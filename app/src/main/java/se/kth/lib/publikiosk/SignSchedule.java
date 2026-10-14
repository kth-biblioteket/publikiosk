package se.kth.lib.publikiosk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SIGN_SCHEDULE: när olika spellistor visas på skylten. JSON:
 * {"lists":{"Dag":[{"url":"https://…","s":30}]},"slots":[{"list":"Dag","days":[1,2,3,4,5],"from":"08:00","to":"19:00"},
 * {"list":"Bokrea","dates":["2026-10-20","2026-10-31"]}],"off":false}. Som Linux-kiosken och admin räknar:
 * days 1-7 = måndag-söndag; from/to "HH:MM" där to inte ingår ("24:00" = midnatt); dates [första, sista] inklusive;
 * ett fält som saknas = alltid; from efter to är ogiltigt (raden hoppas över). Bland raderna som gäller nu vinner den
 * första med dates, annars den första utan. Ingen rad gäller, eller listan är tom: standardlistan
 * (SIGN_PAGES). off och ingen rad gäller: svart skärm. Enhetens lokala tid.
 */
final class SignSchedule {

    /** Resultatet: en lista att visa, svart skärm (null lista och off) eller standardlistan (null lista, inte off) */
    static final class Choice {
        final String key;
        final List<SignageScreen.Page> pages;
        final boolean black;

        Choice(String key, List<SignageScreen.Page> pages, boolean black) {
            this.key = key;
            this.pages = pages;
            this.black = black;
        }
    }

    private static final class Slot {
        String list;
        int[] days;          // null = alla
        int from = 0, to = 1440;
        String dateFrom, dateTo; // null = alltid
    }

    private final Map<String, List<SignageScreen.Page>> lists = new LinkedHashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    private boolean off = false;

    /** Ogiltig eller tom text ger null (då gäller bara SIGN_PAGES) */
    static SignSchedule parse(String raw, int defaultSeconds) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw.trim());
            SignSchedule s = new SignSchedule();
            s.off = o.optBoolean("off", false);
            JSONObject l = o.optJSONObject("lists");
            if (l != null) {
                java.util.Iterator<String> it = l.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONArray a = l.optJSONArray(name);
                    if (a != null) s.lists.put(name, SignageScreen.parse(a.toString(), defaultSeconds, null));
                }
            }
            JSONArray sl = o.optJSONArray("slots");
            if (sl != null) {
                for (int i = 0; i < sl.length(); i++) {
                    Slot slot = slot(sl.optJSONObject(i));
                    // En okänd lista, ogiltiga dagar, tider eller datum, eller from efter to: raden hoppas över
                    if (slot != null && s.lists.containsKey(slot.list)) s.slots.add(slot);
                }
            }
            return s;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static Slot slot(JSONObject o) {
        if (o == null) return null;
        Slot s = new Slot();
        s.list = o.optString("list", "");
        JSONArray d = o.optJSONArray("days");
        if (d != null) {
            s.days = new int[d.length()];
            for (int i = 0; i < d.length(); i++) {
                s.days[i] = d.optInt(i, 0);
                if (s.days[i] < 1 || s.days[i] > 7) return null;
            }
        }
        if (o.has("from")) {
            s.from = minutes(o.optString("from"));
            if (s.from < 0) return null;
        }
        if (o.has("to")) {
            s.to = minutes(o.optString("to"));
            if (s.to < 0) return null;
        }
        if (s.from > s.to) return null;
        JSONArray dates = o.optJSONArray("dates");
        if (dates != null && dates.length() > 0) {
            s.dateFrom = dates.optString(0);
            s.dateTo = dates.length() > 1 ? dates.optString(1) : s.dateFrom;
            if (!s.dateFrom.matches("\\d{4}-\\d{2}-\\d{2}") || !s.dateTo.matches("\\d{4}-\\d{2}-\\d{2}") || s.dateFrom.compareTo(s.dateTo) > 0) return null;
        }
        return s;
    }

    /** "HH:MM" till minuter (24:00 = 1440), annars -1 */
    private static int minutes(String t) {
        if (t == null || !t.matches("\\d{1,2}:\\d{2}")) return -1;
        int h = Integer.parseInt(t.substring(0, t.indexOf(':'))), m = Integer.parseInt(t.substring(t.indexOf(':') + 1));
        if (m > 59 || h > 24 || (h == 24 && m != 0)) return -1;
        return h * 60 + m;
    }

    /** Alla sidor i alla listor, för listan över tillåtna webbplatser */
    List<SignageScreen.Page> allPages() {
        List<SignageScreen.Page> all = new ArrayList<>();
        for (List<SignageScreen.Page> p : lists.values()) all.addAll(p);
        return all;
    }

    private static boolean matches(Slot s, Calendar now) {
        int dow = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1; // måndag = 1 … söndag = 7
        if (s.days != null) {
            boolean day = false;
            for (int d : s.days) if (d == dow) day = true;
            if (!day) return false;
        }
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (minute < s.from || minute >= s.to) return false;
        if (s.dateFrom != null) {
            String today = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
            if (today.compareTo(s.dateFrom) < 0 || today.compareTo(s.dateTo) > 0) return false;
        }
        return true;
    }

    /** Listan som gäller vid now. pages null och inte svart: standardlistan. */
    Choice choose(Calendar now) {
        Slot dated = null, plain = null;
        for (Slot s : slots) {
            if (!matches(s, now)) continue;
            if (s.dateFrom != null) {
                if (dated == null) dated = s;
            } else if (plain == null) plain = s;
        }
        Slot pick = dated != null ? dated : plain;
        if (pick == null) return new Choice(off ? "off" : "default", null, off);
        List<SignageScreen.Page> list = lists.get(pick.list);
        if (list == null || list.isEmpty()) return new Choice("default", null, false);
        return new Choice("list:" + pick.list, list, false);
    }
}
