package kz.freetime.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Пересчитывает свободное время один раз и обновляет все виджеты на рабочем столе. */
final class WidgetUpdater {

    static final String ACTION_TICK = "kz.freetime.app.WIDGET_TICK";

    private WidgetUpdater() {
    }

    static void updateAll(Context ctx) {
        updateAll(ctx, false);
    }

    static synchronized void updateAll(Context ctx, boolean fromJob) {
        Context c = ctx.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] nowIds = m.getAppWidgetIds(new ComponentName(c, NowWidget.class));
        int[] slotIds = m.getAppWidgetIds(new ComponentName(c, SlotsWidget.class));
        int[] weekIds = m.getAppWidgetIds(new ComponentName(c, WeekWidget.class));

        if (nowIds.length + slotIds.length + weekIds.length == 0) {
            cancelTick(c);
            CalendarChangeJob.cancel(c);
            return;
        }

        FreeTimeData.Snapshot s = FreeTimeData.compute(c, System.currentTimeMillis());
        for (int id : nowIds) push(m, id, WidgetViews.now(c, s, m.getAppWidgetOptions(id)));
        for (int id : slotIds) push(m, id, WidgetViews.slots(c, s, m.getAppWidgetOptions(id)));
        for (int id : weekIds) push(m, id, WidgetViews.week(c, s, m.getAppWidgetOptions(id)));

        scheduleTick(c, s.nextChangeAt);
        if (!fromJob) CalendarChangeJob.scheduleIfMissing(c);
    }

    static boolean hasWidgets(Context ctx) {
        Context c = ctx.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m.getAppWidgetIds(new ComponentName(c, NowWidget.class)).length
                + m.getAppWidgetIds(new ComponentName(c, SlotsWidget.class)).length
                + m.getAppWidgetIds(new ComponentName(c, WeekWidget.class)).length > 0;
    }

    private static void push(AppWidgetManager m, int id, RemoteViews v) {
        try {
            m.updateAppWidget(id, v);
        } catch (Exception ignored) {
            // виджет удалили во время обновления
        }
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, WidgetTickReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Неточный будильник: срабатывает, когда телефон и так проснулся, батарею не тратит. */
    private static void scheduleTick(Context c, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.set(AlarmManager.RTC, at, tickIntent(c));
    }

    private static void cancelTick(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(tickIntent(c));
    }
}
