package eu.siacs.conversations.utils;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;

import java.util.List;

public class AsciiArmor {

    public static byte[] decode(final String input) {
        final List<String> lines = Splitter.on('\n').splitToList(Strings.nullToEmpty(input).trim());
        if (lines.size() == 1) {
            final String line = lines.get(0);
            if (line.length() > 1) {
                final int end = line.lastIndexOf('=');
                if (end >= 1) {
                    final String cleaned = line.substring(0, end);
                    return BaseEncoding.base64().decode(cleaned);
                }
            }
        }
        final String withoutChecksum;
        if (Iterables.getLast(lines).charAt(0) == '=') {
            withoutChecksum = Joiner.on("").join(lines.subList(0, lines.size() - 1));
        } else {
            withoutChecksum = Joiner.on("").join(lines);
        }
        return BaseEncoding.base64().decode(withoutChecksum);
    }

}
