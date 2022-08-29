package eu.siacs.conversations.utils;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.osmdroid.util.GeoPoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;

public class LocationProvider {

    public static final GeoPoint FALLBACK = new GeoPoint(0.0, 0.0);

    public static String getUserCountry(final Context context) {
        try {
            final TelephonyManager tm = ContextCompat.getSystemService(context, TelephonyManager.class);
            if (tm == null) {
                return getUserCountryFallback();
            }
            final String simCountry = tm.getSimCountryIso();
            if (simCountry != null && simCountry.length() == 2) { // SIM country code is available
                return simCountry.toUpperCase(Locale.US);
            } else if (tm.getPhoneType() != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
                String networkCountry = tm.getNetworkCountryIso();
                if (networkCountry != null && networkCountry.length() == 2) { // network country code is available
                    return networkCountry.toUpperCase(Locale.US);
                }
            }
            return getUserCountryFallback();
        } catch (final Exception e) {
            return getUserCountryFallback();
        }
    }

    private static String getUserCountryFallback() {
        final Locale locale = Locale.getDefault();
        return locale.getCountry();
    }

    public static GeoPoint getGeoPoint(final Context context) {
        return getGeoPoint(context, getUserCountry(context));
    }


    public static synchronized GeoPoint getGeoPoint(final Context context, final String country) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getResources().openRawResource(R.raw.countries)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] parts = line.split("\\s+", 4);
                if (parts.length == 4) {
                    if (country.equalsIgnoreCase(parts[0])) {
                        try {
                            return new GeoPoint(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                        } catch (final NumberFormatException e) {
                            return FALLBACK;
                        }
                    }
                }
            }
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "unable to parse country->geo map", e);
        }
        return FALLBACK;
    }

}