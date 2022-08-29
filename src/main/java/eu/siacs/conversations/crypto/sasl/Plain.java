package eu.siacs.conversations.crypto.sasl;

import android.util.Base64;

import java.nio.charset.Charset;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xml.TagWriter;

public class Plain extends SaslMechanism {

    public static final String MECHANISM = "PLAIN";

    public Plain(final TagWriter tagWriter, final Account account) {
        super(tagWriter, account, null);
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }

    @Override
    public String getClientFirstMessage() {
        return getMessage(account.getUsername(), account.getPassword());
    }

    public static String getMessage(String username, String password) {
        final String message = '\u0000' + username + '\u0000' + password;
        return Base64.encodeToString(message.getBytes(Charset.defaultCharset()), Base64.NO_WRAP);
    }
}
