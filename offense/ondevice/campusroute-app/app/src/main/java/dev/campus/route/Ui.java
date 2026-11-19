package dev.campus.route;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

final class Ui {
    static final int BASE = Color.rgb(17, 24, 22);
    static final int PANEL = Color.rgb(26, 36, 32);
    static final int LINE = Color.rgb(48, 63, 56);
    static final int INK = Color.rgb(239, 245, 233);
    static final int MUTED = Color.rgb(172, 192, 181);
    static final int ACCENT = Color.rgb(216, 250, 128);
    static final int END = Color.rgb(247, 178, 138);
    static final int DARK_INK = Color.rgb(29, 45, 19);

    static int dp(Context context, float distance) {
        return Math.round(distance * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(Context context, int fill, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radius));
        if (stroke != 0) {
            drawable.setStroke(dp(context, 1), stroke);
        }
        return drawable;
    }

    static void styleButton(TextView button, int fill, int ink, int outline, float radius) {
        Context context = button.getContext();
        GradientDrawable background = background(context, fill, radius, outline);
        button.setTextColor(ink);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), background, null));
    }

    static TextView text(Context context, CharSequence content, float size, int color) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(context, 3), 1);
        return view;
    }

    static void medium(TextView view) {
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static String duration(double seconds) {
        long rounded = Math.round(Math.max(0, seconds));
        long hours = rounded / 3_600;
        long minutes = rounded / 60 % 60;
        long remainder = rounded % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    static String distance(double meters) {
        if (meters < 1_000) {
            return String.format(Locale.ROOT, "%d m", Math.round(meters));
        }
        return String.format(Locale.ROOT, "%.2f km", meters / 1_000);
    }

    private Ui() {}
}
