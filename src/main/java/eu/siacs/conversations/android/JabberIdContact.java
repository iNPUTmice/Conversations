package eu.siacs.conversations.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.xmpp.Jid;

public class JabberIdContact extends AbstractPhoneContact {

    private static final String[] PROJECTION = new String[]{ContactsContract.Data._ID,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.PHOTO_URI,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Im.DATA
    };
    private static final String SELECTION = ContactsContract.Data.MIMETYPE + "=? AND (" + ContactsContract.CommonDataKinds.Im.PROTOCOL + "=? or (" + ContactsContract.CommonDataKinds.Im.PROTOCOL + "=? and lower(" + ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL + ")=?))";

    private static final String[] SELECTION_ARGS = {
            ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
            String.valueOf(ContactsContract.CommonDataKinds.Im.PROTOCOL_JABBER),
            String.valueOf(ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM),
            "xmpp"
    };

    private final Jid jid;

    private JabberIdContact(Cursor cursor) throws IllegalArgumentException {
        super(cursor);
        try {
            this.jid = Jid.of(cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Im.DATA)));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Jid getJid() {
        return jid;
    }

    public static Map<Jid, JabberIdContact> load(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptyMap();
        }
        try (final Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI, PROJECTION, SELECTION, SELECTION_ARGS, null)) {
            if (cursor == null) {
                return Collections.emptyMap();
            }
            final HashMap<Jid, JabberIdContact> contacts = new HashMap<>();
            while (cursor.moveToNext()) {
                try {
                    final JabberIdContact contact = new JabberIdContact(cursor);
                    final JabberIdContact preexisting = contacts.put(contact.getJid(), contact);
                    if (preexisting == null || preexisting.rating() < contact.rating()) {
                        contacts.put(contact.getJid(), contact);
                    }
                } catch (final IllegalArgumentException e) {
                    Log.d(Config.LOGTAG, "unable to create jabber id contact");
                }
            }
            return contacts;
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "unable to query", e);
            return Collections.emptyMap();
        }
    }
}
