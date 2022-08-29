package eu.siacs.conversations.ui.util;

import android.util.Rational;

public final class Rationals {

    //between 2.39:1 and 1:2.39 (inclusive).
    private static final Rational MIN = new Rational(100,239);
    private static final Rational MAX = new Rational(239,100);

    private Rationals() {

    }


    public static Rational clip(final Rational input) {
        if (input.compareTo(MIN) < 0) {
            return MIN;
        }
        if (input.compareTo(MAX) > 0) {
            return MAX;
        }
        return input;
    }

}
