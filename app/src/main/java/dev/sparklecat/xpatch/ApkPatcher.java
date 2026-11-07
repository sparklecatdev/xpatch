// ZIP alignment and signing adapted from sparklecatdev/plural-star-mods.
// SPDX-License-Identifier: AGPL-3.0-only
package dev.sparklecat.xpatch;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class ApkPatcher {
    public static final long MAX_APK = 1024L * 1024 * 1024;
    public interface Progress { void update(String message); }
    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) != -1) md.update(bytes, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    public static void patch(File source, File output, File work, DexEngine.Plan plan, File runtime, byte[] profile, Map<String, byte[]> notices, ApkSigner.SignerConfig signer, Progress progress) throws Exception {
        File unsigned = new File(work, "unsigned.apk"), signed = new File(work, "signed.apk");
        // A previous build must never be offered as the result of a failed new build.
        if (output.exists() && !output.delete()) throw new IOException("Could not remove previous output.");
        try {
            progress.update("Applying independent bytecode hooks…");
            Map<String, File> replacements = plan == null ? Collections.emptyMap() : DexEngine.apply(source, work, plan);
            progress.update("Rebuilding and aligning APK…");
            rewrite(source, unsigned, replacements, runtime, profile, notices);
            progress.update("Signing with your device key…");
            new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(signed)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false).build().sign();
            ApkVerifier.Result check = new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(26).build().verify();
            if (!check.isVerified()) throw new IOException("Generated APK failed signature verification: " + check.getErrors());
            if (!signed.renameTo(output)) throw new IOException("Could not publish the signed APK.");
            progress.update("Signed APK ready");
        } finally { unsigned.delete(); signed.delete(); }
    }
    static boolean signatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-"));
    }
    static void rewrite(File source, File output, Map<String, File> replacements, File runtime, byte[] profile) throws IOException {
        rewrite(source, output, replacements, runtime, profile, Collections.emptyMap());
    }
    static void rewrite(File source, File output, Map<String, File> replacements, File runtime, byte[] profile, Map<String, byte[]> notices) throws IOException {
        try (ZipFile input = new ZipFile(source); CountingStream count = new CountingStream(new BufferedOutputStream(new FileOutputStream(output))); ZipOutputStream zip = new ZipOutputStream(count)) {
            List<ZipEntry> dex = runtime == null ? Collections.emptyList() : DexEngine.dexEntries(input);
            Set<String> dexNames = new HashSet<>();
            for (ZipEntry entry : dex) dexNames.add(entry.getName());
            Set<String> seen = new HashSet<>();
            long total = 0;
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!seen.add(name)) throw new IOException("Duplicate APK entry.");
                if (name.startsWith("assets/xpatch/")) throw new IOException("Already patched APK.");
                if (signatureEntry(name)) continue;
                if (entry.getSize() < 0 || entry.getSize() > MAX_APK || (total += entry.getSize()) > 3 * MAX_APK) throw new IOException("APK exceeds the expanded size limit.");
                ZipEntry next = new ZipEntry(name);
                next.setTime(946684800000L);
                File replacement = replacements.get(name);
                if (replacement == null) {
                    next.setMethod(entry.getMethod());
                    if (entry.getMethod() == ZipEntry.STORED) {
                        next.setSize(entry.getSize()); next.setCrc(entry.getCrc());
                        int alignment = name.startsWith("lib/") && name.endsWith(".so") ? 16384 : 4;
                        int pad = (int) ((alignment - (count.count + 30 + name.getBytes(StandardCharsets.UTF_8).length) % alignment) % alignment);
                        if (pad > 0) {
                            if (pad < 4) pad += alignment;
                            byte[] extra = new byte[pad]; extra[0] = 0x35; extra[1] = (byte) 0xD9;
                            extra[2] = (byte) (pad - 4); extra[3] = (byte) ((pad - 4) >> 8);
                            next.setExtra(extra);
                        }
                    }
                }
                zip.putNextEntry(next);
                try (InputStream in = replacement == null ? input.getInputStream(entry) : new FileInputStream(replacement)) { copy(in, zip, MAX_APK); }
                zip.closeEntry();
            }
            for (Map.Entry<String, File> replacement : replacements.entrySet()) {
                String name = replacement.getKey();
                if (seen.contains(name)) continue;
                if (!name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) throw new IOException("Invalid additional DEX name.");
                if (replacement.getValue().length() > MAX_APK || (total += replacement.getValue().length()) > 3 * MAX_APK) throw new IOException("APK exceeds the expanded size limit.");
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(replacement.getValue())) { copy(in, zip, MAX_APK); }
                zip.closeEntry();
                dexNames.add(name);
            }
            if (runtime == null) return;
            if (dex.isEmpty()) throw new IOException("APK has no code.");
            zip.putNextEntry(new ZipEntry(DexEngine.nextDexName(dexNames)));
            try (InputStream in = new FileInputStream(runtime)) { copy(in, zip, 8L * 1024 * 1024); }
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/xpatch/profile.json")); zip.write(profile); zip.closeEntry();
            for (Map.Entry<String, byte[]> notice : notices.entrySet()) {
                if (!notice.getKey().matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid notice name.");
                zip.putNextEntry(new ZipEntry("assets/xpatch/" + notice.getKey())); zip.write(notice.getValue()); zip.closeEntry();
            }
        }
    }
    public static long copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] bytes = new byte[65536]; long total = 0; int n;
        while ((n = in.read(bytes)) != -1) {
            total += n; if (total > max) throw new IOException("File exceeds the size limit.");
            out.write(bytes, 0, n);
        }
        return total;
    }
    static final class CountingStream extends FilterOutputStream {
        long count;
        CountingStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
