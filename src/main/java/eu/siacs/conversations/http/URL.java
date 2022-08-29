package eu.siacs.conversations.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import okhttp3.HttpUrl;

public class URL {

    public static final List<String> WELL_KNOWN_SCHEMES = Arrays.asList("http", "https", AesGcmURL.PROTOCOL_NAME);

    public static String tryParse(String url) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (WELL_KNOWN_SCHEMES.contains(uri.getScheme())) {
            return uri.toString();
        } else {
            return null;
        }
    }

    public static HttpUrl stripFragment(final HttpUrl url) {
        return url.newBuilder().fragment(null).build();
    }

}
