package eu.siacs.conversations.crypto.sasl;

import android.util.Base64;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.xml.TagWriter;

public class DigestMd5 extends SaslMechanism {

    public static final String MECHANISM = "DIGEST-MD5";

    public DigestMd5(final TagWriter tagWriter, final Account account, final SecureRandom rng) {
        super(tagWriter, account, rng);
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }

    private State state = State.INITIAL;

    @Override
    public String getResponse(final String challenge) throws AuthenticationException {
        switch (state) {
            case INITIAL:
                state = State.RESPONSE_SENT;
                final String encodedResponse;
                try {
                    final Tokenizer tokenizer = new Tokenizer(Base64.decode(challenge, Base64.DEFAULT));
                    String nonce = "";
                    for (final String token : tokenizer) {
                        final String[] parts = token.split("=", 2);
                        if (parts[0].equals("nonce")) {
                            nonce = parts[1].replace("\"", "");
                        } else if (parts[0].equals("rspauth")) {
                            return "";
                        }
                    }
                    final String digestUri = "xmpp/" + account.getServer();
                    final String nonceCount = "00000001";
                    final String x = account.getUsername() + ":" + account.getServer() + ":"
                            + account.getPassword();
                    final MessageDigest md = MessageDigest.getInstance("MD5");
                    final byte[] y = md.digest(x.getBytes(Charset.defaultCharset()));
                    final String cNonce = CryptoHelper.random(100, rng);
                    final byte[] a1 = CryptoHelper.concatenateByteArrays(y,
                            (":" + nonce + ":" + cNonce).getBytes(Charset.defaultCharset()));
                    final String a2 = "AUTHENTICATE:" + digestUri;
                    final String ha1 = CryptoHelper.bytesToHex(md.digest(a1));
                    final String ha2 = CryptoHelper.bytesToHex(md.digest(a2.getBytes(Charset
                            .defaultCharset())));
                    final String kd = ha1 + ":" + nonce + ":" + nonceCount + ":" + cNonce
                            + ":auth:" + ha2;
                    final String response = CryptoHelper.bytesToHex(md.digest(kd.getBytes(Charset
                            .defaultCharset())));
                    final String saslString = "username=\"" + account.getUsername()
                            + "\",realm=\"" + account.getServer() + "\",nonce=\""
                            + nonce + "\",cnonce=\"" + cNonce + "\",nc=" + nonceCount
                            + ",qop=auth,digest-uri=\"" + digestUri + "\",response="
                            + response + ",charset=utf-8";
                    encodedResponse = Base64.encodeToString(
                            saslString.getBytes(Charset.defaultCharset()),
                            Base64.NO_WRAP);
                } catch (final NoSuchAlgorithmException e) {
                    throw new AuthenticationException(e);
                }

                return encodedResponse;
            case RESPONSE_SENT:
                state = State.VALID_SERVER_RESPONSE;
                break;
            case VALID_SERVER_RESPONSE:
                if (challenge == null) {
                    return null; //everything is fine
                }
            default:
                throw new InvalidStateException(state);
        }
        return null;
    }
}
