package eu.siacs.conversations.utils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import eu.siacs.conversations.xmpp.Jid;

public class BackupFileHeader {

    private static final int VERSION = 1;

    private final String app;
    private final Jid jid;
    private final long timestamp;
    private final byte[] iv;
    private final byte[] salt;


    @Override
    public String toString() {
        return "BackupFileHeader{" +
                "app='" + app + '\'' +
                ", jid=" + jid +
                ", timestamp=" + timestamp +
                ", iv=" + CryptoHelper.bytesToHex(iv) +
                ", salt=" + CryptoHelper.bytesToHex(salt) +
                '}';
    }

    public BackupFileHeader(String app, Jid jid, long timestamp, byte[] iv, byte[] salt) {
        this.app = app;
        this.jid = jid;
        this.timestamp = timestamp;
        this.iv = iv;
        this.salt = salt;
    }

    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(VERSION);
        dataOutputStream.writeUTF(app);
        dataOutputStream.writeUTF(jid.asBareJid().toEscapedString());
        dataOutputStream.writeLong(timestamp);
        dataOutputStream.write(iv);
        dataOutputStream.write(salt);
    }

    public static BackupFileHeader read(DataInputStream inputStream) throws IOException {
        final int version = inputStream.readInt();
        if (version > VERSION) {
            throw new IllegalArgumentException("Backup File version was " + version + " but app only supports up to version " + VERSION);
        }
        String app = inputStream.readUTF();
        String jid = inputStream.readUTF();
        long timestamp = inputStream.readLong();
        byte[] iv = new byte[12];
        inputStream.readFully(iv);
        byte[] salt = new byte[16];
        inputStream.readFully(salt);

        return new BackupFileHeader(app, Jid.of(jid), timestamp, iv, salt);

    }

    public byte[] getSalt() {
        return salt;
    }

    public byte[] getIv() {
        return iv;
    }

    public Jid getJid() {
        return jid;
    }

    public String getApp() {
        return app;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
