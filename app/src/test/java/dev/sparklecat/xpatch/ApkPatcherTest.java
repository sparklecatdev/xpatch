package dev.sparklecat.xpatch;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ApkPatcherTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static void entry(ZipOutputStream zip, String name, byte[] value, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) { CRC32 crc = new CRC32(); crc.update(value); entry.setMethod(ZipEntry.STORED); entry.setSize(value.length); entry.setCrc(crc.getValue()); }
        zip.putNextEntry(entry); zip.write(value); zip.closeEntry();
    }
    @Test public void preservesPayloadAndAlignsNativeLibraries() throws Exception {
        File input = temp.newFile(), output = temp.newFile(), runtime = temp.newFile();
        byte[] original = "unchanged binary payload".getBytes(StandardCharsets.UTF_8);
        Files.write(runtime.toPath(), new byte[]{4, 5, 6});
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(input))) {
            entry(zip, "classes.dex", new byte[]{1}, false); entry(zip, "classes2.dex", new byte[]{2}, false);
            entry(zip, "AndroidManifest.xml", original, false); entry(zip, "resources.arsc", original, true);
            entry(zip, "lib/arm64-v8a/test.so", original, true); entry(zip, "META-INF/CERT.RSA", original, false);
            entry(zip, "META-INF/services/example", original, false);
        }
        ApkPatcher.rewrite(input, output, Collections.emptyMap(), runtime, "{}".getBytes(StandardCharsets.UTF_8));
        try (ZipFile zip = new ZipFile(output)) {
            for (String name : Arrays.asList("AndroidManifest.xml", "resources.arsc", "lib/arm64-v8a/test.so", "META-INF/services/example")) assertArrayEquals(original, zip.getInputStream(zip.getEntry(name)).readAllBytes());
            assertNull(zip.getEntry("META-INF/CERT.RSA")); assertNotNull(zip.getEntry("classes3.dex")); assertNotNull(zip.getEntry("assets/xpatch/profile.json"));
        }
        try (RandomAccessFile file = new RandomAccessFile(output, "r")) {
            while (Integer.reverseBytes(file.readInt()) == 0x04034b50) {
                byte[] header = new byte[26]; file.readFully(header);
                int flags = (header[2] & 255) | (header[3] & 255) << 8;
                long size = Integer.toUnsignedLong((header[14] & 255) | (header[15] & 255) << 8 | (header[16] & 255) << 16 | (header[17] & 255) << 24);
                int nameLength = (header[22] & 255) | (header[23] & 255) << 8, extraLength = (header[24] & 255) | (header[25] & 255) << 8;
                byte[] name = new byte[nameLength]; file.readFully(name); file.skipBytes(extraLength);
                String path = new String(name, StandardCharsets.UTF_8);
                if (path.endsWith(".so")) { assertEquals(0, file.getFilePointer() % 16384); return; }
                if ((flags & 8) != 0) {
                    // Find the next header without assuming a compressed entry has a local size.
                    boolean found = false;
                    while (file.getFilePointer() + 4 < file.length()) { long position = file.getFilePointer(); if (Integer.reverseBytes(file.readInt()) == 0x04034b50) { file.seek(position); found = true; break; } file.seek(position + 1); }
                    assertTrue(found);
                } else file.seek(file.getFilePointer() + size);
            }
        }
        fail("Native entry was not checked");
    }
    @Test public void boundedCopyRejectsOversizedInput() throws Exception {
        try { ApkPatcher.copy(new ByteArrayInputStream(new byte[10]), new ByteArrayOutputStream(), 9); fail(); } catch (IOException expected) { }
    }
}
