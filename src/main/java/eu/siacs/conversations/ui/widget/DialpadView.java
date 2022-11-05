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
import android.view.LayoutInflater;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.databinding.DataBindingUtil;
import eu.siacs.conversations.databinding.DialpadBinding;
import eu.siacs.conversations.R;

public class DialpadView extends ConstraintLayout implements View.OnClickListener {

    protected Consumer<String> clickConsumer = null;

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

    public void setClickConsumer(Consumer<String> clickConsumer) {
        this.clickConsumer = clickConsumer;
    }

    private void init() {
        DialpadBinding binding = DataBindingUtil.inflate(
            LayoutInflater.from(getContext()),
            R.layout.dialpad,
            this,
            true
        );
        binding.setDialpadView(this);
    }

    @Override
    public void onClick(View v) {
        clickConsumer.accept(v.getTag().toString());
    }

	// Based on java.util.function.Consumer to avoid Android 24 dependency
	public interface Consumer<T> {
		void accept(T t);
	}
}
