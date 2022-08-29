package eu.siacs.conversations.utils;

import android.os.Bundle;
import android.util.Base64;
import android.util.Pair;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;

public final class CryptoHelper {

    public static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    final public static byte[] ONE = new byte[]{0, 0, 0, 1};
    private static final char[] CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz123456789+-/#$!?".toCharArray();
    private static final int PW_LENGTH = 12;
    private static final char[] VOWELS = "aeiou".toCharArray();
    private static final char[] CONSONANTS = "bcfghjklmnpqrstvwxyz".toCharArray();
    private final static char[] hexArray = "0123456789abcdef".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String createPassword(SecureRandom random) {
        StringBuilder builder = new StringBuilder(PW_LENGTH);
        for (int i = 0; i < PW_LENGTH; ++i) {
            builder.append(CHARS[random.nextInt(CHARS.length - 1)]);
        }
        return builder.toString();
    }

    public static String pronounceable(SecureRandom random) {
        final int rand = random.nextInt(4);
        char[] output = new char[rand * 2 + (5 - rand)];
        boolean vowel = random.nextBoolean();
        for (int i = 0; i < output.length; ++i) {
            output[i] = vowel ? VOWELS[random.nextInt(VOWELS.length)] : CONSONANTS[random.nextInt(CONSONANTS.length)];
            vowel = !vowel;
        }
        return String.valueOf(output);
    }

    public static byte[] hexToBytes(String hexString) {
        int len = hexString.length();
        byte[] array = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            array[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4) + Character
                    .digit(hexString.charAt(i + 1), 16));
        }
        return array;
    }

    public static String hexToString(final String hexString) {
        return new String(hexToBytes(hexString));
    }

    public static byte[] concatenateByteArrays(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Escapes usernames or passwords for SASL.
     */
    public static String saslEscape(final String s) {
        final StringBuilder sb = new StringBuilder((int) (s.length() * 1.1));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ',':
                    sb.append("=2C");
                    break;
                case '=':
                    sb.append("=3D");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    public static String saslPrep(final String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    public static String random(int length, SecureRandom random) {
        final byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
    }

    public static String prettifyFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return "";
        } else if (fingerprint.length() < 40) {
            return fingerprint;
        }
        StringBuilder builder = new StringBuilder(fingerprint);
        for (int i = 8; i < builder.length(); i += 9) {
            builder.insert(i, ' ');
        }
        return builder.toString();
    }

    public static String prettifyFingerprintCert(String fingerprint) {
        StringBuilder builder = new StringBuilder(fingerprint);
        for (int i = 2; i < builder.length(); i += 3) {
            builder.insert(i, ':');
        }
        return builder.toString();
    }

    public static String[] getOrderedCipherSuites(final String[] platformSupportedCipherSuites) {
        final Collection<String> cipherSuites = new LinkedHashSet<>(Arrays.asList(Config.ENABLED_CIPHERS));
        final List<String> platformCiphers = Arrays.asList(platformSupportedCipherSuites);
        cipherSuites.retainAll(platformCiphers);
        cipherSuites.addAll(platformCiphers);
        filterWeakCipherSuites(cipherSuites);
        cipherSuites.remove("TLS_FALLBACK_SCSV");
        return cipherSuites.toArray(new String[cipherSuites.size()]);
    }

    private static void filterWeakCipherSuites(final Collection<String> cipherSuites) {
        final Iterator<String> it = cipherSuites.iterator();
        while (it.hasNext()) {
            String cipherName = it.next();
            // remove all ciphers with no or very weak encryption or no authentication
            for (String weakCipherPattern : Config.WEAK_CIPHER_PATTERNS) {
                if (cipherName.contains(weakCipherPattern)) {
                    it.remove();
                    break;
                }
            }
        }
    }

    public static Pair<Jid, String> extractJidAndName(X509Certificate certificate) throws CertificateEncodingException, IllegalArgumentException, CertificateParsingException {
        Collection<List<?>> alternativeNames = certificate.getSubjectAlternativeNames();
        List<String> emails = new ArrayList<>();
        if (alternativeNames != null) {
            for (List<?> san : alternativeNames) {
                Integer type = (Integer) san.get(0);
                if (type == 1) {
                    emails.add((String) san.get(1));
                }
            }
        }
        X500Name x500name = new JcaX509CertificateHolder(certificate).getSubject();
        if (emails.size() == 0 && x500name.getRDNs(BCStyle.EmailAddress).length > 0) {
            emails.add(IETFUtils.valueToString(x500name.getRDNs(BCStyle.EmailAddress)[0].getFirst().getValue()));
        }
        String name = x500name.getRDNs(BCStyle.CN).length > 0 ? IETFUtils.valueToString(x500name.getRDNs(BCStyle.CN)[0].getFirst().getValue()) : null;
        if (emails.size() >= 1) {
            return new Pair<>(Jid.of(emails.get(0)), name);
        } else if (name != null) {
            try {
                Jid jid = Jid.of(name);
                if (jid.isBareJid() && jid.getLocal() != null) {
                    return new Pair<>(jid, null);
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public static Bundle extractCertificateInformation(X509Certificate certificate) {
        Bundle information = new Bundle();
        try {
            JcaX509CertificateHolder holder = new JcaX509CertificateHolder(certificate);
            X500Name subject = holder.getSubject();
            try {
                information.putString("subject_cn", subject.getRDNs(BCStyle.CN)[0].getFirst().getValue().toString());
            } catch (Exception e) {
                //ignored
            }
            try {
                information.putString("subject_o", subject.getRDNs(BCStyle.O)[0].getFirst().getValue().toString());
            } catch (Exception e) {
                //ignored
            }

            X500Name issuer = holder.getIssuer();
            try {
                information.putString("issuer_cn", issuer.getRDNs(BCStyle.CN)[0].getFirst().getValue().toString());
            } catch (Exception e) {
                //ignored
            }
            try {
                information.putString("issuer_o", issuer.getRDNs(BCStyle.O)[0].getFirst().getValue().toString());
            } catch (Exception e) {
                //ignored
            }
            try {
                information.putString("sha1", getFingerprintCert(certificate.getEncoded()));
            } catch (Exception e) {

            }
            return information;
        } catch (CertificateEncodingException e) {
            return information;
        }
    }

    public static String getFingerprintCert(byte[] input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] fingerprint = md.digest(input);
        return prettifyFingerprintCert(bytesToHex(fingerprint));
    }

    public static String getFingerprint(Jid jid, String androidId) {
        return getFingerprint(jid.toEscapedString() + "\00" + androidId);
    }

    public static String getAccountFingerprint(Account account, String androidId) {
        return getFingerprint(account.getJid().asBareJid(), androidId);
    }

    public static String getFingerprint(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return bytesToHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    public static int encryptionTypeToText(int encryption) {
        switch (encryption) {
            case Message.ENCRYPTION_OTR:
                return R.string.encryption_choice_otr;
            case Message.ENCRYPTION_AXOLOTL:
            case Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE:
            case Message.ENCRYPTION_AXOLOTL_FAILED:
                return R.string.encryption_choice_omemo;
            case Message.ENCRYPTION_NONE:
                return R.string.encryption_choice_unencrypted;
            default:
                return R.string.encryption_choice_pgp;
        }
    }

    public static boolean isPgpEncryptedUrl(String url) {
        if (url == null) {
            return false;
        }
        final String u = url.toLowerCase();
        return !u.contains(" ") && (u.startsWith("https://") || u.startsWith("http://") || u.startsWith("p1s3://")) && u.endsWith(".pgp");
    }
}
