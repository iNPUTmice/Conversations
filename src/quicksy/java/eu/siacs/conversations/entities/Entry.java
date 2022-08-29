package eu.siacs.conversations.entities;

import android.util.Base64;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.android.PhoneNumberContact;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class Entry implements Comparable<Entry> {
    private final List<Jid> jids;
    private final String number;

    private Entry(String number, List<Jid> jids) {
        this.number = number;
        this.jids = jids;
    }

    public static Entry of(Element element) {
        final String number = element.getAttribute("number");
        final List<Jid> jids = new ArrayList<>();
        for (Element jidElement : element.getChildren()) {
            String content = jidElement.getContent();
            if (content != null) {
                jids.add(Jid.of(content));
            }
        }
        return new Entry(number, jids);
    }

    public static List<Entry> ofPhoneBook(Element phoneBook) {
        List<Entry> entries = new ArrayList<>();
        for (Element entry : phoneBook.getChildren()) {
            if ("entry".equals(entry.getName())) {
                entries.add(of(entry));
            }
        }
        return entries;
    }

    public static String statusQuo(final Collection<PhoneNumberContact> phoneNumberContacts, Collection<Contact> systemContacts) {
        return statusQuo(ofPhoneNumberContactsAndContacts(phoneNumberContacts, systemContacts));
    }

    private static String statusQuo(final List<Entry> entries) {
        Collections.sort(entries);
        StringBuilder builder = new StringBuilder();
        for(Entry entry : entries) {
            if (builder.length() != 0) {
                builder.append('\u001d');
            }
            builder.append(entry.getNumber());
            List<Jid> jids = entry.getJids();
            Collections.sort(jids);
            for(Jid jid : jids) {
                builder.append('\u001e');
                builder.append(jid.asBareJid().toEscapedString());
            }
        }
        @SuppressWarnings("deprecation")
        final byte[] sha1 = Hashing.sha1().hashString(builder.toString(), Charsets.UTF_8).asBytes();
        return new String(Base64.encode(sha1, Base64.DEFAULT)).trim();
    }

    private static List<Entry> ofPhoneNumberContactsAndContacts(final Collection<PhoneNumberContact> phoneNumberContacts, Collection<Contact> systemContacts) {
        final ArrayList<Entry> entries = new ArrayList<>();
        for(Contact contact : systemContacts) {
            final PhoneNumberContact phoneNumberContact = PhoneNumberContact.findByUri(phoneNumberContacts, contact.getSystemAccount());
            if (phoneNumberContact != null && phoneNumberContact.getPhoneNumber() != null) {
                Entry entry = findOrCreateByPhoneNumber(entries, phoneNumberContact.getPhoneNumber());
                entry.jids.add(contact.getJid().asBareJid());
            }
        }
        return entries;
    }

    private static Entry findOrCreateByPhoneNumber(final List<Entry> entries, String number) {
        for(Entry entry : entries) {
            if (entry.number.equals(number)) {
                return entry;
            }
        }
        Entry entry = new Entry(number, new ArrayList<>());
        entries.add(entry);
        return entry;
    }

    public List<Jid> getJids() {
        return jids;
    }

    public String getNumber() {
        return number;
    }

    @Override
    public int compareTo(Entry o) {
        return number.compareTo(o.number);
    }
}
