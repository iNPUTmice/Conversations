/*
 * Copyright 2012-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;

public class DialpadView extends ConstraintLayout implements View.OnClickListener {

    public DialpadView(Context context) {
        super(context);
        init();
    }

    public DialpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DialpadView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        inflate(getContext(), R.layout.dialpad, this);
        initViews();
    }

    private void initViews() {
        findViewById(R.id.dialpad_1_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_2_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_3_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_4_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_5_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_6_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_7_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_8_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_9_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_0_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_asterisk_holder).setOnClickListener(this);
        findViewById(R.id.dialpad_pound_holder).setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        /* TODO: this widget doesn't know anything about the RTP Connection,
            so how to make this widget generic but also able to send touch-tone sounds
         */
        System.out.println("v.getTag() = " + v.getTag());
    }

}
