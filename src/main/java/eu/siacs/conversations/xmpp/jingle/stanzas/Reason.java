package eu.siacs.conversations.xmpp.jingle.stanzas;

import androidx.annotation.NonNull;

import com.google.common.base.CaseFormat;
import com.google.common.base.Throwables;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.CryptoFailedException;
import eu.siacs.conversations.xmpp.jingle.RtpContentMap;

public enum Reason {
    ALTERNATIVE_SESSION,
    BUSY,
    CANCEL,
    CONNECTIVITY_ERROR,
    DECLINE,
    EXPIRED,
    FAILED_APPLICATION,
    FAILED_TRANSPORT,
    GENERAL_ERROR,
    GONE,
    INCOMPATIBLE_PARAMETERS,
    MEDIA_ERROR,
    SECURITY_ERROR,
    SUCCESS,
    TIMEOUT,
    UNSUPPORTED_APPLICATIONS,
    UNSUPPORTED_TRANSPORTS,
    UNKNOWN;

    public static Reason of(final String value) {
        try {
            return Reason.valueOf(CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, value));
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    @Override
    @NonNull
    public String toString() {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, super.toString());
    }

    public static Reason of(final RuntimeException e) {
        if (e instanceof SecurityException) {
            return SECURITY_ERROR;
        } else if (e instanceof RtpContentMap.UnsupportedTransportException) {
            return UNSUPPORTED_TRANSPORTS;
        } else if (e instanceof RtpContentMap.UnsupportedApplicationException) {
            return UNSUPPORTED_APPLICATIONS;
        } else {
            return FAILED_APPLICATION;
        }
    }

    public static Reason ofThrowable(final Throwable throwable) {
        final Throwable root = Throwables.getRootCause(throwable);
        if (root instanceof RuntimeException) {
            return of((RuntimeException) root);
        }
        if (root instanceof CryptoFailedException) {
            return SECURITY_ERROR;
        }
        return FAILED_APPLICATION;
    }
}