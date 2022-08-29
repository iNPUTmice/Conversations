package eu.siacs.conversations.crypto.axolotl;

import android.util.Base64;
import android.util.Log;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class XmppAxolotlMessage {
    public static final String CONTAINERTAG = "encrypted";
    private static final String HEADER = "header";
    private static final String SOURCEID = "sid";
    private static final String KEYTAG = "key";
    private static final String REMOTEID = "rid";
    private static final String IVTAG = "iv";
    private static final String PAYLOAD = "payload";

    private static final String KEYTYPE = "AES";
    private static final String CIPHERMODE = "AES/GCM/NoPadding";
    private static final String PROVIDER = "BC";
    private final List<XmppAxolotlSession.AxolotlKey> keys;
    private final Jid from;
    private final int sourceDeviceId;
    private byte[] innerKey;
    private byte[] ciphertext = null;
    private byte[] authtagPlusInnerKey = null;
    private byte[] iv = null;

    private XmppAxolotlMessage(final Element axolotlMessage, final Jid from) throws IllegalArgumentException {
        this.from = from;
        Element header = axolotlMessage.findChild(HEADER);
        try {
            this.sourceDeviceId = Integer.parseInt(header.getAttribute(SOURCEID));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid source id");
        }
        List<Element> keyElements = header.getChildren();
        this.keys = new ArrayList<>();
        for (Element keyElement : keyElements) {
            switch (keyElement.getName()) {
                case KEYTAG:
                    try {
                        int recipientId = Integer.parseInt(keyElement.getAttribute(REMOTEID));
                        byte[] key = Base64.decode(keyElement.getContent().trim(), Base64.DEFAULT);
                        boolean isPreKey = keyElement.getAttributeAsBoolean("prekey");
                        this.keys.add(new XmppAxolotlSession.AxolotlKey(recipientId, key, isPreKey));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("invalid remote id");
                    }
                    break;
                case IVTAG:
                    if (this.iv != null) {
                        throw new IllegalArgumentException("Duplicate iv entry");
                    }
                    iv = Base64.decode(keyElement.getContent().trim(), Base64.DEFAULT);
                    break;
                default:
                    Log.w(Config.LOGTAG, "Unexpected element in header: " + keyElement.toString());
                    break;
            }
        }
        final Element payloadElement = axolotlMessage.findChildEnsureSingle(PAYLOAD, AxolotlService.PEP_PREFIX);
        if (payloadElement != null) {
            ciphertext = Base64.decode(payloadElement.getContent().trim(), Base64.DEFAULT);
        }
    }

    XmppAxolotlMessage(Jid from, int sourceDeviceId) {
        this.from = from;
        this.sourceDeviceId = sourceDeviceId;
        this.keys = new ArrayList<>();
        this.iv = generateIv();
        this.innerKey = generateKey();
    }

    public static int parseSourceId(final Element axolotlMessage) throws IllegalArgumentException {
        final Element header = axolotlMessage.findChild(HEADER);
        if (header == null) {
            throw new IllegalArgumentException("No header found");
        }
        try {
            return Integer.parseInt(header.getAttribute(SOURCEID));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid source id");
        }
    }

    public static XmppAxolotlMessage fromElement(Element element, Jid from) {
        return new XmppAxolotlMessage(element, from);
    }

    private static byte[] generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KEYTYPE);
            generator.init(128);
            return generator.generateKey().getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] generateIv() {
        final SecureRandom random = new SecureRandom();
        final byte[] iv = new byte[12];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] getPaddedBytes(String plaintext) {
        int plainLength = plaintext.getBytes().length;
        int pad = Math.max(64, (plainLength / 32 + 1) * 32) - plainLength;
        SecureRandom random = new SecureRandom();
        int left = random.nextInt(pad);
        int right = pad - left;
        StringBuilder builder = new StringBuilder(plaintext);
        for (int i = 0; i < left; ++i) {
            builder.insert(0, random.nextBoolean() ? "\t" : " ");
        }
        for (int i = 0; i < right; ++i) {
            builder.append(random.nextBoolean() ? "\t" : " ");
        }
        return builder.toString().getBytes();
    }

    public boolean hasPayload() {
        return ciphertext != null;
    }

    void encrypt(final String plaintext) throws CryptoFailedException {
        try {
            SecretKey secretKey = new SecretKeySpec(innerKey, KEYTYPE);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Compatibility.twentyEight() ? Cipher.getInstance(CIPHERMODE) : Cipher.getInstance(CIPHERMODE, PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            this.ciphertext = cipher.doFinal(Config.OMEMO_PADDING ? getPaddedBytes(plaintext) : plaintext.getBytes());
            if (Config.PUT_AUTH_TAG_INTO_KEY && this.ciphertext != null) {
                this.authtagPlusInnerKey = new byte[16 + 16];
                byte[] ciphertext = new byte[this.ciphertext.length - 16];
                System.arraycopy(this.ciphertext, 0, ciphertext, 0, ciphertext.length);
                System.arraycopy(this.ciphertext, ciphertext.length, authtagPlusInnerKey, 16, 16);
                System.arraycopy(this.innerKey, 0, authtagPlusInnerKey, 0, this.innerKey.length);
                this.ciphertext = ciphertext;
            }
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | IllegalBlockSizeException | BadPaddingException | NoSuchProviderException
                | InvalidAlgorithmParameterException e) {
            throw new CryptoFailedException(e);
        }
    }

    public Jid getFrom() {
        return this.from;
    }

    int getSenderDeviceId() {
        return sourceDeviceId;
    }

    void addDevice(XmppAxolotlSession session) {
        addDevice(session, false);
    }

    void addDevice(XmppAxolotlSession session, boolean ignoreSessionTrust) {
        XmppAxolotlSession.AxolotlKey key;
        if (authtagPlusInnerKey != null) {
            key = session.processSending(authtagPlusInnerKey, ignoreSessionTrust);
        } else {
            key = session.processSending(innerKey, ignoreSessionTrust);
        }
        if (key != null) {
            keys.add(key);
        }
    }

    public byte[] getInnerKey() {
        return innerKey;
    }

    public byte[] getIV() {
        return this.iv;
    }

    public Element toElement() {
        Element encryptionElement = new Element(CONTAINERTAG, AxolotlService.PEP_PREFIX);
        Element headerElement = encryptionElement.addChild(HEADER);
        headerElement.setAttribute(SOURCEID, sourceDeviceId);
        for (XmppAxolotlSession.AxolotlKey key : keys) {
            Element keyElement = new Element(KEYTAG);
            keyElement.setAttribute(REMOTEID, key.deviceId);
            if (key.prekey) {
                keyElement.setAttribute("prekey", "true");
            }
            keyElement.setContent(Base64.encodeToString(key.key, Base64.NO_WRAP));
            headerElement.addChild(keyElement);
        }
        headerElement.addChild(IVTAG).setContent(Base64.encodeToString(iv, Base64.NO_WRAP));
        if (ciphertext != null) {
            Element payload = encryptionElement.addChild(PAYLOAD);
            payload.setContent(Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        }
        return encryptionElement;
    }

    private byte[] unpackKey(XmppAxolotlSession session, Integer sourceDeviceId) throws CryptoFailedException {
        ArrayList<XmppAxolotlSession.AxolotlKey> possibleKeys = new ArrayList<>();
        for (XmppAxolotlSession.AxolotlKey key : keys) {
            if (key.deviceId == sourceDeviceId) {
                possibleKeys.add(key);
            }
        }
        if (possibleKeys.size() == 0) {
            throw new NotEncryptedForThisDeviceException();
        }
        return session.processReceiving(possibleKeys);
    }

    XmppAxolotlKeyTransportMessage getParameters(XmppAxolotlSession session, Integer sourceDeviceId) throws CryptoFailedException {
        return new XmppAxolotlKeyTransportMessage(session.getFingerprint(), unpackKey(session, sourceDeviceId), getIV());
    }

    public XmppAxolotlPlaintextMessage decrypt(XmppAxolotlSession session, Integer sourceDeviceId) throws CryptoFailedException {
        XmppAxolotlPlaintextMessage plaintextMessage = null;
        byte[] key = unpackKey(session, sourceDeviceId);
        if (key != null) {
            try {
                if (key.length < 32) {
                    throw new OutdatedSenderException("Key did not contain auth tag. Sender needs to update their OMEMO client");
                }
                final int authTagLength = key.length - 16;
                byte[] newCipherText = new byte[key.length - 16 + ciphertext.length];
                byte[] newKey = new byte[16];
                System.arraycopy(ciphertext, 0, newCipherText, 0, ciphertext.length);
                System.arraycopy(key, 16, newCipherText, ciphertext.length, authTagLength);
                System.arraycopy(key, 0, newKey, 0, newKey.length);
                ciphertext = newCipherText;
                key = newKey;

                final Cipher cipher = Compatibility.twentyEight() ? Cipher.getInstance(CIPHERMODE) : Cipher.getInstance(CIPHERMODE, PROVIDER);
                SecretKeySpec keySpec = new SecretKeySpec(key, KEYTYPE);
                IvParameterSpec ivSpec = new IvParameterSpec(iv);

                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

                String plaintext = new String(cipher.doFinal(ciphertext));
                plaintextMessage = new XmppAxolotlPlaintextMessage(Config.OMEMO_PADDING ? plaintext.trim() : plaintext, session.getFingerprint());

            } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException
                    | BadPaddingException | NoSuchProviderException e) {
                throw new CryptoFailedException(e);
            }
        }
        return plaintextMessage;
    }

    public static class XmppAxolotlPlaintextMessage {
        private final String plaintext;
        private final String fingerprint;

        XmppAxolotlPlaintextMessage(String plaintext, String fingerprint) {
            this.plaintext = plaintext;
            this.fingerprint = fingerprint;
        }

        public String getPlaintext() {
            return plaintext;
        }


        public String getFingerprint() {
            return fingerprint;
        }
    }

    public static class XmppAxolotlKeyTransportMessage {
        private final String fingerprint;
        private final byte[] key;
        private final byte[] iv;

        XmppAxolotlKeyTransportMessage(String fingerprint, byte[] key, byte[] iv) {
            this.fingerprint = fingerprint;
            this.key = key;
            this.iv = iv;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public byte[] getKey() {
            return key;
        }

        public byte[] getIv() {
            return iv;
        }
    }
}
