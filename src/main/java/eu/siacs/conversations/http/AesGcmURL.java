package eu.siacs.conversations.http;

import java.util.regex.Pattern;

import okhttp3.HttpUrl;

public final class AesGcmURL {

    /**
     * This matches a 48 or 44 byte IV + KEY hex combo, like used in http/aesgcm upload anchors
     */
    public static final Pattern IV_KEY = Pattern.compile("([A-Fa-f0-9]{2}){48}|([A-Fa-f0-9]{2}){44}");

    public static final String PROTOCOL_NAME = "aesgcm";

    private AesGcmURL() {

    }

    public static String toAesGcmUrl(HttpUrl url) {
        if (url.isHttps()) {
            return PROTOCOL_NAME + url.toString().substring(5);
        } else {
            return url.toString();
        }
    }

    public static HttpUrl of(final String url) {
        final int end = url.indexOf("://");
        if (end < 0) {
            throw new IllegalArgumentException("Scheme not found");
        }
        final String protocol = url.substring(0, end);
        if (PROTOCOL_NAME.equals(protocol)) {
            return HttpUrl.get("https" + url.substring(PROTOCOL_NAME.length()));
        } else {
            return HttpUrl.get(url);
        }
    }

}
