package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;

import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;

public class OmemoVerification {

    private final AtomicBoolean deviceIdWritten = new AtomicBoolean(false);
    private final AtomicBoolean sessionFingerprintWritten = new AtomicBoolean(false);
    private Integer deviceId;
    private String sessionFingerprint;

    public void setDeviceId(final Integer id) {
        if (deviceIdWritten.compareAndSet(false, true)) {
            this.deviceId = id;
            return;
        }
        throw new IllegalStateException("Device Id has already been set");
    }

    public int getDeviceId() {
        Preconditions.checkNotNull(this.deviceId, "Device ID is null");
        return this.deviceId;
    }

    public boolean hasDeviceId() {
        return this.deviceId != null;
    }

    public void setSessionFingerprint(final String fingerprint) {
        Preconditions.checkNotNull(fingerprint, "Session fingerprint must not be null");
        if (sessionFingerprintWritten.compareAndSet(false, true)) {
            this.sessionFingerprint = fingerprint;
            return;
        }
        throw new IllegalStateException("Session fingerprint has already been set");
    }

    public String getFingerprint() {
        return this.sessionFingerprint;
    }

    public void setOrEnsureEqual(AxolotlService.OmemoVerifiedPayload<?> omemoVerifiedPayload) {
        setOrEnsureEqual(omemoVerifiedPayload.getDeviceId(), omemoVerifiedPayload.getFingerprint());
    }

    public void setOrEnsureEqual(final int deviceId, final String sessionFingerprint) {
        Preconditions.checkNotNull(sessionFingerprint, "Session fingerprint must not be null");
        if (this.deviceIdWritten.get() || this.sessionFingerprintWritten.get()) {
            if (this.sessionFingerprint == null) {
                throw new IllegalStateException("No session fingerprint has been previously provided");
            }
            if (!sessionFingerprint.equals(this.sessionFingerprint)) {
                throw new SecurityException("Session Fingerprints did not match");
            }
            if (this.deviceId == null) {
                throw new IllegalStateException("No Device Id has been previously provided");
            }
            if (this.deviceId != deviceId) {
                throw new IllegalStateException("Device Ids did not match");
            }
        } else {
            this.setSessionFingerprint(sessionFingerprint);
            this.setDeviceId(deviceId);
        }
    }

    public boolean hasFingerprint() {
        return this.sessionFingerprint != null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("deviceId", deviceId)
                .add("fingerprint", sessionFingerprint)
                .toString();
    }
}
