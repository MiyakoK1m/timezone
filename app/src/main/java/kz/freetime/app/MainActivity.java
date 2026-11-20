package kz.freetime.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Показывает assets/index.html во WebView и даёт странице объект window.AndroidCalendar
 * для чтения календаря телефона (все аккаунты: Google, Outlook/Teams, Samsung, локальный).
 * Приложение только читает календарь и не выходит в интернет.
 */
public class MainActivity extends Activity {

    private static final int REQ_CALENDAR = 101;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // нет приложения для ссылки
                }
                return true;
            }
        });
        web.addJavascriptInterface(new CalendarBridge(), "AndroidCalendar");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        callJs("window.onAppResume && window.onAppResume()");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CALENDAR) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        boolean canAskAgain = granted || shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR);
        callJs("window.onCalendarPermission && window.onCalendarPermission(" + granted + "," + canAskAgain + ")");
    }

    private boolean hasCalendarPermission() {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private void callJs(final String script) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(script, null);
            }
        });
    }

    private static String fail(String message) {
        JSONObject res = new JSONObject();
        try {
            res.put("ok", false);
            res.put("error", message == null ? "unknown" : message);
        } catch (JSONException ignored) {
            // не бывает для строк
        }
        return res.toString();
    }

    /** Методы, доступные из JavaScript как window.AndroidCalendar.*. Возвращают JSON-строки. */
    private class CalendarBridge {

        @JavascriptInterface
        public boolean hasPermission() {
            return hasCalendarPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_CALENDAR);
                }
            });
        }

        /** Настройки расчёта со страницы (JSON) — их читают виджеты. */
        @JavascriptInterface
        public void saveSettings(String json) {
            getSharedPreferences(FreeTimeData.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(FreeTimeData.KEY_SETTINGS, json)
                    .apply();
        }

        /** Перерисовать виджеты на рабочем столе. */
        @JavascriptInterface
        public void updateWidgets() {
            WidgetUpdater.updateAll(getApplicationContext());
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                }
            });
        }

        /** {"ok":true,"items":[{id,name,account,accountType,color,visible}]} */
        @JavascriptInterface
        public String getCalendars() {
            if (!hasCalendarPermission()) return fail("no_permission");
            String[] projection = {
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.VISIBLE
            };
            JSONArray items = new JSONArray();
            try (Cursor c = getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                    projection, null, null, CalendarContract.Calendars.ACCOUNT_NAME + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        o.put("id", c.getLong(0));
                        o.put("name", c.isNull(1) ? "" : c.getString(1));
                        o.put("account", c.isNull(2) ? "" : c.getString(2));
                        o.put("accountType", c.isNull(3) ? "" : c.getString(3));
                        o.put("color", String.format(Locale.ROOT, "#%06X", 0xFFFFFF & c.getInt(4)));
                        o.put("visible", c.getInt(5) == 1);
                        items.put(o);
                    }
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("items", items);
                return res.toString();
            } catch (Exception e) {
                return fail(e.getMessage());
            }
        }

        /**
         * События (с развёрнутыми повторениями) в интервале [fromMs, toMs).
         * {"ok":true,"items":[{eventId,title,begin,end,allDay,availability,selfStatus,status,calendarId}]}
         * availability: 0 занят, 1 свободен, 2 под вопросом; selfStatus 2 = отклонено; status 2 = отменено.
         */
        @JavascriptInterface
        public String getEvents(String fromMs, String toMs) {
            if (!hasCalendarPermission()) return fail("no_permission");
            try {
                long from = Long.parseLong(fromMs);
                long to = Long.parseLong(toMs);
                Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(b, from);
                ContentUris.appendId(b, to);
                String[] projection = {
                        CalendarContract.Instances.EVENT_ID,
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.ALL_DAY,
                        CalendarContract.Instances.AVAILABILITY,
                        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                        CalendarContract.Instances.STATUS,
                        CalendarContract.Instances.CALENDAR_ID
                };
                JSONArray items = new JSONArray();
                try (Cursor c = getContentResolver().query(b.build(), projection, null, null,
                        CalendarContract.Instances.BEGIN + " ASC")) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("eventId", c.getLong(0));
                            o.put("title", c.isNull(1) ? "" : c.getString(1));
                            o.put("begin", c.getLong(2));
                            o.put("end", c.getLong(3));
                            o.put("allDay", c.getInt(4) == 1);
                            o.put("availability", c.isNull(5) ? 0 : c.getInt(5));
                            o.put("selfStatus", c.isNull(6) ? 0 : c.getInt(6));
                            o.put("status", c.isNull(7) ? 1 : c.getInt(7));
                            o.put("calendarId", c.getLong(8));
                            items.put(o);
                        }
                    }
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("items", items);
                return res.toString();
            } catch (Exception e) {
                return fail(e.getMessage());
            }
        }
    }
}
