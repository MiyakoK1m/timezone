package kz.freetime.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Плановое обновление виджетов (на границах окон и не реже раза в 15 минут). */
public class WidgetTickReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
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
