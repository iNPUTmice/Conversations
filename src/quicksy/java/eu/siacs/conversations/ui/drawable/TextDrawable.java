package eu.siacs.conversations.ui.drawable; /**
 * Copyright 2016 Ali Muzaffar
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import eu.siacs.conversations.ui.util.StyledAttributes;

public class TextDrawable extends Drawable implements TextWatcher {
    private WeakReference<TextView> ref;
    private String mText;
    private Paint mPaint;
    private Rect mHeightBounds;
    private boolean mBindToViewPaint = false;
    private float mPrevTextSize = 0;
    private boolean mInitFitText = false;
    private boolean mFitTextEnabled = false;

    /**
     * Create a TextDrawable using the given paint object and string
     *
     * @param paint
     * @param s
     */
    public TextDrawable(Paint paint, String s) {
        mText = s;
        mPaint = new Paint(paint);
        mHeightBounds = new Rect();
        init();
    }

    /**
     * Create a TextDrawable. This uses the given TextView to initialize paint and has initial text
     * that will be drawn. Initial text can also be useful for reserving space that may otherwise
     * not be available when setting compound drawables.
     *
     * @param tv               The TextView / EditText using to initialize this drawable
     * @param initialText      Optional initial text to display
     * @param bindToViewsText  Should this drawable mirror the text in the TextView
     * @param bindToViewsPaint Should this drawable mirror changes to Paint in the TextView, like textColor, typeface, alpha etc.
     *                         Note, this will override any changes made using setColorFilter or setAlpha.
     */
    public TextDrawable(TextView tv, String initialText, boolean bindToViewsText, boolean bindToViewsPaint) {
        this(tv.getPaint(), initialText);
        mPaint.setColor(StyledAttributes.getColor(tv.getContext(), android.R.attr.textColorPrimary));
        ref = new WeakReference<>(tv);
        if (bindToViewsText || bindToViewsPaint) {
            if (bindToViewsText) {
                tv.addTextChangedListener(this);
            }
            mBindToViewPaint = bindToViewsPaint;
        }
    }

    /**
     * Create a TextDrawable. This uses the given TextView to initialize paint and the text that
     * will be drawn.
     *
     * @param tv               The TextView / EditText using to initialize this drawable
     * @param bindToViewsText  Should this drawable mirror the text in the TextView
     * @param bindToViewsPaint Should this drawable mirror changes to Paint in the TextView, like textColor, typeface, alpha etc.
     *                         Note, this will override any changes made using setColorFilter or setAlpha.
     */
    public TextDrawable(TextView tv, boolean bindToViewsText, boolean bindToViewsPaint) {
        this(tv, tv.getText().toString(), false, false);
    }

    /**
     * Use the provided TextView/EditText to initialize the drawable.
     * The Drawable will copy the Text and the Paint properties, however it will from that
     * point on be independant of the TextView.
     *
     * @param tv a TextView or EditText or any of their children.
     */
    public TextDrawable(TextView tv) {
        this(tv, false, false);
    }

    /**
     * Use the provided TextView/EditText to initialize the drawable.
     * The Drawable will copy the Paint properties, and use the provided text to initialise itself.
     *
     * @param tv a TextView or EditText or any of their children.
     * @param s  The String to draw
     */
    public TextDrawable(TextView tv, String s) {
        this(tv, s, false, false);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mBindToViewPaint && ref.get() != null) {
            Paint p = ref.get().getPaint();
            canvas.drawText(mText, 0, getBounds().height(), p);
        } else {
            if (mInitFitText) {
                fitTextAndInit();
            }
            canvas.drawText(mText, 0, getBounds().height(), mPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        int alpha = mPaint.getAlpha();
        if (alpha == 0) {
            return PixelFormat.TRANSPARENT;
        } else if (alpha == 255) {
            return PixelFormat.OPAQUE;
        } else {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private void init() {
        Rect bounds = getBounds();
        //We want to use some character to determine the max height of the text.
        //Otherwise if we draw something like "..." they will appear centered
        //Here I'm just going to use the entire alphabet to determine max height.
        mPaint.getTextBounds("1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+", 0, 1, mHeightBounds);
        //This doesn't account for leading or training white spaces.
        //mPaint.getTextBounds(mText, 0, mText.length(), bounds);
        float width = mPaint.measureText(mText);
        bounds.top = mHeightBounds.top;
        bounds.bottom = mHeightBounds.bottom;
        bounds.right = (int) width;
        bounds.left = 0;
        setBounds(bounds);
    }

    public Paint getPaint() {
        return mPaint;
    }

    public void setPaint(Paint paint) {
        mPaint = new Paint(paint);
        //Since this can change the font used, we need to recalculate bounds.
        if (mFitTextEnabled && !mInitFitText) {
            fitTextAndInit();
        } else {
            init();
        }
        invalidateSelf();
    }

    public String getText() {
        return mText;
    }

    public void setText(String text) {
        mText = text;
        //Since this can change the bounds of the text, we need to recalculate.
        if (mFitTextEnabled && !mInitFitText) {
            fitTextAndInit();
        } else {
            init();
        }
        invalidateSelf();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        setText(s.toString());
    }

    /**
     * Make the TextDrawable match the width of the View it's associated with.
     * <p/>
     * Note: While this option will not work if bindToViewPaint is true.
     *
     * @param fitText
     */
    public void setFillText(boolean fitText) {
        mFitTextEnabled = fitText;
        if (fitText) {
            mPrevTextSize = mPaint.getTextSize();
            if (ref.get() != null) {
                if (ref.get().getWidth() > 0) {
                    fitTextAndInit();
                } else {
                    mInitFitText = true;
                }
            }
        } else {
            if (mPrevTextSize > 0) {
                mPaint.setTextSize(mPrevTextSize);
            }
            init();
        }
    }

    private void fitTextAndInit() {
        float fitWidth = ref.get().getWidth();
        float textWidth = mPaint.measureText(mText);
        float multi = fitWidth / textWidth;
        mPaint.setTextSize(mPaint.getTextSize() * multi);
        mInitFitText = false;
        init();
    }

}