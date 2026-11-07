package dev.sparklecat.xpatch;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.*;
import java.util.List;

final class Installer {
    static void install(Context context) throws Exception {
        List<File> parts = Pipeline.outputParts(context);
        if (parts.isEmpty()) throw new IOException("Build a patched APK first.");
        install(context, parts, "com.twitter.android");
    }
    static void install(Context context, List<File> parts, String packageName) throws Exception {
        if (parts.isEmpty()) throw new IOException("No APK is available.");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params); boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            for (int i = 0; i < parts.size(); i++) {
                File file = parts.get(i);
                try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite("part-" + i + ".apk", 0, file.length())) {
                    ApkPatcher.copy(in, out, ApkPatcher.MAX_APK); session.fsync(out);
                }
            }
            Intent result = new Intent(context, InstallReceiver.class).setAction("dev.sparklecat.xpatch.INSTALL_RESULT");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).getIntentSender()); committed = true;
        } finally { if (!committed) installer.abandonSession(id); }
    }
}
