package se.kth.lib.publikiosk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alla Lucide-ikoner (lucide-react 1.48.0, ISC; samma version som publicomtools och Linux-kiosken), ritade ur
 * assets/lucide-icons.txt som genereras av tools/gen-lucide.mjs. Ikonen väljs med sitt Lucide-namn
 * (lucide.dev/icons), inklusive äldre alias som circle-help. Ett okänt namn ger ingen ikon (null).
 */
final class LucideIcons {

    private static final String TAG = "LucideIcons";
    private static final String ASSET = "lucide-icons.txt";

    /** namn → json med ikonens noder, eller @annatnamn (alias) */
    private static Map<String, String> index;
    private static final Map<String, List<Shape>> parsed = new HashMap<>();

    private LucideIcons() {
    }

    /** Ikonen med det namnet, eller null om den inte finns */
    static synchronized Drawable get(Context context, String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        List<Shape> shapes = parsed.get(key);
        if (shapes == null) {
            shapes = parse(context, key);
            if (shapes == null) return null;
            parsed.put(key, shapes);
        }
        return new LucideDrawable(shapes);
    }

    private static List<Shape> parse(Context context, String key) {
        Map<String, String> idx = index(context);
        String value = idx.get(key);
        if (value != null && value.startsWith("@")) value = idx.get(value.substring(1));
        if (value == null) return null;
        try {
            JSONArray nodes = new JSONArray(value);
            List<Shape> shapes = new ArrayList<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONArray node = nodes.getJSONArray(i);
                Shape s = Shape.of(node.getString(0), node.getJSONObject(1));
                if (s != null) shapes.add(s);
            }
            return shapes;
        } catch (JSONException | RuntimeException e) {
            // En trasig ikon får aldrig ta ner appen: ingen ikon i stället
            Log.w(TAG, "Ikonen " + key + " går inte att läsa", e);
            return null;
        }
    }

    private static Map<String, String> index(Context context) {
        if (index != null) return index;
        Map<String, String> map = new HashMap<>(4096);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getApplicationContext().getAssets().open(ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab > 0) map.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException e) {
            Log.w(TAG, "Ikonfilen kunde inte läsas", e);
        }
        index = map;
        return map;
    }

    /** En del av en ikon i Lucides 24 × 24-rutnät */
    private interface Shape {
        void draw(Canvas canvas, Paint stroke, Paint fill);

        static Shape of(String tag, JSONObject a) {
            boolean filled = "currentColor".equals(a.optString("fill", "none"));
            switch (tag) {
                case "path": {
                    Path p = SvgPath.parse(a.optString("d", ""));
                    return p == null ? null : (c, s, f) -> draw(c, p, s, f, filled);
                }
                case "circle": {
                    float cx = (float) a.optDouble("cx"), cy = (float) a.optDouble("cy"), r = (float) a.optDouble("r");
                    return (c, s, f) -> {
                        if (filled) c.drawCircle(cx, cy, r, f);
                        c.drawCircle(cx, cy, r, s);
                    };
                }
                case "ellipse": {
                    RectF o = new RectF((float) (a.optDouble("cx") - a.optDouble("rx")), (float) (a.optDouble("cy") - a.optDouble("ry")),
                            (float) (a.optDouble("cx") + a.optDouble("rx")), (float) (a.optDouble("cy") + a.optDouble("ry")));
                    return (c, s, f) -> {
                        if (filled) c.drawOval(o, f);
                        c.drawOval(o, s);
                    };
                }
                case "rect": {
                    float x = (float) a.optDouble("x", 0), y = (float) a.optDouble("y", 0);
                    RectF r = new RectF(x, y, x + (float) a.optDouble("width"), y + (float) a.optDouble("height"));
                    float rx = (float) a.optDouble("rx", a.optDouble("ry", 0)), ry = (float) a.optDouble("ry", rx);
                    return (c, s, f) -> {
                        if (filled) c.drawRoundRect(r, rx, ry, f);
                        c.drawRoundRect(r, rx, ry, s);
                    };
                }
                case "line": {
                    float x1 = (float) a.optDouble("x1"), y1 = (float) a.optDouble("y1"), x2 = (float) a.optDouble("x2"), y2 = (float) a.optDouble("y2");
                    return (c, s, f) -> c.drawLine(x1, y1, x2, y2, s);
                }
                case "polyline":
                case "polygon": {
                    String[] n = a.optString("points", "").trim().split("[\\s,]+");
                    if (n.length < 4) return null;
                    Path p = new Path();
                    for (int i = 0; i + 1 < n.length; i += 2) {
                        float x = Float.parseFloat(n[i]), y = Float.parseFloat(n[i + 1]);
                        if (i == 0) p.moveTo(x, y);
                        else p.lineTo(x, y);
                    }
                    if ("polygon".equals(tag)) p.close();
                    return (c, s, f) -> draw(c, p, s, f, filled);
                }
                default:
                    return null;
            }
        }

        static void draw(Canvas c, Path p, Paint s, Paint f, boolean filled) {
            if (filled) c.drawPath(p, f);
            c.drawPath(p, s);
        }
    }

    /**
     * Läser SVG:s path-data (alla kommandon M L H V C S Q T A Z, absoluta och relativa) som Lucide skriver den,
     * kompakt: tal utan mellanslag (".25.25"), och bågarnas flaggor som enstaka tecken ("00.407").
     * Android egna läsare klarar inte flaggorna.
     */
    private static final class SvgPath {
        private final String s;
        private int i = 0;

        private SvgPath(String s) {
            this.s = s;
        }

        static Path parse(String d) {
            return new SvgPath(d).run();
        }

        private void skipSeparators() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\t' || s.charAt(i) == '\r')) i++;
        }

        private boolean hasNumber() {
            skipSeparators();
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
        }

        private float number() {
            skipSeparators();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            return Float.parseFloat(s.substring(start, i));
        }

        private boolean flag() {
            skipSeparators();
            return s.charAt(i++) == '1';
        }

        private Path run() {
            Path p = new Path();
            char cmd = 0, prev = 0;
            float cx = 0, cy = 0, sx = 0, sy = 0, lx = 0, ly = 0;
            while (true) {
                skipSeparators();
                if (i >= s.length()) break;
                char c = s.charAt(i);
                if (Character.isLetter(c)) {
                    cmd = c;
                    i++;
                    if (cmd == 'Z' || cmd == 'z') {
                        p.close();
                        cx = sx;
                        cy = sy;
                        prev = 'z';
                        continue;
                    }
                } else if (!hasNumber() || cmd == 0) {
                    break;
                }
                boolean rel = Character.isLowerCase(cmd);
                float ox = rel ? cx : 0, oy = rel ? cy : 0;
                switch (Character.toUpperCase(cmd)) {
                    case 'M': {
                        float x = number() + ox, y = number() + oy;
                        p.moveTo(x, y);
                        cx = sx = x;
                        cy = sy = y;
                        cmd = rel ? 'l' : 'L';
                        break;
                    }
                    case 'L': {
                        float x = number() + ox, y = number() + oy;
                        p.lineTo(x, y);
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'H': {
                        float x = number() + ox;
                        p.lineTo(x, cy);
                        cx = x;
                        break;
                    }
                    case 'V': {
                        float y = number() + oy;
                        p.lineTo(cx, y);
                        cy = y;
                        break;
                    }
                    case 'C': {
                        float x1 = number() + ox, y1 = number() + oy, x2 = number() + ox, y2 = number() + oy, x = number() + ox, y = number() + oy;
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        lx = x2;
                        ly = y2;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'S': {
                        float x1 = prev == 'C' || prev == 'S' ? 2 * cx - lx : cx, y1 = prev == 'C' || prev == 'S' ? 2 * cy - ly : cy;
                        float x2 = number() + ox, y2 = number() + oy, x = number() + ox, y = number() + oy;
                        p.cubicTo(x1, y1, x2, y2, x, y);
                        lx = x2;
                        ly = y2;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'Q': {
                        float x1 = number() + ox, y1 = number() + oy, x = number() + ox, y = number() + oy;
                        p.quadTo(x1, y1, x, y);
                        lx = x1;
                        ly = y1;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'T': {
                        float x1 = prev == 'Q' || prev == 'T' ? 2 * cx - lx : cx, y1 = prev == 'Q' || prev == 'T' ? 2 * cy - ly : cy;
                        float x = number() + ox, y = number() + oy;
                        p.quadTo(x1, y1, x, y);
                        lx = x1;
                        ly = y1;
                        cx = x;
                        cy = y;
                        break;
                    }
                    case 'A': {
                        float rx = number(), ry = number(), rot = number();
                        boolean large = flag(), sweep = flag();
                        float x = number() + ox, y = number() + oy;
                        arc(p, cx, cy, rx, ry, rot, large, sweep, x, y);
                        cx = x;
                        cy = y;
                        break;
                    }
                    default:
                        return p;
                }
                prev = Character.toUpperCase(cmd == 'l' && prev == 'M' ? 'L' : cmd);
                if (Character.toUpperCase(cmd) == 'L' && prev != 'L') prev = 'L';
            }
            return p;
        }

        /** Bågen från (x0,y0) till (x,y) som kubiska kurvor (SVG-specifikationens F.6.5) */
        private static void arc(Path p, float x0, float y0, float rx, float ry, float rotation, boolean large, boolean sweep, float x, float y) {
            if (x0 == x && y0 == y) return;
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            if (rx == 0 || ry == 0) {
                p.lineTo(x, y);
                return;
            }
            double phi = Math.toRadians(rotation), cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
            double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
            double x1p = cosPhi * dx2 + sinPhi * dy2, y1p = -sinPhi * dx2 + cosPhi * dy2;
            double lambda = (x1p * x1p) / ((double) rx * rx) + (y1p * y1p) / ((double) ry * ry);
            if (lambda > 1) {
                double sq = Math.sqrt(lambda);
                rx *= sq;
                ry *= sq;
            }
            double rx2 = (double) rx * rx, ry2 = (double) ry * ry;
            double num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
            double den = rx2 * y1p * y1p + ry2 * x1p * x1p;
            double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
            double cxp = coef * (rx * y1p / ry), cyp = coef * (-ry * x1p / rx);
            double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0, cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0;
            double ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry, vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry;
            double theta1 = Math.atan2(uy, ux);
            double dtheta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
            if (!sweep && dtheta > 0) dtheta -= 2 * Math.PI;
            else if (sweep && dtheta < 0) dtheta += 2 * Math.PI;
            int segments = (int) Math.ceil(Math.abs(dtheta) / (Math.PI / 2) - 1e-9);
            double delta = dtheta / segments, t = 4.0 / 3.0 * Math.tan(delta / 4);
            for (int k = 0; k < segments; k++) {
                double a1 = theta1 + k * delta, a2 = a1 + delta;
                double c1 = Math.cos(a1), s1 = Math.sin(a1), c2 = Math.cos(a2), s2 = Math.sin(a2);
                double[] q = {c1 - t * s1, s1 + t * c1, c2 + t * s2, s2 - t * c2, c2, s2};
                float[] o = new float[6];
                for (int m = 0; m < 3; m++) {
                    o[2 * m] = (float) (cx + rx * q[2 * m] * cosPhi - ry * q[2 * m + 1] * sinPhi);
                    o[2 * m + 1] = (float) (cy + rx * q[2 * m] * sinPhi + ry * q[2 * m + 1] * cosPhi);
                }
                p.cubicTo(o[0], o[1], o[2], o[3], o[4], o[5]);
            }
        }
    }

    /** Ritar ikonen som Lucide: streck 2, runda ändar, ingen fyllning, i tintens färg (standard svart) */
    private static final class LucideDrawable extends Drawable {
        private final List<Shape> shapes;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        LucideDrawable(List<Shape> shapes) {
            this.shapes = shapes;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            setColor(0xFF000000);
        }

        private void setColor(int color) {
            stroke.setColor(color);
            fill.setColor(color);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            int save = canvas.save();
            canvas.translate(b.left, b.top);
            float s = Math.min(b.width(), b.height()) / 24f;
            canvas.scale(s, s);
            for (Shape shape : shapes) shape.draw(canvas, stroke, fill);
            canvas.restoreToCount(save);
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            if (tint != null) setColor(tint.getColorForState(getState(), tint.getDefaultColor()));
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            stroke.setColorFilter(filter);
            fill.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
