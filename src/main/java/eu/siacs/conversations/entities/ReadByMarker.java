package eu.siacs.conversations.entities;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import eu.siacs.conversations.xmpp.Jid;

public class ReadByMarker {

	private ReadByMarker() {

	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ReadByMarker marker = (ReadByMarker) o;

		if (fullJid != null ? !fullJid.equals(marker.fullJid) : marker.fullJid != null)
			return false;
		return realJid != null ? realJid.equals(marker.realJid) : marker.realJid == null;

	}

	@Override
	public int hashCode() {
		int result = fullJid != null ? fullJid.hashCode() : 0;
		result = 31 * result + (realJid != null ? realJid.hashCode() : 0);
		return result;
	}

	private Jid fullJid;
	private Jid realJid;

	public Jid getFullJid() {
		return fullJid;
	}

	public Jid getRealJid() {
		return realJid;
	}

	public JSONObject toJson() {
		JSONObject jsonObject = new JSONObject();
		if (fullJid != null) {
			try {
				jsonObject.put("fullJid", fullJid.toString());
			} catch (JSONException e) {
				//ignore
			}
		}
		if (realJid != null) {
			try {
				jsonObject.put("realJid", realJid.toString());
			} catch (JSONException e) {
				//ignore
			}
		}
		return jsonObject;
	}

	public static Set<ReadByMarker> fromJson(final JSONArray jsonArray) {
		final Set<ReadByMarker> readByMarkers = new CopyOnWriteArraySet<>();
		for(int i = 0; i < jsonArray.length(); ++i) {
			try {
				readByMarkers.add(fromJson(jsonArray.getJSONObject(i)));
			} catch (JSONException e) {
				//ignored
			}
		}
		return readByMarkers;
	}

	public static ReadByMarker from(Jid fullJid, Jid realJid) {
		final ReadByMarker marker = new ReadByMarker();
		marker.fullJid = fullJid;
		marker.realJid = realJid == null ? null : realJid.asBareJid();
		return marker;
	}

	public static ReadByMarker from(Message message) {
		final ReadByMarker marker = new ReadByMarker();
		marker.fullJid = message.getCounterpart();
		marker.realJid = message.getTrueCounterpart();
		return marker;
	}

	public static ReadByMarker from(MucOptions.User user) {
		final ReadByMarker marker = new ReadByMarker();
		marker.fullJid = user.getFullJid();
		marker.realJid = user.getRealJid();
		return marker;
	}

	public static Set<ReadByMarker> from(Collection<MucOptions.User> users) {
		final Set<ReadByMarker> markers = new CopyOnWriteArraySet<>();
		for(MucOptions.User user : users) {
			markers.add(from(user));
		}
		return markers;
	}

	public static ReadByMarker fromJson(JSONObject jsonObject) {
		ReadByMarker marker = new ReadByMarker();
		try {
			marker.fullJid = Jid.of(jsonObject.getString("fullJid"));
		} catch (JSONException | IllegalArgumentException e) {
			marker.fullJid = null;
		}
		try {
			marker.realJid = Jid.of(jsonObject.getString("realJid"));
		} catch (JSONException | IllegalArgumentException e) {
			marker.realJid = null;
		}
		return marker;
	}

	public static Set<ReadByMarker> fromJsonString(String json) {
		try {
			return fromJson(new JSONArray(json));
		} catch (final JSONException | NullPointerException e) {
			return new CopyOnWriteArraySet<>();
		}
	}

	public static JSONArray toJson(final Set<ReadByMarker> readByMarkers) {
		final JSONArray jsonArray = new JSONArray();
		for(final ReadByMarker marker : readByMarkers) {
			jsonArray.put(marker.toJson());
		}
		return jsonArray;
	}

	public static boolean contains(ReadByMarker needle, final Set<ReadByMarker> readByMarkers) {
		for(final ReadByMarker marker : readByMarkers) {
			if (marker.realJid != null && needle.realJid != null) {
				if (marker.realJid.asBareJid().equals(needle.realJid.asBareJid())) {
					return true;
				}
			} else if (marker.fullJid != null && needle.fullJid != null) {
				if (marker.fullJid.equals(needle.fullJid)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean allUsersRepresented(Collection<MucOptions.User> users, Set<ReadByMarker> markers) {
		for(MucOptions.User user : users) {
			if (!contains(from(user),markers)) {
				return false;
			}
		}
		return true;
	}

	public static boolean allUsersRepresented(Collection<MucOptions.User> users, Set<ReadByMarker> markers, ReadByMarker marker) {
		final Set<ReadByMarker> markersCopy = new CopyOnWriteArraySet<>(markers);
		markersCopy.add(marker);
		return allUsersRepresented(users, markersCopy);
	}

}
