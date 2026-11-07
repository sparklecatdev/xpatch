package dev.sparklecat.xpatch;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ApkSetTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void rejectsTraversalWithoutWritingOutsideDestination() throws Exception {
        File archive = temp.newFile(), directory = temp.newFolder();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../escape.apk")); zip.write(1); zip.closeEntry();
        }
        try { ApkSet.read(archive, directory); fail(); }
        catch (IOException expected) { assertEquals("Unsafe bundle entry.", expected.getMessage()); }
        assertEquals(0, Objects.requireNonNull(directory.list()).length);
    }
    @Test public void rejectsBundleWithoutBaseApk() throws Exception {
        File archive = temp.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(123); zip.closeEntry();
        }
        try { ApkSet.read(archive, temp.newFolder()); fail(); }
        catch (IOException expected) { assertEquals("No APKs found in this file.", expected.getMessage()); }
    }
    @Test public void archivePreservesEverySplitPayload() throws Exception {
        File base = temp.newFile(), split = temp.newFile(), archive = temp.newFile();
        Files.write(base.toPath(), new byte[]{1, 2}); Files.write(split.toPath(), new byte[]{3, 4, 5});
        ApkSet.archive(Arrays.asList(base, split), archive);
        try (ZipFile zip = new ZipFile(archive)) {
            assertEquals(2, zip.size());
            assertArrayEquals(new byte[]{1, 2}, zip.getInputStream(zip.getEntry("base.apk")).readAllBytes());
            assertArrayEquals(new byte[]{3, 4, 5}, zip.getInputStream(zip.getEntry("split-1.apk")).readAllBytes());
        }
    }
}
