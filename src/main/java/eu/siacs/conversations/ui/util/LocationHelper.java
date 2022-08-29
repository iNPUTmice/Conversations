package eu.siacs.conversations.ui.util;

import android.location.Location;

import org.osmdroid.util.GeoPoint;

import eu.siacs.conversations.Config;

public final class LocationHelper {
	/**
	 * Parses a lat long string in the form "lat,long".
	 *
	 * @param latlong A string in the form "lat,long"
	 * @return A GeoPoint representing the lat,long string.
	 * @throws NumberFormatException If an invalid lat or long is specified.
	 */
	public static GeoPoint parseLatLong(final String latlong) throws NumberFormatException {
		if (latlong == null || latlong.isEmpty()) {
			return null;
		}

		final String[] parts = latlong.split(",");
		if (parts[1].contains("?")) {
			parts[1] = parts[1].substring(0, parts[1].indexOf("?"));
		}
		return new GeoPoint(Double.valueOf(parts[0]), Double.valueOf(parts[1]));
	}

	private static boolean isSameProvider(final String provider1, final String provider2) {
		if (provider1 == null) {
			return provider2 == null;
		}
		return provider1.equals(provider2);
	}

	public static boolean isBetterLocation(final Location location, final Location prevLoc) {
		if (prevLoc == null) {
			return true;
		}

		// Check whether the new location fix is newer or older
		final long timeDelta = location.getTime() - prevLoc.getTime();
		final boolean isSignificantlyNewer = timeDelta > Config.Map.LOCATION_FIX_SIGNIFICANT_TIME_DELTA;
		final boolean isSignificantlyOlder = timeDelta < -Config.Map.LOCATION_FIX_SIGNIFICANT_TIME_DELTA;
		final boolean isNewer = timeDelta > 0;

		if (isSignificantlyNewer) {
			return true;
		} else if (isSignificantlyOlder) {
			return false;
		}

		// Check whether the new location fix is more or less accurate
		final int accuracyDelta = (int) (location.getAccuracy() - prevLoc.getAccuracy());
		final boolean isLessAccurate = accuracyDelta > 0;
		final boolean isMoreAccurate = accuracyDelta < 0;
		final boolean isSignificantlyLessAccurate = accuracyDelta > 200;

		// Check if the old and new location are from the same provider
		final boolean isFromSameProvider = isSameProvider(location.getProvider(), prevLoc.getProvider());

		// Determine location quality using a combination of timeliness and accuracy
		if (isMoreAccurate) {
			return true;
		} else if (isNewer && !isLessAccurate) {
			return true;
		} else return isNewer && !isSignificantlyLessAccurate && isFromSameProvider;
    }
}