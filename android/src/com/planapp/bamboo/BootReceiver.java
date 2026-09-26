package com.planapp.bamboo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机或应用更新后，把还没到的提醒重新排进闹钟 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            ReminderReceiver.scheduleAll(ctx);
        }
    }
}
