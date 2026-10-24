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
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.List;

public final class VerifiedBootState {
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
