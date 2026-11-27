package com.dream.ambientrecorder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class RecorderStyle {
    static final int BACKGROUND = 0xff101512;
    static final int PANEL = 0xff1b251d;
    static final int LINE = 0xff35443a;
    static final int TEXT = 0xffedf2e7;
    static final int MUTED = 0xffaebbad;
    static final int ACCENT = 0xffcaf28a;
    static final int ON_ACCENT = 0xff1a2813;
    static final int ERROR = 0xffffb5a6;

    private final Context context;

    RecorderStyle(Context context) {
        this.context = context;
    }

    int dp(float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    TextView text(float size, int color, boolean strong) {
        TextView text = new TextView(context);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return text;
    }

    Button button(String label, int fill, int ink) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(ink);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setStateListAnimator(null);
        button.setBackground(tappable(fill, 16));
        return button;
    }

    GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) {
            shape.setStroke(dp(1), stroke);
        }
        return shape;
    }

    RippleDrawable tappable(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33a4ba9e), rounded(fill, radius, 0),
                new ColorDrawable(0xffffffff));
    }

    static String timestamp(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time));
    }

    static String clock(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
