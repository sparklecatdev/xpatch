package dev.sparklecat.xpatch;

import com.android.apksig.ApkVerifier;
import com.android.apksig.internal.apk.AndroidBinXmlParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;

/** Local APK sets only; no APK downloads or package metadata services. */
final class ApkSet {
    static final class Part {
        final File file;
        final String split, sha;
        final long version;
        final byte[] certificate;
        Part(File file) throws Exception {
            this.file = file;
            String pkg = "", name = ""; long low = -1, high = 0;
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry entry = zip.getEntry("AndroidManifest.xml");
                if (entry == null) throw new IOException("An APK is missing its manifest.");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = zip.getInputStream(entry)) { ApkPatcher.copy(in, bytes, 8 * 1024 * 1024); }
                AndroidBinXmlParser xml = new AndroidBinXmlParser(ByteBuffer.wrap(bytes.toByteArray()));
                while (xml.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                    if (xml.getEventType() != AndroidBinXmlParser.EVENT_START_ELEMENT) continue;
                    if (!"manifest".equals(xml.getName())) throw new IOException("Invalid APK manifest.");
                    for (int i = 0; i < xml.getAttributeCount(); i++) {
                        String key = xml.getAttributeName(i);
                        if ("package".equals(key)) pkg = xml.getAttributeStringValue(i);
                        if ("split".equals(key)) name = xml.getAttributeStringValue(i);
                        if ("versionCode".equals(key)) low = Integer.toUnsignedLong(xml.getAttributeIntValue(i));
                        if ("versionCodeMajor".equals(key)) high = Integer.toUnsignedLong(xml.getAttributeIntValue(i));
                    }
                    break;
                }
            }
            if (!"com.twitter.android".equals(pkg) || low < 0) throw new IOException("Every APK must belong to X/Twitter.");
            split = name; version = high << 32 | low;
            ApkVerifier.Result result = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().size() != 1) throw new IOException("Input APK signature verification failed.");
            certificate = result.getSignerCertificates().get(0).getEncoded();
            sha = ApkPatcher.sha256(file);
        }
    }
    static List<Part> read(File source, File directory) throws Exception {
        List<Part> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(source)) {
            if (zip.getEntry("AndroidManifest.xml") != null) {
                File copy = new File(directory, "input-0.apk");
                try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(copy)) { ApkPatcher.copy(in, out, ApkPatcher.MAX_APK); }
                result.add(new Part(copy));
            } else {
                Set<String> names = new HashSet<>(); long total = 0;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement(); String name = entry.getName();
                    if (!names.add(name) || name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("Unsafe bundle entry.");
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                    if (result.size() >= 64 || entry.getSize() <= 0 || entry.getSize() > ApkPatcher.MAX_APK || (total += entry.getSize()) > 2 * ApkPatcher.MAX_APK) throw new IOException("APK set exceeds the size limit.");
                    File part = new File(directory, "input-" + result.size() + ".apk");
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(part)) { ApkPatcher.copy(in, out, entry.getSize()); }
                    result.add(new Part(part));
                }
            }
        }
        validate(result);
        result.sort(Comparator.comparing(part -> part.split));
        return result;
    }
    static void validate(List<Part> parts) throws IOException {
        if (parts.isEmpty()) throw new IOException("No APKs found in this file.");
        Part first = parts.get(0); Set<String> names = new HashSet<>();
        for (Part part : parts) {
            if (!names.add(part.split)) throw new IOException("Multiple variants of the same split. Choose an APK set for your device.");
            if (part.version != first.version || !Arrays.equals(part.certificate, first.certificate)) throw new IOException("Split versions or signing certificates do not match.");
        }
        if (!names.contains("")) throw new IOException("The base APK is missing.");
    }
    static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    static void archive(List<File> files, File output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            for (int i = 0; i < files.size(); i++) {
                zip.putNextEntry(new ZipEntry(i == 0 ? "base.apk" : "split-" + i + ".apk"));
                try (InputStream in = new FileInputStream(files.get(i))) { ApkPatcher.copy(in, zip, ApkPatcher.MAX_APK); }
                zip.closeEntry();
            }
        }
    }
}
