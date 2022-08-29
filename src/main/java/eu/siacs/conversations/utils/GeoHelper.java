package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;

import org.osmdroid.util.GeoPoint;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ShareLocationActivity;
import eu.siacs.conversations.ui.ShowLocationActivity;

public class GeoHelper {

	private static final String SHARE_LOCATION_PACKAGE_NAME = "eu.siacs.conversations.location.request";
	private static final String SHOW_LOCATION_PACKAGE_NAME = "eu.siacs.conversations.location.show";

	public static Pattern GEO_URI = Pattern.compile("geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,-?\\d+(?:\\.\\d+)?)?(?:;crs=[\\w-]+)?(?:;u=\\d+(?:\\.\\d+)?)?(?:;[\\w-]+=(?:[\\w-_.!~*'()]|%[\\da-f][\\da-f])+)*(\\?z=\\d+)?", Pattern.CASE_INSENSITIVE);

	public static boolean isLocationPluginInstalled(Context context) {
		return new Intent(SHARE_LOCATION_PACKAGE_NAME).resolveActivity(context.getPackageManager()) != null;
	}

	public static boolean isLocationPluginInstalledAndDesired(Context context) {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		final boolean configured = preferences.getBoolean("use_share_location_plugin", context.getResources().getBoolean(R.bool.use_share_location_plugin));
		return configured && isLocationPluginInstalled(context);
	}

	public static Intent getFetchIntent(Context context) {
		if (isLocationPluginInstalledAndDesired(context)) {
			return new Intent(SHARE_LOCATION_PACKAGE_NAME);
		} else {
			return new Intent(context, ShareLocationActivity.class);
		}
	}

	private static GeoPoint parseGeoPoint(String body) throws IllegalArgumentException {
		Matcher matcher = GEO_URI.matcher(body);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid geo uri");
		}
		double latitude;
		double longitude;
		try {
			latitude = Double.parseDouble(matcher.group(1));
			if (latitude > 90.0 || latitude < -90.0) {
				throw new IllegalArgumentException("Invalid geo uri");
			}
			longitude = Double.parseDouble(matcher.group(2));
			if (longitude > 180.0 || longitude < -180.0) {
				throw new IllegalArgumentException("Invalid geo uri");
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid geo uri",e);
		}
		return new GeoPoint(latitude, longitude);
	}

	public static ArrayList<Intent> createGeoIntentsFromMessage(Context context, Message message) {
		final ArrayList<Intent> intents = new ArrayList<>();
		final GeoPoint geoPoint;
		try {
			geoPoint = parseGeoPoint(message.getBody());
		} catch (IllegalArgumentException e) {
			return intents;
		}
		final Conversational conversation = message.getConversation();
		final String label = getLabel(context, message);

		if (isLocationPluginInstalledAndDesired(context)) {
			Intent locationPluginIntent = new Intent(SHOW_LOCATION_PACKAGE_NAME);
			locationPluginIntent.putExtra("latitude", geoPoint.getLatitude());
			locationPluginIntent.putExtra("longitude", geoPoint.getLongitude());
			if (message.getStatus() != Message.STATUS_RECEIVED) {
				locationPluginIntent.putExtra("jid", conversation.getAccount().getJid().toString());
				locationPluginIntent.putExtra("name", conversation.getAccount().getJid().getLocal());
			} else {
				Contact contact = message.getContact();
				if (contact != null) {
					locationPluginIntent.putExtra("name", contact.getDisplayName());
					locationPluginIntent.putExtra("jid", contact.getJid().toString());
				} else {
					locationPluginIntent.putExtra("name", UIHelper.getDisplayedMucCounterpart(message.getCounterpart()));
				}
			}
			intents.add(locationPluginIntent);
		} else {
			Intent intent = new Intent(context, ShowLocationActivity.class);
			intent.setAction(SHOW_LOCATION_PACKAGE_NAME);
			intent.putExtra("latitude", geoPoint.getLatitude());
			intent.putExtra("longitude", geoPoint.getLongitude());
			intents.add(intent);
		}

		intents.add(geoIntent(geoPoint, label));

		Intent httpIntent = new Intent(Intent.ACTION_VIEW);
		httpIntent.setData(Uri.parse("https://maps.google.com/maps?q=loc:"+ geoPoint.getLatitude() + "," + geoPoint.getLongitude() +label));
		intents.add(httpIntent);
		return intents;
	}

	public static void view(Context context, Message message) {
		final GeoPoint geoPoint = parseGeoPoint(message.getBody());
		final String label = getLabel(context, message);
		context.startActivity(geoIntent(geoPoint,label));
	}

	private static Intent geoIntent(GeoPoint geoPoint, String label) {
		Intent geoIntent = new Intent(Intent.ACTION_VIEW);
		geoIntent.setData(Uri.parse("geo:" + geoPoint.getLatitude() + "," + geoPoint.getLongitude() + "?q=" + geoPoint.getLatitude() + "," + geoPoint.getLongitude() + "("+ label+")"));
		return geoIntent;
	}

	public static boolean openInOsmAnd(Context context, Message message) {
		try {
			final GeoPoint geoPoint = parseGeoPoint(message.getBody());
			final String label = getLabel(context, message);
			return geoIntent(geoPoint, label).resolveActivity(context.getPackageManager()) != null;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String getLabel(Context context, Message message) {
		if(message.getStatus() == Message.STATUS_RECEIVED) {
			try {
				return URLEncoder.encode(UIHelper.getMessageDisplayName(message),"UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new AssertionError(e);
			}
		} else {
			return context.getString(R.string.me);
		}
	}
}
