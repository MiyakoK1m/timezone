package kz.freetime.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;

/** Собирает RemoteViews для трёх виджетов из готового расчёта FreeTimeData.Snapshot. */
final class WidgetViews {

    private static final String NO_ACCESS = "Откройте приложение и разрешите доступ к календарю";

    private WidgetViews() {
    }

    // ---------- общее ----------

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Размер виджета в dp: ширина и высота в портретной ориентации. */
    private static int[] sizeDp(Bundle o, int defW, int defH) {
        int w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return new int[]{w > 0 ? w : defW, h > 0 ? h : defH};
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    private static int col(Context c, int id) {
        return c.getColor(id);
    }

    // ---------- «Сейчас» ----------

    static RemoteViews now(Context c, FreeTimeData.Snapshot s, Bundle options) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_now);
        v.setOnClickPendingIntent(R.id.root, openApp(c));
        int[] size = sizeDp(options, 110, 110);
        boolean compact = size[1] < 100;

        if (!s.hasPermission) {
            v.setTextViewText(R.id.label, "Свободное время");
            v.setTextViewText(R.id.big, "Нет доступа");
            v.setTextColor(R.id.big, col(c, R.color.w_ink));
            v.setTextViewText(R.id.sub, NO_ACCESS);
            v.setViewVisibility(R.id.foot, View.GONE);
            return v;
        }

