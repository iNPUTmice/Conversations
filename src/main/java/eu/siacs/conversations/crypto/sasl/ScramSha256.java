package eu.siacs.conversations.crypto.sasl;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;

import java.security.SecureRandom;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xml.TagWriter;

public class ScramSha256 extends ScramMechanism {

    public static final String MECHANISM = "SCRAM-SHA-256";

    @Override
    protected HMac getHMAC() {
        return new HMac(new SHA256Digest());
    }

    @Override
    protected Digest getDigest() {
        return new SHA256Digest();
    }

    public ScramSha256(final TagWriter tagWriter, final Account account, final SecureRandom rng) {
        super(tagWriter, account, rng);
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }
}
