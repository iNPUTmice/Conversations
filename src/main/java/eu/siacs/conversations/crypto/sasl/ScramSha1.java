package eu.siacs.conversations.crypto.sasl;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.macs.HMac;

import java.security.SecureRandom;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xml.TagWriter;

public class ScramSha1 extends ScramMechanism {

    public static final String MECHANISM = "SCRAM-SHA-1";

    @Override
    protected HMac getHMAC() {
        return new HMac(new SHA1Digest());
    }

    @Override
    protected Digest getDigest() {
        return new SHA1Digest();
    }

    public ScramSha1(final TagWriter tagWriter, final Account account, final SecureRandom rng) {
        super(tagWriter, account, rng);
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }
}
