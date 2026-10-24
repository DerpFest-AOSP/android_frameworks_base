/*
 * Copyright (C) 2022-2024 Paranoid Android
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @hide
 */
public class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Settings toggle. Default on, matching the spoofing screen.
    private static final String PROP_PI_SPOOF = "persist.sys.pihooks.pi";
    // Shell kill switch. Default off.
    private static final String PROP_DISABLE_GMS_PROPS = "persist.sys.pihooks.disable.gms_props";

    private static final String DATA_FILE = "gms_certified_props.json";

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";
    private static final String PACKAGE_NETFLIX = "com.netflix.mediaclient";
    private static final String PACKAGE_GPHOTOS = "com.google.android.apps.photos";

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static final String FEATURE_NEXUS_PRELOAD =
            "com.google.android.apps.photos.NEXUS_PRELOAD";

    private static final Map<String, String> sPixelOneProps = Map.of(
        "PRODUCT", "sailfish",
        "DEVICE", "sailfish",
        "MANUFACTURER", "Google",
        "BRAND", "google",
        "MODEL", "Pixel",
        "FINGERPRINT", "google/sailfish/sailfish:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final Set<String> sPixelFeatures = Set.of(
        "PIXEL_2017_EXPERIENCE",
        "PIXEL_2017_PRELOAD",
        "PIXEL_2018_EXPERIENCE",
        "PIXEL_2018_PRELOAD",
        "PIXEL_2019_EXPERIENCE",
        "PIXEL_2019_MIDYEAR_EXPERIENCE",
        "PIXEL_2019_MIDYEAR_PRELOAD",
        "PIXEL_2019_PRELOAD",
        "PIXEL_2020_EXPERIENCE",
        "PIXEL_2020_MIDYEAR_EXPERIENCE",
        "PIXEL_2021_MIDYEAR_EXPERIENCE"
    );

    private static final Set<String> sTensorFeatures = Set.of(
        "PIXEL_2021_EXPERIENCE",
        "PIXEL_2022_EXPERIENCE",
        "PIXEL_2022_MIDYEAR_EXPERIENCE",
        "PIXEL_2023_EXPERIENCE",
        "PIXEL_2023_MIDYEAR_EXPERIENCE",
        "PIXEL_2024_EXPERIENCE",
        "PIXEL_2024_MIDYEAR_EXPERIENCE",
        "PIXEL_2025_EXPERIENCE",
        "PIXEL_2025_MIDYEAR_EXPERIENCE"
    );

    private static volatile String sStockFp, sNetflixModel;
    private static volatile boolean sNativePropsApplied;

    private static volatile String sProcessName;
    private static volatile boolean sIsGms, sIsFinsky, sIsPhotos;

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        final Resources res = context.getResources();
        if (res == null) {
            Log.e(TAG, "Null resources");
            return;
        }

        sStockFp = res.getString(R.string.config_stockFingerprint);
        sNetflixModel = res.getString(R.string.config_netflixSpoofModel);

        sProcessName = processName;
        sIsGms = packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_UNSTABLE);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);
        sIsPhotos = packageName.equals(PACKAGE_GPHOTOS)
                && SystemProperties.getBoolean("persist.sys.pihooks.gphotos", true);

        /* Set Certified Properties for GMSCore
         * Set Stock Fingerprint for ARCore
         * Set custom model for Netflix
         * Set Pixel XL for Google Photos
         */
        if (sIsGms || sIsFinsky) {
            if (!android.os.Process.isIsolated()) {
                setPlayIntegrityProps(context);
            } else {
                dlog("Not setting Play Integrity props in isolated process");
            }
        } else if (!sStockFp.isEmpty() && packageName.equals(PACKAGE_ARCORE)) {
            dlog("Setting stock fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sStockFp);
        } else if (sIsPhotos) {
            dlog("Spoofing Pixel 1 for Google Photos");
            sPixelOneProps.forEach((PropImitationHooks::setPropValue));
        } else if (!sNetflixModel.isEmpty() && packageName.equals(PACKAGE_NETFLIX)) {
            dlog("Setting model to " + sNetflixModel + " for Netflix");
            setPropValue("MODEL", sNetflixModel);
        }
    }

    private static void setPropValue(String key, String value) {
        try {
            dlog("Setting prop " + key + " to " + value.toString());
            Class clazz = Build.class;
            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            // Cast the value to int if it's an integer field, otherwise string.
            field.set(null, field.getType().equals(Integer.TYPE) ? Integer.parseInt(value) : value);
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static boolean isPiSpoofEnabled() {
        return SystemProperties.getBoolean(PROP_PI_SPOOF, true)
                && !SystemProperties.getBoolean(PROP_DISABLE_GMS_PROPS, false);
    }

    private static void setPlayIntegrityProps(Context context) {
        if (!isPiSpoofEnabled()) {
            dlog("GMS prop imitation is disabled by user");
            return;
        }

        // Guard: isolated processes cannot access content providers (Settings.*).
        if (android.os.Process.isIsolated()) {
            dlog("Skipping setPlayIntegrityProps in isolated process");
            return;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> nativeProps = new LinkedHashMap<>();
        if (!loadCertifiedProps(context, fields, nativeProps)) {
            dlog("Certified props are not set");
            return;
        }

        expandFingerprint(fields);
        String initialSdk = resolveDeviceInitialSdk(fields.get("DEVICE"),
                firstNonEmpty(fields.get("VERSION.DEVICE_INITIAL_SDK_INT"),
                        fields.get("DEVICE_INITIAL_SDK_INT")));
        fields.put("VERSION.DEVICE_INITIAL_SDK_INT", initialSdk);
        // The attestation leaf and DroidGuard both compare this date to the
        // fingerprint. A missing patch leaves the real device date in place.
        ensureSecurityPatch(fields);

        final boolean was = isGmsAddAccountActivityOnTop();
        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean is = isGmsAddAccountActivityOnTop();
                if (is ^ was) {
                    dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + was +
                            ", killing myself!"); // process will restart automatically later
                    Process.killProcess(Process.myPid());
                }
            }
        };

        if (!was) {
            dlog("Spoofing build for GMS / Finsky");
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                setPropValue(entry.getKey(), entry.getValue());
            }
            // Keystore reads these instead of Build.DEVICE. They are filled from
            // ro.product.*_for_attestation when the class loads, which is before
            // this spoof runs, so the certificate otherwise keeps this device.
            setPropValue("DEVICE_FOR_ATTESTATION", fields.get("DEVICE"));
            setPropValue("PRODUCT_FOR_ATTESTATION", fields.get("PRODUCT"));
            setPropValue("MODEL_FOR_ATTESTATION", fields.get("MODEL"));
            setPropValue("BRAND_FOR_ATTESTATION", fields.get("BRAND"));
            setPropValue("MANUFACTURER_FOR_ATTESTATION", fields.get("MANUFACTURER"));
            if (sIsGms) {
                spoofNativeProperties(fields, nativeProps, initialSdk);
            }
        } else {
            dlog("Skip spoofing build for GMS / Finsky, because GmsAddAccountActivityOnTop");
        }

        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    private static boolean loadCertifiedProps(Context context, Map<String, String> fields,
            Map<String, String> nativeProps) {
        String savedProps = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.PIF_DATA);
        if (TextUtils.isEmpty(savedProps)) {
            savedProps = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.FETCHED_PIF);
        }

        if (TextUtils.isEmpty(savedProps)) {
            dlog("Parsing props locally - fetched pif / user provided pif unavailable");
            for (String entry : context.getResources().getStringArray(
                    R.array.config_certifiedBuildProperties)) {
                addCertifiedEntry(fields, nativeProps, entry);
            }
            return !fields.isEmpty() || !nativeProps.isEmpty();
        }

        dlog("Parsing props fetched / provided by user");
        try {
            JSONObject parsedProps = new JSONObject(savedProps);
            Iterator<String> keys = parsedProps.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object raw = parsedProps.opt(key);
                if (raw == null || raw == JSONObject.NULL
                        || raw instanceof JSONObject || raw instanceof JSONArray) {
                    continue;
                }
                addCertifiedEntry(fields, nativeProps, key + ":" + raw);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON data", e);
            dlog("Parsing props locally as fallback");
            fields.clear();
            nativeProps.clear();
            for (String entry : context.getResources().getStringArray(
                    R.array.config_certifiedBuildProperties)) {
                addCertifiedEntry(fields, nativeProps, entry);
            }
        }
        return !fields.isEmpty() || !nativeProps.isEmpty();
    }

    private static void addCertifiedEntry(Map<String, String> fields,
            Map<String, String> nativeProps, String entry) {
        final String[] fieldAndProp = entry.split(":", 2);
        if (fieldAndProp.length != 2 || fieldAndProp[0].isEmpty()) {
            Log.e(TAG, "Invalid entry in certified props: " + entry);
            return;
        }
        String key = fieldAndProp[0];
        String value = fieldAndProp[1];
        if (key.contains(".") || key.startsWith("*")) {
            nativeProps.put(key, value);
            return;
        }
        switch (key) {
            case "DEVICE_INITIAL_SDK_INT":
                fields.put("VERSION.DEVICE_INITIAL_SDK_INT", value);
                break;
            case "SECURITY_PATCH":
                fields.put("VERSION.SECURITY_PATCH", value);
                break;
            case "RELEASE":
                fields.put("VERSION.RELEASE", value);
                break;
            case "INCREMENTAL":
                fields.put("VERSION.INCREMENTAL", value);
                break;
            default:
                fields.put(key, value);
                break;
        }
    }

    /**
     * Fills the individual build fields encoded in a fingerprint
     * ({@code brand/product/device:release/id/incremental:type/tags}).
     */
    private static void expandFingerprint(Map<String, String> fields) {
        String fingerprint = fields.get("FINGERPRINT");
        if (TextUtils.isEmpty(fingerprint)) {
            return;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i++) {
            char ch = fingerprint.charAt(i);
            if (ch == '/' || ch == ':') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());

        String[] keys = {
            "BRAND", "PRODUCT", "DEVICE", "VERSION.RELEASE", "ID",
            "VERSION.INCREMENTAL", "TYPE", "TAGS"
        };
        for (int i = 0; i < keys.length && i < parts.size(); i++) {
            if (!parts.get(i).isEmpty()) {
                fields.put(keys[i], parts.get(i));
            }
        }
    }

    /**
     * Launch SDK of known Pixel codenames. A configured value is only used when the
     * device is not in this table, because a stale first-api-level fails the
     * fingerprint cross-check (mustang is 36, not the previously shipped 32).
     */
    private static String resolveDeviceInitialSdk(String device, String configuredSdk) {
        if (device != null) {
            switch (device) {
                case "oriole":
                case "raven":
                    return "31";
                case "bluejay":
                    return "32";
                case "panther":
                case "cheetah":
                case "lynx":
                case "felix":
                case "tangorpro":
                    return "33";
                case "shiba":
                case "husky":
                case "akita":
                case "tokay":
                case "caiman":
                case "komodo":
                case "comet":
                    return "34";
                case "tegu":
                case "stallion":
                    return "35";
                case "frankel":
                case "blazer":
                case "mustang":
                case "rango":
                    return "36";
                case "cubs":
                case "grizzly":
                case "kodiak":
                case "yogi":
                    return "37";
                default:
                    break;
            }
        }
        if (!TextUtils.isEmpty(configuredSdk)) {
            return configuredSdk;
        }
        return "32";
    }

    private static void spoofNativeProperties(Map<String, String> fields,
            Map<String, String> configured, String initialSdk) {
        if (sNativePropsApplied) {
            return;
        }

        Map<String, String> props = new LinkedHashMap<>();
        if (configured != null) {
            props.putAll(configured);
        }
        putIfAbsent(props, "init.svc.adbd", "stopped");
        putIfAbsent(props, "sys.usb.state", "mtp");
        putIfAbsent(props, "ro.boot.flash.locked", "1");
        putIfAbsent(props, "ro.boot.vbmeta.device_state", "locked");
        putIfAbsent(props, "ro.boot.verifiedbootstate", "green");
        putIfAbsent(props, "ro.boot.vbmeta.digest",
                VerifiedBootState.getAttestationBootHashHex());
        putIfAbsent(props, "ro.boot.vbmeta.public_key_digest",
                VerifiedBootState.getAttestationBootKeyHex());
        putIfAbsent(props, "ro.boot.veritymode", "enforcing");
        putIfAbsent(props, "ro.debuggable", "0");
        putIfAbsent(props, "ro.secure", "1");
        putIfAbsent(props, "ro.build.type", fields.get("TYPE"));
        putIfAbsent(props, "ro.build.tags", fields.get("TAGS"));
        putIfAbsent(props, "ro.product.first_api_level", initialSdk);
        putIfAbsent(props, "ro.board.first_api_level", initialSdk);
        putIfAbsent(props, "ro.build.fingerprint", fields.get("FINGERPRINT"));
        putIfAbsent(props, "ro.build.id", fields.get("ID"));
        putIfAbsent(props, "ro.build.display.id", fields.get("ID"));
        putIfAbsent(props, "ro.product.device", fields.get("DEVICE"));
        putIfAbsent(props, "ro.product.name", fields.get("PRODUCT"));
        putIfAbsent(props, "ro.build.product", fields.get("PRODUCT"));
        putIfAbsent(props, "ro.product.brand", fields.get("BRAND"));
        putIfAbsent(props, "ro.product.manufacturer", fields.get("MANUFACTURER"));
        putIfAbsent(props, "ro.product.model", fields.get("MODEL"));
        putIfAbsent(props, "ro.product.device_for_attestation", fields.get("DEVICE"));
        putIfAbsent(props, "ro.product.name_for_attestation", fields.get("PRODUCT"));
        putIfAbsent(props, "ro.product.model_for_attestation", fields.get("MODEL"));
        putIfAbsent(props, "ro.product.brand_for_attestation", fields.get("BRAND"));
        putIfAbsent(props, "ro.product.manufacturer_for_attestation",
                fields.get("MANUFACTURER"));
        putIfAbsent(props, "ro.hardware", fields.get("HARDWARE"));
        putIfExists(props, "ro.boot.hardware", fields.get("HARDWARE"));
        putIfExists(props, "ro.product.board", fields.get("DEVICE"));
        putIfAbsent(props, "ro.build.version.release", fields.get("VERSION.RELEASE"));
        putIfAbsent(props, "ro.build.version.incremental", fields.get("VERSION.INCREMENTAL"));
        String securityPatch = firstNonEmpty(fields.get("VERSION.SECURITY_PATCH"),
                fields.get("SECURITY_PATCH"));
        putIfAbsent(props, "ro.build.version.security_patch", securityPatch);
        // Vendor, bootimage and system partitions each have their own patch prop.
        // DroidGuard rejects the fingerprint when any of them still shows the device date.
        putIfAbsent(props, "*security_patch", securityPatch);
        // Partition props stay readable as this device. DroidGuard compares them
        // with the spoofed fingerprint, so each one that exists is overwritten.
        spoofPartitionIdentity(props, fields);
        // A leading '!' drops the property from lookup and enumeration. The
        // name itself is the custom-ROM signal, so rewriting the value is not
        // enough.
        props.put("!ro.derpfest.", "1");
        props.put("!ro.lineage.", "1");

        props.entrySet().removeIf(entry -> TextUtils.isEmpty(entry.getValue()));
        if (props.isEmpty()) {
            return;
        }

        String[] keys = props.keySet().toArray(new String[0]);
        String[] values = props.values().toArray(new String[0]);
        try {
            boolean ok = nativeSpoofProperties(keys, values, DEBUG);
            if (ok) {
                sNativePropsApplied = true;
                Log.i(TAG, "Property area virtualization active (" + keys.length + " rules)");
            } else {
                Log.w(TAG, "Property area virtualization returned false");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to spoof native properties", t);
        }
    }

    private static void putIfAbsent(Map<String, String> props, String key, String value) {
        if (!TextUtils.isEmpty(value)) {
            props.putIfAbsent(key, value);
        }
    }

    /**
     * Overwrite partition identity props that already exist. A vendor fingerprint
     * such as {@code lineage_komodo} next to a spoofed Pixel fingerprint fails
     * the cross-check even when the keybox chain is valid.
     */
    private static void spoofPartitionIdentity(Map<String, String> props,
            Map<String, String> fields) {
        String product = fields.get("PRODUCT");
        String type = firstNonEmpty(fields.get("TYPE"), "user");
        String release = fields.get("VERSION.RELEASE");
        String id = fields.get("ID");
        String incremental = fields.get("VERSION.INCREMENTAL");
        String tags = firstNonEmpty(fields.get("TAGS"), "release-keys");
        String description = null;
        if (!TextUtils.isEmpty(product) && !TextUtils.isEmpty(release)
                && !TextUtils.isEmpty(id) && !TextUtils.isEmpty(incremental)) {
            description = product + "-" + type + " " + release + " " + id + " "
                    + incremental + " " + tags;
        }
        String flavor = TextUtils.isEmpty(product) ? null : product + "-" + type;
        putIfExists(props, "ro.build.description", description);
        putIfExists(props, "ro.build.flavor", flavor);

        String fingerprint = fields.get("FINGERPRINT");
        for (String part : new String[] {
                "vendor", "odm", "product", "system", "system_ext", "system_dlkm",
                "bootimage", "vendor_dlkm"}) {
            putIfExists(props, "ro." + part + ".build.fingerprint", fingerprint);
            putIfExists(props, "ro." + part + ".build.description", description);
            putIfExists(props, "ro." + part + ".build.id", id);
            putIfExists(props, "ro.product." + part + ".device", fields.get("DEVICE"));
            putIfExists(props, "ro.product." + part + ".name", fields.get("PRODUCT"));
            putIfExists(props, "ro.product." + part + ".brand", fields.get("BRAND"));
            putIfExists(props, "ro.product." + part + ".manufacturer",
                    fields.get("MANUFACTURER"));
            putIfExists(props, "ro.product." + part + ".model", fields.get("MODEL"));
        }
    }

    private static void putIfExists(Map<String, String> props, String key, String value) {
        if (TextUtils.isEmpty(value) || TextUtils.isEmpty(SystemProperties.get(key, ""))) {
            return;
        }
        props.putIfAbsent(key, value);
    }

    private static String firstNonEmpty(String primary, String fallback) {
        return !TextUtils.isEmpty(primary) ? primary : fallback;
    }

    /**
     * Pixel build IDs encode the security patch as {@code YYMMDD}
     * ({@code CP3A.260905.009} is 2026-09-05). Use that when the profile
     * does not carry an explicit patch.
     */
    private static void ensureSecurityPatch(Map<String, String> fields) {
        if (!TextUtils.isEmpty(fields.get("VERSION.SECURITY_PATCH"))
                || !TextUtils.isEmpty(fields.get("SECURITY_PATCH"))) {
            return;
        }
        String patch = securityPatchFromBuildId(fields.get("ID"));
        if (patch != null) {
            fields.put("VERSION.SECURITY_PATCH", patch);
        }
    }

    private static String securityPatchFromBuildId(String buildId) {
        if (TextUtils.isEmpty(buildId)) {
            return null;
        }
        int firstDot = buildId.indexOf('.');
        int secondDot = firstDot >= 0 ? buildId.indexOf('.', firstDot + 1) : -1;
        if (firstDot < 0 || secondDot != firstDot + 7) {
            return null;
        }
        String yymmdd = buildId.substring(firstDot + 1, secondDot);
        for (int i = 0; i < yymmdd.length(); i++) {
            if (!Character.isDigit(yymmdd.charAt(i))) {
                return null;
            }
        }
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day = Integer.parseInt(yymmdd.substring(4, 6));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return "20" + yymmdd.substring(0, 2) + "-"
                + yymmdd.substring(2, 4) + "-" + yymmdd.substring(4, 6);
    }

    private static native boolean nativeSpoofProperties(String[] keys, String[] values,
            boolean debug);

    private static String readFromFile(File file) {
        StringBuilder content = new StringBuilder();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file", e);
            }
        }
        return content.toString();
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    public static boolean shouldBypassTaskPermission(Context context) {
        if (!isPiSpoofEnabled()) {
            return false;
        }

        // GMS and Finsky don't have MANAGE_ACTIVITY_TASKS permission.
        // Finsky hits getFocusedRootTaskInfo() once certified props are spoofed for it.
        final int callingUid = Binder.getCallingUid();
        try {
            int gmsUid = context.getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            int finskyUid = context.getPackageManager()
                    .getApplicationInfo(PACKAGE_FINSKY, 0).uid;
            dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid
                    + " finskyUid:" + finskyUid
                    + " callingUid:" + callingUid);
            return callingUid == gmsUid || callingUid == finskyUid;
        } catch (Exception e) {
            Log.e(TAG, "shouldBypassTaskPermission: unable to get gms/finsky uid", e);
            return false;
        }
    }

    public static boolean hasSystemFeature(String name, boolean has) {
        if (sIsPhotos) {
            if (has && (sPixelFeatures.stream().anyMatch(name::contains)
                    || sTensorFeatures.stream().anyMatch(name::contains))) {
                dlog("Blocked system feature " + name + " for Google Photos");
                has = false;
            } else if (!has && name.equalsIgnoreCase(FEATURE_NEXUS_PRELOAD)) {
                dlog("Enabled system feature " + name + " for Google Photos");
                has = true;
            }
        }
        return has;
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
