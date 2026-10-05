package se.kth.lib.publikiosk;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

/**
 * WebView som ber tangentbordet att inte lära sig av det som skrivs (ordförslag, historik).
 * På en publik enhet ska en besökares text inte dyka upp som förslag för nästa.
 */
public class KioskWebView extends WebView {

    public KioskWebView(Context context) {
        super(context);
    }

    public KioskWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public KioskWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        // Vanliga textfält beskrivs som "synligt lösenord": då visar tangentbordet (Gboard och andra)
        // inga förslag och ingen verktygsrad med inställningar, GIF, översättning, teman och mikrofon.
        // E-post-, nummer- och telefonfält behåller sina egna tangentbord.
        if ((outAttrs.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            int variation = outAttrs.inputType & InputType.TYPE_MASK_VARIATION;
            if (variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                    && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            }
        }
        return connection;
    }
}
