package dev.sparklecat.xpatch;

import android.content.*;
import android.content.pm.PackageInstaller;

public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirmation == null) throw new IllegalStateException("Android returned no confirmation screen.");
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                message = "Confirm installation in Android's installer.";
            } catch (Exception e) { message = "Could not open installer: " + e.getMessage(); }
        } else if (status == PackageInstaller.STATUS_SUCCESS) message = "Patched X installed.";
        else message = "Installation failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "\nIf stock X is installed, its different signature requires uninstalling it first. Uninstalling deletes its local app data.";
        context.getSharedPreferences("xpatch", 0).edit().putString("status", message).apply();
    }
}
