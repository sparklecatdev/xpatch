// Adapted from sparklecatdev/plural-star-mods. SPDX-License-Identifier: AGPL-3.0-only
package dev.sparklecat.xpatch;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.android.apksig.ApkSigner;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.security.auth.x500.X500Principal;

final class SigningIdentity {
    private static final String ALIAS = "sparklecat-xpatch-v1";
    static ApkSigner.SignerConfig load() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=sparklecat XPatch"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(1704067200000L)).setCertificateNotAfter(new Date(4102444800000L)).build());
            generator.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) store.getCertificate(ALIAS);
        return new ApkSigner.SignerConfig.Builder("sparklecat", key, Collections.singletonList(cert)).build();
    }
}
