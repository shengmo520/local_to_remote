package com.planapp.bamboo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 到点弹系统通知。
 * 页面每次改数据都会把「待提醒清单」推过来，这里整体重排一次 AlarmManager。
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "plan_reminder";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_GOAL = "goal";

    /** 应用在前台时页面自己会弹提醒卡片，避免重复打扰 */
    public static volatile boolean foreground = false;

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("planapp", Context.MODE_PRIVATE);
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL_ID, "行动提醒", NotificationManager.IMPORTANCE_HIGH);
        c.setDescription("到点的行动提醒");
        c.enableVibration(true);
        nm.createNotificationChannel(c);
    }

    public static void sync(Context ctx, String json) {
        SharedPreferences p = prefs(ctx);
        cancelAll(ctx);
        p.edit().putString("reminders", json == null ? "[]" : json).apply();
        scheduleAll(ctx);
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("reminders", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                am.cancel(pending(ctx, o.optString("id"), null, null));
            }
        } catch (Exception ignored) {}
    }

    /** 开机 / 应用被覆盖安装后重新排一遍未来的提醒 */
    public static void scheduleAll(Context ctx) {
        ensureChannel(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("reminders", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long at = o.optLong("at", 0);
                if (at <= now) continue;
                PendingIntent pi = pending(ctx, o.optString("id"), o.optString("title"), o.optString("goal"));
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } catch (SecurityException e) {
                    am.set(AlarmManager.RTC_WAKEUP, at, pi);   // 没拿到精确闹钟权限就退化
                }
            }
        } catch (Exception ignored) {}
    }

    private static PendingIntent pending(Context ctx, String id, String title, String goal) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.putExtra(EXTRA_ID, id == null ? "" : id);
        if (title != null) i.putExtra(EXTRA_TITLE, title);
        if (goal != null) i.putExtra(EXTRA_GOAL, goal);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, id == null ? 0 : id.hashCode(), i, flags);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (foreground) return;
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(ctx);

        String id = intent.getStringExtra(EXTRA_ID);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String goal = intent.getStringExtra(EXTRA_GOAL);
        if (title == null || title.length() == 0) title = "行动提醒";
        if (goal == null) goal = "";

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(goal)
                .setStyle(new Notification.BigTextStyle().bigText(goal))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id == null || id.length() == 0 ? 1 : id.hashCode(), n);
    }
}
