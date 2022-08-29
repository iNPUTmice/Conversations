package eu.siacs.conversations.xmpp.jingle;

import android.os.PowerManager;
import android.util.Log;

import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.SocksSocketFactory;
import eu.siacs.conversations.utils.WakeLockHelper;
import eu.siacs.conversations.xmpp.jingle.stanzas.FileTransferDescription;

public class JingleSocks5Transport extends JingleTransport {

    private static final int SOCKET_TIMEOUT_DIRECT = 3000;
    private static final int SOCKET_TIMEOUT_PROXY = 5000;

    private final JingleCandidate candidate;
    private final JingleFileTransferConnection connection;
    private final String destination;
    private final Account account;
    private OutputStream outputStream;
    private InputStream inputStream;
    private boolean isEstablished = false;
    private boolean activated = false;
    private ServerSocket serverSocket;
    private Socket socket;

    JingleSocks5Transport(JingleFileTransferConnection jingleConnection, JingleCandidate candidate) {
        final MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        this.candidate = candidate;
        this.connection = jingleConnection;
        this.account = jingleConnection.getId().account;
        final StringBuilder destBuilder = new StringBuilder();
        if (this.connection.getFtVersion() == FileTransferDescription.Version.FT_3) {
            Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": using session Id instead of transport Id for proxy destination");
            destBuilder.append(this.connection.getId().sessionId);
        } else {
            destBuilder.append(this.connection.getTransportId());
        }
        if (candidate.isOurs()) {
            destBuilder.append(this.account.getJid());
            destBuilder.append(this.connection.getId().with);
        } else {
            destBuilder.append(this.connection.getId().with);
            destBuilder.append(this.account.getJid());
        }
        messageDigest.reset();
        this.destination = CryptoHelper.bytesToHex(messageDigest.digest(destBuilder.toString().getBytes()));
        if (candidate.isOurs() && candidate.getType() == JingleCandidate.TYPE_DIRECT) {
            createServerSocket();
        }
    }

    private void createServerSocket() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(candidate.getHost()), candidate.getPort()));
            new Thread(() -> {
                try {
                    final Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try {
                            acceptIncomingSocketConnection(socket);
                        } catch (IOException e) {
                            Log.d(Config.LOGTAG, "unable to read from socket", e);

                        }
                    }).start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        Log.d(Config.LOGTAG, "unable to accept socket", e);
                    }
                }
            }).start();
        } catch (IOException e) {
            Log.d(Config.LOGTAG, "unable to bind server socket ", e);
        }
    }

    private void acceptIncomingSocketConnection(final Socket socket) throws IOException {
        Log.d(Config.LOGTAG, "accepted connection from " + socket.getInetAddress().getHostAddress());
        socket.setSoTimeout(SOCKET_TIMEOUT_DIRECT);
        final byte[] authBegin = new byte[2];
        final InputStream inputStream = socket.getInputStream();
        final OutputStream outputStream = socket.getOutputStream();
        ByteStreams.readFully(inputStream, authBegin);
        if (authBegin[0] != 0x5) {
            socket.close();
        }
        final short methodCount = authBegin[1];
        final byte[] methods = new byte[methodCount];
        ByteStreams.readFully(inputStream, methods);
        if (SocksSocketFactory.contains((byte) 0x00, methods)) {
            outputStream.write(new byte[]{0x05, 0x00});
        } else {
            outputStream.write(new byte[]{0x05, (byte) 0xff});
        }
        final byte[] connectCommand = new byte[4];
        ByteStreams.readFully(inputStream, connectCommand);
        if (connectCommand[0] == 0x05 && connectCommand[1] == 0x01 && connectCommand[3] == 0x03) {
            int destinationCount = inputStream.read();
            final byte[] destination = new byte[destinationCount];
            ByteStreams.readFully(inputStream, destination);
            final byte[] port = new byte[2];
            ByteStreams.readFully(inputStream, port);
            final String receivedDestination = new String(destination);
            final ByteBuffer response = ByteBuffer.allocate(7 + destination.length);
            final byte[] responseHeader;
            final boolean success;
            if (receivedDestination.equals(this.destination) && this.socket == null) {
                responseHeader = new byte[]{0x05, 0x00, 0x00, 0x03};
                success = true;
            } else {
                Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": destination mismatch. received " + receivedDestination + " (expected " + this.destination + ")");
                responseHeader = new byte[]{0x05, 0x04, 0x00, 0x03};
                success = false;
            }
            response.put(responseHeader);
            response.put((byte) destination.length);
            response.put(destination);
            response.put(port);
            outputStream.write(response.array());
            outputStream.flush();
            if (success) {
                Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": successfully processed connection to candidate " + candidate.getHost() + ":" + candidate.getPort());
                socket.setSoTimeout(0);
                this.socket = socket;
                this.inputStream = inputStream;
                this.outputStream = outputStream;
                this.isEstablished = true;
                FileBackend.close(serverSocket);
            } else {
                FileBackend.close(socket);
            }
        } else {
            socket.close();
        }
    }

    public void connect(final OnTransportConnected callback) {
        new Thread(() -> {
            final int timeout = candidate.getType() == JingleCandidate.TYPE_DIRECT ? SOCKET_TIMEOUT_DIRECT : SOCKET_TIMEOUT_PROXY;
            try {
                final boolean useTor = this.account.isOnion() || connection.getConnectionManager().getXmppConnectionService().useTorToConnect();
                if (useTor) {
                    socket = SocksSocketFactory.createSocketOverTor(candidate.getHost(), candidate.getPort());
                } else {
                    socket = new Socket();
                    SocketAddress address = new InetSocketAddress(candidate.getHost(), candidate.getPort());
                    socket.connect(address, timeout);
                }
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                socket.setSoTimeout(timeout);
                SocksSocketFactory.createSocksConnection(socket, destination, 0);
                socket.setSoTimeout(0);
                isEstablished = true;
                callback.established();
            } catch (final IOException e) {
                callback.failed();
            }
        }).start();

    }

    public void send(final DownloadableFile file, final OnFileTransmissionStatusChanged callback) {
        new Thread(() -> {
            InputStream fileInputStream = null;
            final PowerManager.WakeLock wakeLock = connection.getConnectionManager().createWakeLock("jingle_send_" + connection.getId().sessionId);
            long transmitted = 0;
            try {
                wakeLock.acquire();
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.reset();
                fileInputStream = connection.getFileInputStream();
                if (fileInputStream == null) {
                    Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": could not create input stream");
                    callback.onFileTransferAborted();
                    return;
                }
                final InputStream innerInputStream = AbstractConnectionManager.upgrade(file, fileInputStream);
                long size = file.getExpectedSize();
                int count;
                byte[] buffer = new byte[8192];
                while ((count = innerInputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    transmitted += count;
                    connection.updateProgress((int) ((((double) transmitted) / size) * 100));
                }
                outputStream.flush();
                file.setSha1Sum(digest.digest());
                if (callback != null) {
                    callback.onFileTransmitted(file);
                }
            } catch (Exception e) {
                final Account account = this.account;
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": failed sending file after " + transmitted + "/" + file.getExpectedSize() + " (" + socket.getInetAddress() + ":" + socket.getPort() + ")", e);
                callback.onFileTransferAborted();
            } finally {
                FileBackend.close(fileInputStream);
                WakeLockHelper.release(wakeLock);
            }
        }).start();

    }

    public void receive(final DownloadableFile file, final OnFileTransmissionStatusChanged callback) {
        new Thread(() -> {
            OutputStream fileOutputStream = null;
            final PowerManager.WakeLock wakeLock = connection.getConnectionManager().createWakeLock("jingle_receive_" + connection.getId().sessionId);
            try {
                wakeLock.acquire();
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.reset();
                //inputStream.skip(45);
                socket.setSoTimeout(30000);
                fileOutputStream = connection.getFileOutputStream();
                if (fileOutputStream == null) {
                    callback.onFileTransferAborted();
                    Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": could not create output stream");
                    return;
                }
                double size = file.getExpectedSize();
                long remainingSize = file.getExpectedSize();
                byte[] buffer = new byte[8192];
                int count;
                while (remainingSize > 0) {
                    count = inputStream.read(buffer);
                    if (count == -1) {
                        callback.onFileTransferAborted();
                        Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": file ended prematurely with " + remainingSize + " bytes remaining");
                        return;
                    } else {
                        fileOutputStream.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        remainingSize -= count;
                    }
                    connection.updateProgress((int) (((size - remainingSize) / size) * 100));
                }
                fileOutputStream.flush();
                fileOutputStream.close();
                file.setSha1Sum(digest.digest());
                callback.onFileTransmitted(file);
            } catch (Exception e) {
                Log.d(Config.LOGTAG, this.account.getJid().asBareJid() + ": " + e.getMessage());
                callback.onFileTransferAborted();
            } finally {
                WakeLockHelper.release(wakeLock);
                FileBackend.close(fileOutputStream);
                FileBackend.close(inputStream);
            }
        }).start();
    }

    public boolean isProxy() {
        return this.candidate.getType() == JingleCandidate.TYPE_PROXY;
    }

    public boolean needsActivation() {
        return (this.isProxy() && !this.activated);
    }

    public void disconnect() {
        FileBackend.close(inputStream);
        FileBackend.close(outputStream);
        FileBackend.close(socket);
        FileBackend.close(serverSocket);
    }

    public boolean isEstablished() {
        return this.isEstablished;
    }

    public JingleCandidate getCandidate() {
        return this.candidate;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }
}
