/*
 * Copyright (C) 2026 The DerpFest Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

public final class VerifiedBootState {
    private static final String TAG = "VerifiedBootState";

    /** Alias of the one-shot key used to read the TEE boot hash. Not attested with the keybox. */
    public static final String TEE_BOOT_HASH_ALIAS = "derpfest_boot_hash";

    private static final String REAL_VBMETA_DIGEST_PROP = "ro.boot.vbmeta.digest";
    private static final String REAL_VBMETA_PUBLIC_KEY_DIGEST_PROP =
            "ro.boot.vbmeta.public_key_digest";
    private static final int DIGEST_SIZE_BYTES = 32;

    /**
     * Chained vbmeta digest and AVB public-key digest of the mustang
     * {@code CP3A.260905.009} factory image. Init publishes a fresh random
     * digest on every boot, so a keybox certificate otherwise names a hash
     * Google has never shipped for this fingerprint.
     */
    private static final String FACTORY_BUILD_ID = "CP3A.260905.009";
    private static final String FACTORY_BOOT_HASH =
            "78b3088513cb7a0c0805f3ccacd0990adeb2a7fec589511938a3ea8909157e98";
    private static final String FACTORY_BOOT_KEY =
            "ca5c81da02b8debb054fc625135a7833698a9b32148e8a2ef619ebc62ab2e3d8";

    private static volatile String sDerivedBootHash;
    private static volatile String sDerivedBootKey;

    private VerifiedBootState() {}

    public static void ensureInitialized() {}

    /**
     * Publish the TEE's verified-boot hash when the bootloader left
     * {@code ro.boot.vbmeta.digest} unset. The property is replaceable, so
     * DroidGuard and the hardware leaf then name the same measurement.
     */
    public static void publishTeeBootHash() {
        if (isValidDigestHex(SystemProperties.get(REAL_VBMETA_DIGEST_PROP, ""))) {
            return;
        }
        byte[] hash = readTeeBootHash();
        if (hash == null || hash.length != DIGEST_SIZE_BYTES) {
            Log.w(TAG, "TEE boot hash unavailable");
            return;
        }
        try {
            SystemProperties.set(REAL_VBMETA_DIGEST_PROP, toHex(hash));
            Log.i(TAG, "Published TEE vbmeta digest");
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to publish TEE vbmeta digest", e);
        }
    }

    private static byte[] readTeeBootHash() {
        boolean created = false;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(
                    TEE_BOOT_HASH_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[DIGEST_SIZE_BYTES])
                    .build());
            created = true;
            generator.generateKeyPair();

            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Certificate[] chain = keyStore.getCertificateChain(TEE_BOOT_HASH_ALIAS);
            if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                Log.w(TAG, "TEE boot-hash probe returned no certificate");
                return null;
            }
            return KeyboxChainGenerator.hardwareBootHash(chain[0].getEncoded());
        } catch (Exception e) {
            Log.w(TAG, "Failed to read boot hash from TEE", e);
            return null;
        } finally {
            if (created) {
                try {
                    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                    keyStore.load(null);
                    keyStore.deleteEntry(TEE_BOOT_HASH_ALIAS);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete TEE boot-hash probe key", e);
                }
            }
        }
    }

    public static String getVerifiedBootHashHex() {
        ensureInitialized();
        return getEffectiveDigestHex(REAL_VBMETA_DIGEST_PROP);
    }

    public static String getVerifiedBootKeyHex() {
        ensureInitialized();
        return getEffectiveDigestHex(REAL_VBMETA_PUBLIC_KEY_DIGEST_PROP);
    }

    public static byte[] getVerifiedBootHashBytes() {
        return hexToBytes(getVerifiedBootHashHex());
    }

    public static byte[] getVerifiedBootKeyBytes() {
        return hexToBytes(getVerifiedBootKeyHex());
    }

    /** Boot hash for a keybox certificate. Matches {@link #getAttestationBootKeyHex}. */
    public static String getAttestationBootHashHex() {
        if (FACTORY_BUILD_ID.equals(Build.ID)) {
            return FACTORY_BOOT_HASH;
        }
        return getVerifiedBootHashHex();
    }

    /** Verified-boot key digest paired with {@link #getAttestationBootHashHex}. */
    public static String getAttestationBootKeyHex() {
        if (FACTORY_BUILD_ID.equals(Build.ID)) {
            return FACTORY_BOOT_KEY;
        }
        return getVerifiedBootKeyHex();
    }

    public static byte[] getAttestationBootHashBytes() {
        return hexToBytes(getAttestationBootHashHex());
    }

    public static byte[] getAttestationBootKeyBytes() {
        return hexToBytes(getAttestationBootKeyHex());
    }

    private static String getEffectiveDigestHex(String property) {
        String primary = SystemProperties.get(property, "");
        if (isValidDigestHex(primary)) {
            return primary.toLowerCase();
        }

        String derived = deriveFromKeybox(property);
        if (isValidDigestHex(derived)) {
            return derived.toLowerCase();
        }

        return "0".repeat(DIGEST_SIZE_BYTES * 2);
    }

    /**
     * When the vbmeta properties are missing, derive a stable boot key and boot hash
     * from the keybox leaf the way a hardware root of trust would be bound to it.
     */
    private static String deriveFromKeybox(String property) {
        boolean bootHash = REAL_VBMETA_DIGEST_PROP.equals(property);
        String cached = bootHash ? sDerivedBootHash : sDerivedBootKey;
        if (isValidDigestHex(cached)) {
            return cached;
        }
        try {
            List<Certificate> chain = null;
            try {
                chain = KeyboxUtils.getCertificateChain(KeyProperties.KEY_ALGORITHM_EC);
            } catch (Exception ignored) {
            }
            if (chain == null || chain.isEmpty()) {
                chain = KeyboxUtils.getCertificateChain(KeyProperties.KEY_ALGORITHM_RSA);
            }
            if (chain == null || chain.isEmpty()) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (bootHash) {
                digest.update("boothash".getBytes(StandardCharsets.UTF_8));
            }
            String hex = toHex(digest.digest(chain.get(0).getEncoded()));
            if (bootHash) {
                sDerivedBootHash = hex;
            } else {
                sDerivedBootKey = hex;
            }
            return hex;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static boolean isValidDigestHex(String value) {
        if (value == null || value.length() != DIGEST_SIZE_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9')
                    && (c < 'a' || c > 'f')
                    && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[DIGEST_SIZE_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return bytes;
    }
}
