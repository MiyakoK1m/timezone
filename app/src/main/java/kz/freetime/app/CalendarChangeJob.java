package kz.freetime.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.CalendarContract;

/** Срабатывает, когда в календаре телефона что-то изменилось, и обновляет виджеты. */
public class CalendarChangeJob extends JobService {

    private static final int JOB_ID = 4201;

    private static JobInfo build(Context c) {
        return new JobInfo.Builder(JOB_ID, new ComponentName(c, CalendarChangeJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3000)
                .setTriggerContentMaxDelay(15000)
                .build();
    }

    static void scheduleIfMissing(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        try {
            js.schedule(build(c));
        } catch (Exception ignored) {
            // планировщик недоступен — останутся обновления по таймеру
        }
    }

    static void cancel(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                WidgetUpdater.updateAll(app, true);
                jobFinished(params, false);
                if (WidgetUpdater.hasWidgets(app)) {
                    JobScheduler js = app.getSystemService(JobScheduler.class);
                    try {
                        if (js != null) js.schedule(build(app));
                    } catch (Exception ignored) {
                        // следующее изменение подхватит таймер
                    }
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