        FreeTimeData.Day d0 = s.days.get(0), d1 = s.days.get(1);
        String label, big, sub, foot;
        int bigColor = col(c, R.color.w_free);
        switch (s.status) {
            case FreeTimeData.STATUS_BUSY:
                label = "Сейчас занято";
                big = "до " + FreeTimeData.hm(s.statusUntil);
                sub = s.statusTitle == null ? "" : s.statusTitle;
                bigColor = col(c, R.color.w_ink);
                foot = "Сегодня свободно " + FreeTimeData.dur(d0.freeMins);
                break;
            case FreeTimeData.STATUS_FREE:
                label = "Сейчас свободно";
                big = "до " + FreeTimeData.hm(s.statusUntil);
                sub = "ещё " + FreeTimeData.dur((int) ((s.statusUntil - s.now) / 60000L));
                foot = "Сегодня свободно " + FreeTimeData.dur(d0.freeMins);
                break;
            case FreeTimeData.STATUS_BEFORE: {
                FreeTimeData.Slot f = d0.slots.isEmpty() ? null : d0.slots.get(0);
                label = "Первое окно сегодня";
                big = f == null ? "—" : "с " + FreeTimeData.hm(f.start);
                sub = f == null ? "свободных окон нет" : "до " + FreeTimeData.hm(f.end) + " · " + FreeTimeData.dur(f.mins);
                foot = "Сегодня свободно " + FreeTimeData.dur(d0.freeMins);
                break;
            }
            default: {
                FreeTimeData.Slot f = d1.slots.isEmpty() ? null : d1.slots.get(0);
                label = "Завтра первое окно";
                big = f == null ? "—" : "с " + FreeTimeData.hm(f.start);
                sub = f == null ? "свободных окон нет" : "до " + FreeTimeData.hm(f.end) + " · " + FreeTimeData.dur(f.mins);
                foot = "Завтра свободно " + FreeTimeData.dur(d1.freeMins);
                break;
            }
        }
        v.setTextViewText(R.id.label, label);
        v.setTextViewText(R.id.big, big);
        v.setTextColor(R.id.big, bigColor);
        v.setTextViewText(R.id.sub, sub);
        v.setViewVisibility(R.id.sub, sub.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.foot, foot);
        v.setViewVisibility(R.id.foot, compact ? View.GONE : View.VISIBLE);
        return v;
    }

    // ---------- «Свободные окна» ----------

    private static final int[] SLOT_ROWS = {R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5};

    static RemoteViews slots(Context c, FreeTimeData.Snapshot s, Bundle options) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_slots);
        v.setOnClickPendingIntent(R.id.root, openApp(c));
        int[] size = sizeDp(options, 250, 140);

        if (!s.hasPermission) {
            v.setTextViewText(R.id.title, "Свободные окна");
            v.setTextViewText(R.id.total, "");
            v.setViewVisibility(R.id.bar, View.GONE);
            for (int id : SLOT_ROWS) v.setViewVisibility(id, View.GONE);
            v.setTextViewText(R.id.note, NO_ACCESS);
            v.setViewVisibility(R.id.note, View.VISIBLE);
            return v;
        }

        int idx = 0;
        FreeTimeData.Day d = s.days.get(0);
        if (d.slots.isEmpty()) {
            idx = 1;
            d = s.days.get(1);
        }
        v.setTextViewText(R.id.title, (idx == 0 ? "Сегодня" : "Завтра") + " · " + FreeTimeData.dateLabel(d.midnight));
        v.setTextViewText(R.id.total, d.freeMins > 0 ? FreeTimeData.dur(d.freeMins) : "");

        v.setViewVisibility(R.id.bar, View.VISIBLE);
        v.setImageViewBitmap(R.id.bar, drawTimeline(c, d, s.now, dp(c, size[0] - 24), dp(c, 10)));

        int fit = Math.max(1, (size[1] - 58) / 25);
        if (d.slots.size() > fit) fit = Math.max(1, (size[1] - 58 - 16) / 25);
        int rows = Math.min(Math.min(fit, SLOT_ROWS.length), d.slots.size());
        for (int i = 0; i < SLOT_ROWS.length; i++) {
            if (i < rows) {
                v.setViewVisibility(SLOT_ROWS[i], View.VISIBLE);
                v.setTextViewText(SLOT_ROWS[i], slotText(c, d.slots.get(i), s.now));
            } else {
                v.setViewVisibility(SLOT_ROWS[i], View.GONE);
            }
        }
        int hidden = d.slots.size() - rows;
        if (d.slots.isEmpty()) {
            v.setTextViewText(R.id.note, "Свободных окон от " + s.settings.minSlot + " мин нет");
            v.setViewVisibility(R.id.note, View.VISIBLE);
        } else if (hidden > 0) {
            v.setTextViewText(R.id.note, "и ещё " + hidden + " " + FreeTimeData.plural(hidden, "окно", "окна", "окон"));
            v.setViewVisibility(R.id.note, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.note, View.GONE);
        }
        return v;
    }

    private static CharSequence slotText(Context c, FreeTimeData.Slot sl, long now) {
        String time = (sl.start <= now + 5 * 60000L ? "сейчас" : FreeTimeData.hm(sl.start)) + "–" + FreeTimeData.hm(sl.end);
        String d = FreeTimeData.dur(sl.mins);
        SpannableStringBuilder b = new SpannableStringBuilder();
        b.append(time);
        b.setSpan(new StyleSpan(Typeface.BOLD), 0, time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append("   ");
        int from = b.length();
        b.append(d);
        b.setSpan(new ForegroundColorSpan(col(c, R.color.w_muted)), from, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    // ---------- «Неделя» ----------

    static RemoteViews week(Context c, FreeTimeData.Snapshot s, Bundle options) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_week);
        v.setOnClickPendingIntent(R.id.root, openApp(c));
        int[] size = sizeDp(options, 250, 140);

        v.setTextViewText(R.id.title, "Свободно за 7 дней");
        if (!s.hasPermission) {
            v.setTextViewText(R.id.total, "");
            v.setViewVisibility(R.id.chart, View.GONE);
            v.setTextViewText(R.id.note, NO_ACCESS);
            v.setViewVisibility(R.id.note, View.VISIBLE);
            return v;
        }
        v.setTextViewText(R.id.total, FreeTimeData.dur(s.weekFreeMins));
        v.setViewVisibility(R.id.note, View.GONE);
        v.setViewVisibility(R.id.chart, View.VISIBLE);
        int chartH = Math.max(48, size[1] - 24 - 20 - 6);
        v.setImageViewBitmap(R.id.chart, drawWeek(c, s, dp(c, size[0] - 24), dp(c, chartH)));
        return v;
    }

    // ---------- картинки ----------

    private static Bitmap blank(int w, int h) {
        return Bitmap.createBitmap(Math.max(8, Math.min(w, 1600)), Math.max(8, Math.min(h, 900)), Bitmap.Config.ARGB_8888);
    }

    /** Полоса дня: занято — серым, свободные окна — акцентом, прошедшее время приглушено. */
    static Bitmap drawTimeline(Context c, FreeTimeData.Day d, long now, int w, int h) {
        Bitmap bmp = blank(w, h);
        w = bmp.getWidth();
        h = bmp.getHeight();
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = h / 2f;
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
        cv.clipPath(clip);

        float span = Math.max(1, d.wEnd - d.wStart);
        p.setColor(col(c, R.color.w_free_soft));
        cv.drawRect(0, 0, w, h, p);
        p.setColor(col(c, R.color.w_busy));
        for (FreeTimeData.Busy b : d.busy) {
            cv.drawRect(w * (b.start - d.wStart) / span, 0, w * (b.end - d.wStart) / span, h, p);
        }
        p.setColor(col(c, R.color.w_free));
        for (FreeTimeData.Slot sl : d.slots) {
            cv.drawRect(w * (sl.start - d.wStart) / span, 0, w * (sl.end - d.wStart) / span, h, p);
        }
        if (now > d.wStart) {
            int bg = col(c, R.color.w_bg_solid);
            p.setColor(Color.argb(170, Color.red(bg), Color.green(bg), Color.blue(bg)));
            cv.drawRect(0, 0, w * (Math.min(now, d.wEnd) - d.wStart) / span, h, p);
        }
        if (now > d.wStart && now < d.wEnd) {
            float x = w * (now - d.wStart) / span;
            p.setColor(col(c, R.color.w_ink));
            cv.drawRect(x - dp(c, 1), 0, x + dp(c, 1), h, p);
        }
        return bmp;
    }

    /** Семь столбиков: высота — доля свободного времени в окне дня. */
    static Bitmap drawWeek(Context c, FreeTimeData.Snapshot s, int w, int h) {
        Bitmap bmp = blank(w, h);
        w = bmp.getWidth();
        h = bmp.getHeight();
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(sp(c, 11));

        float label = sp(c, 11) * 1.6f;
        float top = label, bottom = h - label;
        float maxBar = Math.max(1, bottom - top);
        int maxWin = 1;
        for (FreeTimeData.Day d : s.days) maxWin = Math.max(maxWin, d.windowMins());

        float cw = w / 7f;
        float bw = Math.min(cw * 0.56f, dp(c, 22));
        float rad = dp(c, 5);
        for (int i = 0; i < s.days.size(); i++) {
            FreeTimeData.Day d = s.days.get(i);
            float cx = cw * i + cw / 2f;

            p.setColor(col(c, R.color.w_free_soft));
            cv.drawRoundRect(new RectF(cx - bw / 2, top, cx + bw / 2, bottom), rad, rad, p);
            if (d.freeMins > 0) {
                float bh = Math.max(dp(c, 4), maxBar * d.freeMins / (float) maxWin);
                p.setColor(col(c, R.color.w_free));
                cv.drawRoundRect(new RectF(cx - bw / 2, bottom - bh, cx + bw / 2, bottom), rad, rad, p);
            }

            boolean today = i == 0;
            t.setFakeBoldText(today);
            t.setColor(col(c, today ? R.color.w_ink : R.color.w_muted));
            cv.drawText(d.freeMins > 0 ? FreeTimeData.durShort(d.freeMins) : "0", cx, label - dp(c, 4), t);

            int wd = d.weekday();
            boolean weekend = wd == Calendar.SATURDAY || wd == Calendar.SUNDAY;
            t.setColor(col(c, weekend ? R.color.w_warn : (today ? R.color.w_ink : R.color.w_muted)));
            cv.drawText(today ? "Сег" : FreeTimeData.DW_SHORT[wd - 1], cx, h - dp(c, 3), t);
        }
        return bmp;
    }
}
