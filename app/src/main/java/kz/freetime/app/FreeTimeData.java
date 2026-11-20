package kz.freetime.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Расчёт свободных окон для виджетов. Логика совпадает с assets/index.html:
 * окно дня, занятость из календарей телефона + рабочие часы, окна не короче minSlot.
 * Настройки приходят из приложения (MainActivity.saveSettings) через SharedPreferences.
 */
final class FreeTimeData {

    static final String PREFS = "freetime";
    static final String KEY_SETTINGS = "settings";
    static final int DAYS = 7;

    static final int STATUS_FREE = 0;
    static final int STATUS_BUSY = 1;
    static final int STATUS_BEFORE = 2;
    static final int STATUS_AFTER = 3;

    static final String[] DW_SHORT = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
    static final String[] MON_GEN = {"января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    private FreeTimeData() {
    }

    static final class Settings {
        int dayStart = 6 * 60;
        int dayEnd = 22 * 60;
        int minSlot = 30;
        boolean workOn = true;
        int workStart = 9 * 60;
        int workEnd = 18 * 60;
        Set<String> calIds = null; // null — все видимые календари
    }

    static final class Busy {
        final long start;
        final long end;
        final String title;
        final boolean work;

        Busy(long start, long end, String title, boolean work) {
            this.start = start;
            this.end = end;
            this.title = title;
            this.work = work;
        }
    }

    static final class Slot {
        final long start;
        final long end;
        final int mins;

        Slot(long start, long end, int mins) {
            this.start = start;
            this.end = end;
            this.mins = mins;
        }
    }

    static final class Day {
        long midnight;
        long wStart;
        long wEnd;
        final List<Busy> busy = new ArrayList<>();
        final List<long[]> merged = new ArrayList<>();
        final List<Slot> slots = new ArrayList<>();
        int freeMins;
        boolean over;

        int windowMins() {
            return (int) ((wEnd - wStart) / 60000L);
        }

        int weekday() {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(midnight);
            return c.get(Calendar.DAY_OF_WEEK);
        }
    }

    static final class Snapshot {
        boolean hasPermission = true;
        String error;
        long now;
        Settings settings;
        final List<Day> days = new ArrayList<>();
        int weekFreeMins;
        int status;
        long statusUntil;
        String statusTitle;
        long nextChangeAt;
    }

    // ---------- настройки ----------

