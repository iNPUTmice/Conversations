/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.PluralsRes;

import java.util.Locale;

import eu.siacs.conversations.R;

public class TimeFrameUtils {

    private static final TimeFrame[] TIME_FRAMES;

    static {
        TIME_FRAMES = new TimeFrame[]{
                new TimeFrame(1000L, R.plurals.seconds),
                new TimeFrame(60L * 1000, R.plurals.minutes),
                new TimeFrame(60L * 60 * 1000, R.plurals.hours),
                new TimeFrame(24L * 60 * 60 * 1000, R.plurals.days),
                new TimeFrame(7L * 24 * 60 * 60 * 1000, R.plurals.weeks),
                new TimeFrame(30L * 24 * 60 * 60 * 1000, R.plurals.months),
        };
    }

    public static String resolve(Context context, long timeFrame) {
        for (int i = TIME_FRAMES.length - 1; i >= 0; --i) {
            long duration = TIME_FRAMES[i].duration;
            long threshold = i > 0 ? (TIME_FRAMES[i - 1].duration / 2) : 0;
            if (timeFrame >= duration - threshold) {
                int count = (int) (timeFrame / duration + ((timeFrame % duration) > (duration / 2) ? 1 : 0));
                return context.getResources().getQuantityString(TIME_FRAMES[i].name, count, count);
            }
        }
        return context.getResources().getQuantityString(TIME_FRAMES[0].name, 0, 0);
    }

    public static String formatTimePassed(final long since, final boolean withMilliseconds) {
        return formatTimePassed(since, SystemClock.elapsedRealtime(), withMilliseconds);
    }

    public static String formatTimePassed(final long since, final long to, final boolean withMilliseconds) {
        final long passed = (since < 0) ? 0 : (to - since);
        return formatElapsedTime(passed, withMilliseconds);
    }

    public static String formatElapsedTime(final long elapsed, final boolean withMilliseconds) {
        final int hours = (int) (elapsed / 3600000);
        final int minutes = (int) (elapsed / 60000) % 60;
        final int seconds = (int) (elapsed / 1000) % 60;
        final int milliseconds = (int) (elapsed / 100) % 10;
        if (hours > 0) {
            return String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds);
        } else if (withMilliseconds) {
            return String.format(Locale.ENGLISH, "%d:%02d.%d", minutes, seconds, milliseconds);
        } else {
            return String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds);
        }
    }


    private static class TimeFrame {
        final long duration;
        public final int name;

        private TimeFrame(long duration, @PluralsRes int name) {
            this.duration = duration;
            this.name = name;
        }
    }

}