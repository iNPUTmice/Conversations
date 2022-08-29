package eu.siacs.conversations.entities;

import androidx.annotation.DrawableRes;

import com.google.common.base.Strings;

import eu.siacs.conversations.R;

public class RtpSessionStatus {

    public final boolean successful;
    public final long duration;


    public RtpSessionStatus(boolean successful, long duration) {
        this.successful = successful;
        this.duration = duration;
    }

    @Override
    public String toString() {
        return successful + ":" + duration;
    }

    public static RtpSessionStatus of(final String body) {
        final String[] parts = Strings.nullToEmpty(body).split(":", 2);
        long duration = 0;
        if (parts.length == 2) {
            try {
                duration = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                //do nothing
            }
        }
        boolean made;
        try {
            made = Boolean.parseBoolean(parts[0]);
        } catch (Exception e) {
            made = false;
        }
        return new RtpSessionStatus(made, duration);
    }

    public static @DrawableRes int getDrawable(final boolean received, final boolean successful, final boolean darkTheme) {
        if (received) {
            if (successful) {
                return darkTheme ? R.drawable.ic_call_received_white_18dp : R.drawable.ic_call_received_black_18dp;
            } else {
                return darkTheme ? R.drawable.ic_call_missed_white_18dp : R.drawable.ic_call_missed_black_18dp;
            }
        } else {
            if (successful) {
                return darkTheme ? R.drawable.ic_call_made_white_18dp : R.drawable.ic_call_made_black_18dp;
            } else {
                return darkTheme ? R.drawable.ic_call_missed_outgoing_white_18dp : R.drawable.ic_call_missed_outgoing_black_18dp;
            }
        }
    }
}
