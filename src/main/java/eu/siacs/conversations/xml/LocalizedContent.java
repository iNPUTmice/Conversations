package eu.siacs.conversations.xml;

import com.google.common.collect.Iterables;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocalizedContent {

    public static final String STREAM_LANGUAGE = "en";

    public final String content;
    public final String language;
    public final int count;

    private LocalizedContent(String content, String language, int count) {
        this.content = content;
        this.language = language;
        this.count = count;
    }

    public static LocalizedContent get(final Element element, String name) {
        final HashMap<String, String> contents = new HashMap<>();
        final String parentLanguage = element.getAttribute("xml:lang");
        for(Element child : element.children) {
            if (name.equals(child.getName())) {
                final String namespace = child.getNamespace();
                final String childLanguage = child.getAttribute("xml:lang");
                final String lang = childLanguage == null ? parentLanguage : childLanguage;
                final String content = child.getContent();
                if (namespace == null || "jabber:client".equals(namespace)) {
                    if (contents.put(lang, content == null ? "" : content) != null) {
                        //anything that has multiple contents for the same language is invalid
                        return null;
                    }
                }
            }
        }
        if (contents.size() == 0) {
            return null;
        }
        final String userLanguage = Locale.getDefault().getLanguage();
        final String localized = contents.get(userLanguage);
        if (localized != null) {
            return new LocalizedContent(localized, userLanguage, contents.size());
        }
        final String defaultLanguageContent = contents.get(null);
        if (defaultLanguageContent != null) {
            return new LocalizedContent(defaultLanguageContent, STREAM_LANGUAGE, contents.size());
        }
        final String streamLanguageContent = contents.get(STREAM_LANGUAGE);
        if (streamLanguageContent != null) {
            return new LocalizedContent(streamLanguageContent, STREAM_LANGUAGE, contents.size());
        }
        final Map.Entry<String, String> first = Iterables.get(contents.entrySet(), 0);
        return new LocalizedContent(first.getValue(), first.getKey(), contents.size());
    }
}