    static int toMin(String s, int def) {
        if (s == null) return def;
        String[] p = s.trim().split(":");
        if (p.length < 2) return def;
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim().substring(0, 2));
            return h * 60 + m;
        } catch (Exception e) {
            return def;
        }
    }

    static Settings loadSettings(Context c) {
        Settings s = new Settings();
        String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SETTINGS, null);
        if (raw == null) return s;
        try {
            JSONObject o = new JSONObject(raw);
            s.dayStart = toMin(o.optString("dayStart", null), s.dayStart);
            s.dayEnd = toMin(o.optString("dayEnd", null), s.dayEnd);
            s.minSlot = o.optInt("minSlot", s.minSlot);
            s.workOn = o.optBoolean("workOn", s.workOn);
            s.workStart = toMin(o.optString("workStart", null), s.workStart);
            s.workEnd = toMin(o.optString("workEnd", null), s.workEnd);
            JSONArray ids = o.optJSONArray("calIds");
            if (ids != null) {
                s.calIds = new HashSet<>();
                for (int i = 0; i < ids.length(); i++) s.calIds.add(ids.optString(i));
            }
        } catch (Exception ignored) {
            // повреждённые настройки — берём значения по умолчанию
        }
        if (s.minSlot <= 0) s.minSlot = 30;
        return s;
    }

    // ---------- время ----------

    static Calendar midnight(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static long at(Calendar base, int dayOffset, int minutes) {
        Calendar c = (Calendar) base.clone();
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        c.add(Calendar.MINUTE, minutes);
        return c.getTimeInMillis();
    }

    static String hm(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    static String dur(int mins) {
        int h = mins / 60, m = mins % 60;
        if (h == 0) return m + " мин";
        if (m == 0) return h + " ч";
        return h + " ч " + m + " мин";
    }

    static String durShort(int mins) {
        int h = mins / 60, m = mins % 60;
        if (h == 0) return m + "м";
        return m == 0 ? h + "ч" : h + "ч" + String.format(Locale.ROOT, "%02d", m);
    }

    static String dateLabel(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.DAY_OF_MONTH) + " " + MON_GEN[c.get(Calendar.MONTH)];
    }

    static String plural(int n, String one, String few, String many) {
        int a = Math.abs(n) % 100, b = a % 10;
        if (a > 10 && a < 20) return many;
        if (b > 1 && b < 5) return few;
        if (b == 1) return one;
        return many;
    }

    // ---------- расчёт ----------

    static Snapshot compute(Context c, long now) {
        Snapshot snap = new Snapshot();
        snap.now = now;
        Settings st = loadSettings(c);
        snap.settings = st;
        Calendar today = midnight(now);

        List<Busy> events = new ArrayList<>();
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            snap.hasPermission = false;
        } else {
            try {
                events = queryEvents(c, at(today, -1, 0), at(today, DAYS + 1, 0), st);
            } catch (Exception e) {
                snap.error = e.getMessage();
            }
        }

        long nowR = ((now + 299999L) / 300000L) * 300000L; // как на странице: вверх до 5 минут
        int ws = st.dayStart, we = st.dayEnd;
        if (we <= ws) we += 1440;

        for (int i = 0; i < DAYS; i++) {
            Day d = new Day();
            d.midnight = at(today, i, 0);
            d.wStart = at(today, i, ws);
            d.wEnd = at(today, i, we);

            for (Busy e : events) {
                if (e.end > d.wStart && e.start < d.wEnd) {
                    d.busy.add(new Busy(Math.max(e.start, d.wStart), Math.min(e.end, d.wEnd), e.title, false));
                }
            }
            int wd = d.weekday();
            if (st.workOn && wd != Calendar.SATURDAY && wd != Calendar.SUNDAY && st.workEnd > st.workStart) {
                long s = at(today, i, st.workStart), f = at(today, i, st.workEnd);
                if (f > d.wStart && s < d.wEnd) {
                    d.busy.add(new Busy(Math.max(s, d.wStart), Math.min(f, d.wEnd), "Работа", true));
                }
            }
            Collections.sort(d.busy, new Comparator<Busy>() {
                @Override
                public int compare(Busy x, Busy y) {
                    return Long.compare(x.start, y.start);
                }
            });
            for (Busy b : d.busy) {
                long[] last = d.merged.isEmpty() ? null : d.merged.get(d.merged.size() - 1);
                if (last != null && b.start <= last[1]) {
                    if (b.end > last[1]) last[1] = b.end;
                } else {
                    d.merged.add(new long[]{b.start, b.end});
                }
            }

            long cur = nowR > d.wStart ? Math.min(nowR, d.wEnd) : d.wStart;
            for (long[] m : d.merged) {
                if (m[0] > cur) addSlot(d, cur, m[0], st.minSlot);
                if (m[1] > cur) cur = m[1];
            }
            if (d.wEnd > cur) addSlot(d, cur, d.wEnd, st.minSlot);

            d.over = now >= d.wEnd;
            snap.days.add(d);
            snap.weekFreeMins += d.freeMins;
        }

        computeStatus(snap);
        return snap;
    }

    private static void addSlot(Day d, long a, long b, int minSlot) {
        int mins = (int) ((b - a) / 60000L);
        if (mins >= minSlot) {
            d.slots.add(new Slot(a, b, mins));
            d.freeMins += mins;
        }
    }

    private static List<Busy> queryEvents(Context c, long from, long to, Settings st) {
        List<Busy> out = new ArrayList<>();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] projection = {
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.VISIBLE
        };
        try (Cursor cur = c.getContentResolver().query(b.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                if (cur.getInt(3) == 1) continue; // событие на весь день не занимает время
                if (!cur.isNull(4) && cur.getInt(4) == CalendarContract.Events.AVAILABILITY_FREE) continue;
                if (!cur.isNull(5) && cur.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                if (!cur.isNull(6) && cur.getInt(6) == CalendarContract.Events.STATUS_CANCELED) continue;
                String calId = String.valueOf(cur.getLong(7));
                boolean include = st.calIds != null ? st.calIds.contains(calId) : cur.getInt(8) == 1;
                if (!include) continue;
                long s = cur.getLong(0), e = cur.getLong(1);
                if (e <= s) continue;
                String title = cur.isNull(2) ? "" : cur.getString(2);
                out.add(new Busy(s, e, title.isEmpty() ? "Без названия" : title, false));
            }
        }
        return out;
    }

    private static void computeStatus(Snapshot s) {
        Day d0 = s.days.get(0), d1 = s.days.get(1);
        long now = s.now;
        if (now < d0.wStart) {
            s.status = STATUS_BEFORE;
            s.statusUntil = d0.wStart;
        } else if (now >= d0.wEnd) {
            s.status = STATUS_AFTER;
            s.statusUntil = d1.wStart;
        } else {
            long[] inside = null;
            for (long[] m : d0.merged) {
                if (m[0] <= now && now < m[1]) {
                    inside = m;
                    break;
                }
            }
            if (inside != null) {
                s.status = STATUS_BUSY;
                s.statusUntil = inside[1];
                for (Busy b : d0.busy) {
                    if (b.start <= now && now < b.end && !b.work) {
                        s.statusTitle = b.title;
                        break;
                    }
                }
                if (s.statusTitle == null) s.statusTitle = "Работа";
            } else {
                s.status = STATUS_FREE;
                s.statusUntil = d0.wEnd;
                for (long[] m : d0.merged) {
                    if (m[0] > now) {
                        s.statusUntil = m[0];
                        break;
                    }
                }
            }
        }

        // Когда виджеты стоит перерисовать: ближайшая граница занятости, но не реже раза в 15 минут
        long next = now + 15 * 60000L;
        Day[] near = {d0, d1};
        for (Day d : near) {
            next = earlier(next, now, d.wStart);
            next = earlier(next, now, d.wEnd);
            for (long[] m : d.merged) {
                next = earlier(next, now, m[0]);
                next = earlier(next, now, m[1]);
            }
        }
        next = earlier(next, now, d1.midnight + 60000L);
        s.nextChangeAt = Math.max(next + 5000L, now + 60000L);
    }

    private static long earlier(long current, long now, long candidate) {
        return candidate > now && candidate < current ? candidate : current;
    }
}
