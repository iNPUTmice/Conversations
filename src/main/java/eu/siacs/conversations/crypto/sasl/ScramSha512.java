package eu.siacs.conversations.crypto.sasl;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;

import java.security.SecureRandom;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xml.TagWriter;

public class ScramSha512 extends ScramMechanism {

    public static final String MECHANISM = "SCRAM-SHA-512";

    @Override
    protected HMac getHMAC() {
        return new HMac(new SHA512Digest());
    }

    @Override
    protected Digest getDigest() {
        return new SHA512Digest();
    }

    public ScramSha512(final TagWriter tagWriter, final Account account, final SecureRandom rng) {
        super(tagWriter, account, rng);
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }
}
