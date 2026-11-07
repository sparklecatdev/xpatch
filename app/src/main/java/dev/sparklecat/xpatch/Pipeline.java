package dev.sparklecat.xpatch;

import android.content.Context;
import android.content.pm.*;
import com.android.apksig.ApkSigner;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

final class Pipeline {
    static final Object LOCK = new Object();
    static final class Input implements AutoCloseable {
        final DexEngine.Plan plan;
        final String version, sha;
        final List<ApkSet.Part> parts;
        final File directory;
        Input(DexEngine.Plan plan, String version, String sha, List<ApkSet.Part> parts, File directory) {
            this.plan = plan; this.version = version; this.sha = sha; this.parts = parts; this.directory = directory;
        }
        @Override public void close() { ApkSet.delete(directory); }
    }
    static Input inspect(Context context, File source) throws Exception {
        if (!source.isFile() || source.length() == 0 || source.length() > ApkPatcher.MAX_APK) throw new IOException("Choose an APK or APK set smaller than 1 GiB.");
        File directory = new File(context.getCacheDir(), "input-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("Could not prepare input folder.");
        try {
            List<ApkSet.Part> parts = ApkSet.read(source, directory);
            File base = parts.get(0).file;
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(base.getPath(), PackageManager.GET_META_DATA);
            if (info == null || info.applicationInfo == null) throw new IOException("Could not read the X manifest.");
            if (parts.size() == 1 && info.applicationInfo.metaData != null && info.applicationInfo.metaData.getBoolean("com.android.vending.splits.required", false)) throw new IOException("This base APK requires splits. Choose the complete XAPK/APKM/APKS set or use installed X.");
            try (ZipFile zip = new ZipFile(base)) {
                if (zip.getEntry("assets/xpatch/profile.json") != null) throw new IOException("Choose the unmodified APK, not a previous XPatch build.");
            }
            String app = info.applicationInfo.className;
            if (app == null || app.equals("android.app.Application")) throw new IOException("A custom Application class is required by this patch engine.");
            if (app.startsWith(".")) app = info.packageName + app;
            if (!app.contains(".")) app = info.packageName + "." + app;
            return new Input(DexEngine.analyze(base, app), info.versionName, ApkPatcher.sha256(source), parts, directory);
        } catch (Exception e) { ApkSet.delete(directory); throw e; }
    }
    static File output(Context context) {
        File set = new File(context.getFilesDir(), "patched.apks");
        return set.isFile() ? set : new File(context.getFilesDir(), "patched.apk");
    }
    static List<File> outputParts(Context context) {
        if (!output(context).getName().endsWith(".apks")) return Collections.singletonList(output(context));
        File[] parts = new File(context.getFilesDir(), "patched-splits").listFiles((dir, name) -> name.endsWith(".apk"));
        if (parts == null) return Collections.emptyList();
        Arrays.sort(parts, Comparator.comparing(File::getName)); return Arrays.asList(parts);
    }
    static String build(Context context, File source, Input input, JSONObject profile, ApkPatcher.Progress progress) throws Exception {
        Profile.validate(profile);
        if (profile.optBoolean("ads") && !input.plan.adsAvailable()) throw new IOException("Ad hook not found or model ambiguous. Disable ad removal to build UI mods, or provide this APK's compatibility report for a new matcher.");
        if (!input.sha.equals(ApkPatcher.sha256(source))) throw new IOException("Input changed. Analyze it again.");
        for (ApkSet.Part part : input.parts) if (!part.sha.equals(ApkPatcher.sha256(part.file))) throw new IOException("An input split changed. Analyze it again.");
        File work = new File(context.getCacheDir(), "patch-work"); ApkSet.delete(work);
        if (!work.mkdirs()) throw new IOException("Could not create work folder.");
        new File(context.getFilesDir(), "patched.apk").delete(); new File(context.getFilesDir(), "patched.apks").delete();
        File splits = new File(context.getFilesDir(), "patched-splits"); ApkSet.delete(splits);
        File runtime = new File(work, "runtime.dex");
        try (InputStream in = context.getAssets().open("runtime.dex"); OutputStream out = new FileOutputStream(runtime)) { ApkPatcher.copy(in, out, 8 * 1024 * 1024); }
        JSONObject manifest = new JSONObject().put("schema", 1).put("engine", 3).put("inputVersion", input.version).put("inputSha256", input.sha).put("entryFields", new JSONObject(input.plan.entryFields)).put("promotedFields", new JSONObject(input.plan.promotedFields)).put("mediaFields", new JSONObject(input.plan.mediaFields)).put("premiumSites", input.plan.premiumSites).put("profile", profile);
        Map<String, byte[]> notices = new LinkedHashMap<>();
        for (String name : new String[]{"NOTICE", "Piko-LICENSE.txt"}) {
            try (InputStream in = context.getAssets().open("xpatch/" + name); ByteArrayOutputStream out = new ByteArrayOutputStream()) { ApkPatcher.copy(in, out, 128 * 1024); notices.put(name, out.toByteArray()); }
        }
        boolean complete = false;
        try {
            ApkSigner.SignerConfig signer = SigningIdentity.load();
            if (!splits.mkdir()) throw new IOException("Could not prepare output folder.");
            List<File> outputs = new ArrayList<>();
            for (int i = 0; i < input.parts.size(); i++) {
                ApkSet.Part part = input.parts.get(i); File file = new File(splits, "part-" + i + ".apk");
                progress.update("Patching APK " + (i + 1) + "/" + input.parts.size());
                ApkPatcher.patch(part.file, file, work, i == 0 ? input.plan : null, i == 0 ? runtime : null, i == 0 ? manifest.toString().getBytes(StandardCharsets.UTF_8) : null, notices, signer, progress);
                outputs.add(file);
            }
            File output;
            if (outputs.size() == 1) {
                output = new File(context.getFilesDir(), "patched.apk");
                if (!outputs.get(0).renameTo(output)) throw new IOException("Could not publish output.");
                ApkSet.delete(splits);
            } else { output = new File(context.getFilesDir(), "patched.apks"); ApkSet.archive(outputs, output); }
            String report = "XPatch 0.2.0-alpha.2\nX version: " + input.version + "\nInput SHA-256: " + input.sha + "\nAPK count: " + input.parts.size() + "\n" + input.plan.summary() + "\nOutput SHA-256: " + ApkPatcher.sha256(output) + "\n\nBytecode matched; signed-in X behavior still needs device verification.\n";
            try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(context.getFilesDir(), "report.txt")), StandardCharsets.UTF_8)) { out.write(report); }
            context.getSharedPreferences("xpatch", 0).edit().putString("status", report).apply();
            complete = true; return report;
        } finally {
            ApkSet.delete(work);
            if (!complete) { new File(context.getFilesDir(), "patched.apk").delete(); new File(context.getFilesDir(), "patched.apks").delete(); ApkSet.delete(splits); }
        }
    }
    static JSONObject profile(Context context) throws JSONException {
        return new JSONObject(context.getSharedPreferences("xpatch", 0).getString("profile", Profile.defaults().toString()));
    }
}
