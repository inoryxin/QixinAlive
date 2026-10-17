package xin.inory.alive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后自动把常驻服务拉起来。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                AliveService.start(context);
            } catch (Throwable ignored) {
            }
        }
    }
}
