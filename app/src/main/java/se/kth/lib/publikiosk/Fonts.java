package se.kth.lib.publikiosk;

import android.content.Context;
import android.graphics.Typeface;

import androidx.core.content.res.ResourcesCompat;

/**
 * Figtree, KTH:s profiltypsnitt, som följer med i appen (kioskerna har ingen Play-tjänst som kan
 * ladda ner typsnitt). Licens: SIL Open Font License, se docs/licenses/Figtree-OFL.txt.
 */
final class Fonts {

    private Fonts() {
    }

    static Typeface regular(Context c) {
        return load(c, R.font.figtree_regular, Typeface.NORMAL);
    }

    static Typeface bold(Context c) {
        return load(c, R.font.figtree_bold, Typeface.BOLD);
    }

    static Typeface extraBold(Context c) {
        return load(c, R.font.figtree_extrabold, Typeface.BOLD);
    }

    private static Typeface load(Context c, int res, int fallbackStyle) {
        Typeface t = ResourcesCompat.getFont(c, res);
        return t != null ? t : Typeface.defaultFromStyle(fallbackStyle);
    }
}
