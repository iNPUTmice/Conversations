package eu.siacs.conversations.entities;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import eu.siacs.conversations.xmpp.Jid;

public class AccountConfiguration {

    private static final Gson GSON = new GsonBuilder().create();

    public Protocol protocol;
    public String address;
    public String password;

    public Jid getJid() {
        return Jid.ofEscaped(address);
    }

    public static AccountConfiguration parse(final String input) {
        final AccountConfiguration c;
        try {
            c = GSON.fromJson(input, AccountConfiguration.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Not a valid JSON string", e);
        }
        Preconditions.checkArgument(
                c.protocol == Protocol.XMPP,
                "Protocol must be XMPP"
        );
        Preconditions.checkArgument(
                c.address != null && c.getJid().isBareJid() && !c.getJid().isDomainJid(),
                "Invalid XMPP address"
        );
        Preconditions.checkArgument(
                c.password != null && c.password.length() > 0,
                "No password specified"
        );
        return c;
    }

    public enum Protocol {
        @SerializedName("xmpp") XMPP,
    }

}

