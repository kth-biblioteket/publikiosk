package se.kth.lib.publikiosk;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;

/** Svenska och engelska i samma textruta: svenskan i textens egen storlek, engelskan mindre och ljusare på en ny rad. */
final class Bilingual {

    private Bilingual() {
    }

    /** sv, ny rad, en. enPx: engelskans storlek i pixlar, enColor: dess färg, enFace: dess typsnitt (null = samma). */
    static CharSequence two(String sv, String en, int enPx, int enColor, Typeface enFace) {
        if (en == null || en.isEmpty()) return sv;
        SpannableStringBuilder b = new SpannableStringBuilder(sv);
        b.append('\n');
        int start = b.length();
        b.append(en);
        b.setSpan(new AbsoluteSizeSpan(enPx), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(enColor), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (enFace != null) b.setSpan(new FaceSpan(enFace), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** Bredden som den bredaste raden kräver, med svenskan i svPaint och engelskan i enPaint */
    static float width(String sv, String en, Paint svPaint, Paint enPaint) {
        float w = svPaint.measureText(sv);
        if (en != null && !en.isEmpty()) w = Math.max(w, enPaint.measureText(en));
        return w;
    }

    static final int LIGHT_ON_WHITE = Color.parseColor("#6B7280");
    static final int LIGHT_ON_BLUE = Color.argb(199, 255, 255, 255);

    private static final class FaceSpan extends MetricAffectingSpan {
        private final Typeface face;

        FaceSpan(Typeface face) {
            this.face = face;
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setTypeface(face);
        }

        @Override
        public void updateMeasureState(TextPaint p) {
            p.setTypeface(face);
        }
    }
}
