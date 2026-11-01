package github.gtouming.itemsync.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                context.startForegroundService(new Intent(context, SyncForegroundService.class));
            } catch (RuntimeException ignored) {
                // newer Android versions may restrict foreground services at boot
            }
        }
    }
}
