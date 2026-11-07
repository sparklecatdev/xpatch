package dev.sparklecat.xpatch;

import com.android.apksig.ApkVerifier;
import java.io.*;
import java.security.MessageDigest;

// Adapted from sparklecatdev/plural-star-mods (AGPL-3.0).
public final class UpdateTrust {
    public static void checkDownload(File file, long expectedBytes, String expectedHash) throws Exception {
        if (expectedBytes < 1 || expectedBytes > UpdatePolicy.MAX_APK || file.length() != expectedBytes)
            throw new IOException("Update download was incomplete. Try again.");
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}") || !expectedHash.equals(ApkPatcher.sha256(file)))
            throw new IOException("The update checksum does not match. Nothing was installed.");
    }
    public static void checkPackage(String candidate, String installed) throws IOException {
        if (candidate == null || !candidate.equals(installed)) throw new IOException("This APK is for a different app.");
    }
    public static void checkSigner(File file, byte[][] installed) throws Exception {
        ApkVerifier.Result verified = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify();
        if (!verified.isVerified() || verified.getSignerCertificates().size() != 1)
            throw new IOException("The update's APK signature is invalid.");
        byte[] certificate = verified.getSignerCertificates().get(0).getEncoded();
        if (installed == null || installed.length != 1 || installed[0] == null || !MessageDigest.isEqual(installed[0], certificate))
            throw new IOException("The update uses a different signing key. Keep your current app and check the release notes.");
    }
}
