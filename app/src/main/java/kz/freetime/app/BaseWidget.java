package kz.freetime.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Общий провайдер: любое событие виджета — пересчитать и перерисовать все виджеты. */
public abstract class BaseWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle newOptions) {
        refresh(context);
    }

    @Override
    public void onDisabled(Context context) {
        refresh(context);
    }

    private void refresh(Context context) {
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WidgetUpdater.updateAll(app);
                } finally {
                    result.finish();
                }
            }
        }).start();
    }
}
