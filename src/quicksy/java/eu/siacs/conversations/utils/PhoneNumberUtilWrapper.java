package eu.siacs.conversations.utils;

import android.content.Context;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.xmpp.Jid;
import io.michaelrocks.libphonenumber.android.NumberParseException;
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil;
import io.michaelrocks.libphonenumber.android.Phonenumber;

public class PhoneNumberUtilWrapper {

    private static volatile PhoneNumberUtil instance;


    public static String getCountryForCode(String code) {
        Locale locale = new Locale("", code);
        return locale.getDisplayCountry();
    }

    public static String toFormattedPhoneNumber(Context context, Jid jid) {
        try {
            return getInstance(context).format(toPhoneNumber(context, jid), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL).replace(' ','\u202F');
        } catch (Exception e) {
            return jid.getEscapedLocal();
        }
    }

    public static Phonenumber.PhoneNumber toPhoneNumber(Context context, Jid jid) throws NumberParseException {
        return getInstance(context).parse(jid.getEscapedLocal(), "de");
    }

    public static String normalize(Context context, String input) throws IllegalArgumentException, NumberParseException {
        final Phonenumber.PhoneNumber number = getInstance(context).parse(input, LocationProvider.getUserCountry(context));
        if (!getInstance(context).isValidNumber(number)) {
            throw new IllegalArgumentException(String.format("%s is not a valid phone number", input));
        }
        return normalize(context, number);
    }

    public static String normalize(Context context, Phonenumber.PhoneNumber phoneNumber) {
        return getInstance(context).format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    }

    public static PhoneNumberUtil getInstance(final Context context) {
        PhoneNumberUtil localInstance = instance;
        if (localInstance == null) {
            synchronized (PhoneNumberUtilWrapper.class) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = PhoneNumberUtil.createInstance(context);
                }

            }
        }
        return localInstance;
    }

    public static List<Country> getCountries(final Context context) {
        List<Country> countries = new ArrayList<>();
        for (String region : getInstance(context).getSupportedRegions()) {
            countries.add(new Country(region, getInstance(context).getCountryCodeForRegion(region)));
        }
        return countries;

    }

    public static class Country implements Comparable<Country> {
        private final String name;
        private final String region;
        private final int code;

        Country(String region, int code) {
            this.name = getCountryForCode(region);
            this.region = region;
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public String getRegion() {
            return region;
        }

        public String getCode() {
            return '+' + String.valueOf(code);
        }

        @Override
        public int compareTo(Country o) {
            return name.compareTo(o.name);
        }
    }

}
