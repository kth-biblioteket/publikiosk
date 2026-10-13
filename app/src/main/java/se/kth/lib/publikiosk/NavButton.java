package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Knapp i kiosknavigeringen med två textrader: svenska stort och engelska mindre under. Varje rad
 * kortas för sig med … när den inte ryms. Ikonen ligger till vänster (liggande skärm) eller ovanför
 * texten (stående, där knapparna är smalare). Måtten ges i skissens enheter (u = pixlar per skisspixel).
 */
public class NavButton extends LinearLayout {

    private static final int EN_MUTED = 0xFF6B7280;

    private final ImageView icon;
    private final LinearLayout labels;
    private final TextView sv;
    private final TextView en;
    private boolean on = false;
    private boolean stacked = false;
    private float u = 1f;

    public NavButton(Context context) {
        this(context, null);
    }

    public NavButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        setGravity(Gravity.CENTER);
        icon = new ImageView(context);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        sv = text(context, Fonts.bold(context));
        en = text(context, Fonts.regular(context));
        labels.addView(sv, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        labels.addView(en, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(icon);
        addView(labels);
        restyle();
    }

    private static TextView text(Context c, android.graphics.Typeface face) {
        TextView t = new TextView(c);
        t.setTypeface(face);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    public void setIcon(int res) {
        if (res != 0) icon.setImageResource(res);
        icon.setVisibility(res != 0 ? VISIBLE : GONE);
    }

    /** Ikon ur en Lucide-ritning (null: ingen ikon) */
    public void setIcon(android.graphics.drawable.Drawable drawable) {
        icon.setImageDrawable(drawable);
        icon.setVisibility(drawable != null ? VISIBLE : GONE);
    }

    /** Svenska och engelska; engelskan utelämnas när den saknas eller är densamma */
    public void setLabels(String svText, String enText) {
        sv.setText(svText);
        boolean two = enText != null && !enText.trim().isEmpty() && !enText.trim().equalsIgnoreCase(svText.trim());
        en.setText(two ? enText.trim() : "");
        en.setVisibility(two ? VISIBLE : GONE);
        setContentDescription(two ? svText + " / " + enText.trim() : svText);
    }

    /** Vald app: blå fyllning med vit text */
    public void setActive(boolean active) {
        on = active;
        restyle();
    }

    /** stacked: ikonen ovanför texten. u: pixlar per skisspixel. */
    public void setStyle(boolean stackedIcon, float unit) {
        stacked = stackedIcon;
        u = unit;
        restyle();
    }

    private int px(float design) {
        return Math.round(design * u);
    }

    private void restyle() {
        setOrientation(stacked ? VERTICAL : HORIZONTAL);
        int svSize = stacked ? 15 : 20, enSize = stacked ? 11 : 14;
        sv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(svSize));
        en.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(enSize));
        sv.setGravity(stacked ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        en.setGravity(stacked ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        ((LayoutParams) en.getLayoutParams()).topMargin = px(2);
        labels.setGravity(stacked ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        LayoutParams ilp = new LayoutParams(px(26), px(26));
        if (stacked) ilp.bottomMargin = px(4);
        else ilp.setMarginEnd(px(10));
        icon.setLayoutParams(ilp);
        // I stående format delar texten bredden med de andra knapparna: den får krympa (och kortas), ikonen inte
        LayoutParams llp = new LayoutParams(stacked ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labels.setLayoutParams(llp);
        int padH = px(stacked ? 4 : 20);
        setPadding(padH, 0, padH, 0);

        int fg = on ? Color.WHITE : getContext().getColor(R.color.nav_text);
        sv.setTextColor(fg);
        en.setTextColor(on ? Color.argb(199, 255, 255, 255) : EN_MUTED);
        icon.setImageTintList(ColorStateList.valueOf(fg));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? getContext().getColor(R.color.kth_blue) : Color.WHITE);
        bg.setCornerRadius(px(14));
        bg.setStroke(Math.max(1, px(2)), on ? getContext().getColor(R.color.kth_blue) : getContext().getColor(R.color.nav_border));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(px(14));
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), bg, mask));
    }

    /** Bredden som texten behöver (längsta raden) plus ikon och marginaler, för knappar som inte delar rad */
    public int naturalWidth() {
        float w = Math.max(sv.getPaint().measureText(String.valueOf(sv.getText())),
                en.getVisibility() == VISIBLE ? en.getPaint().measureText(String.valueOf(en.getText())) : 0f);
        int iconW = stacked || icon.getVisibility() == GONE ? 0 : px(26) + px(10);
        return Math.round(w) + iconW + 2 * (stacked ? px(4) : px(20)) + px(4);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    public View labelsView() {
        return labels;
    }
}
