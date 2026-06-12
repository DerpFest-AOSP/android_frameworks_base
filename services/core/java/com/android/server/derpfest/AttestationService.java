/*
 * Copyright (C) 2024 The LeafOS Project
 * Copyright (C) 2024 The Clover Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.derpfest;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import android.system.keystore2.KeyDescriptor;

import com.android.internal.security.keybox.AttestationCertificates;
import com.android.internal.security.keybox.IKeyboxAttestationService;
import com.android.internal.security.keybox.KeyboxKeyParameters;

import com.android.internal.util.KeyboxChainGenerator;
import com.android.internal.util.KeyboxChainGenerator.KeyGenParameters;
import com.android.internal.util.KeyboxUtils;
import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

public final class AttestationService extends SystemService {

    private static final String TAG = AttestationService.class.getSimpleName();
    private static final String API =
            Resources.getSystem().getString(com.android.internal.R.string.config_pifUpdateUrl);

    private static final long INITIAL_DELAY = 0;
    private static final long INTERVAL = 8;

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Boolean sDisableGmsProps = SystemProperties.getBoolean(
            "persist.sys.pihooks.disable.gms_props", false);

    private final Context mContext;
    private final ScheduledExecutorService mScheduler;
    private final KeyboxAttestationServiceImpl mKeyboxAttestationService =
            new KeyboxAttestationServiceImpl();
    private final ConnectivityManager mConnectivityManager;
    private final FetchGmsCertifiedProps mFetchRunnable;

    private boolean mPendingUpdate;

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mFetchRunnable = new FetchGmsCertifiedProps();
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    @Override
    public void onStart() {
        publishBinderService("android.security.keybox", mKeyboxAttestationService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (!sDisableGmsProps
                && isPackageInstalled(mContext, "com.google.android.gms")
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling periodic fetch every " + INTERVAL + " hours");
            mScheduler.scheduleAtFixedRate(
                    mFetchRunnable, INITIAL_DELAY, INTERVAL, TimeUnit.HOURS);
        }
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        if (packageName == null) {
            return true;
        }
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String fetchProps() {
        try {
            URL url = new URI(API).toURL();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            try {
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                }
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        }
    }

    private static boolean isValidProps(String props) {
        if (TextUtils.isEmpty(props)) {
            return false;
        }

        try {
            return new JSONObject(props).length() > 0;
        } catch (JSONException e) {
            Log.e(TAG, "Fetched PIF data is not valid JSON", e);
            return false;
        }
    }

    private boolean isInternetConnected() {
        Network network = mConnectivityManager.getActiveNetwork();
        if (network == null) {
            dlog("No active network");
            return false;
        }
        NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
        boolean connected = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        dlog("isInternetConnected(): " + connected);
        return connected;
    }

    private void registerNetworkCallback() {
        mConnectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Connectivity established");
                if (mPendingUpdate) {
                    Log.i(TAG, "Pending fetch detected. Executing now");
                    mScheduler.schedule(mFetchRunnable, 0, TimeUnit.SECONDS);
                    mPendingUpdate = false;
                }
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Connectivity lost");
            }
        });
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private class FetchGmsCertifiedProps implements Runnable {
        @Override
        public void run() {
            try {
                dlog("FetchGmsCertifiedProps started");

                if (!isInternetConnected()) {
                    if (!mPendingUpdate) {
                        Log.w(TAG, "Internet unavailable, deferring update until network is restored");
                        mPendingUpdate = true;
                    }
                    return;
                }

                String savedProps = Settings.Secure.getString(
                        mContext.getContentResolver(), Settings.Secure.FETCHED_PIF);
                String props = fetchProps();

                if (isValidProps(props) && !TextUtils.equals(savedProps, props)) {
                    dlog("Found new props");
                    Settings.Secure.putString(
                            mContext.getContentResolver(), Settings.Secure.FETCHED_PIF, props);
                    dlog("FetchGmsCertifiedProps completed");
                } else {
                    dlog("No change in props");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedProps", e);
            }
        }
    }

    private static class KeyboxAttestationServiceImpl extends IKeyboxAttestationService.Stub {
        private static AttestationCertificates emptyCertificates() {
            AttestationCertificates certs = new AttestationCertificates();
            certs.privateKey = new byte[0];
            certs.certificate = new byte[0];
            certs.certificateChain = new byte[0];
            return certs;
        }

        private static AttestationCertificates buildCertificates(List<Certificate> chain,
                byte[] privateKey) throws Exception {
            AttestationCertificates certs = emptyCertificates();
            certs.privateKey = privateKey != null ? privateKey : certs.privateKey;
            certs.certificate = chain.get(0).getEncoded();
            if (chain.size() > 1) {
                certs.certificateChain = KeyboxUtils.toCertificateChainBytes(
                        chain.subList(1, chain.size()).toArray(new Certificate[0]));
            }
            return certs;
        }

        private void enforcePermission() {
            int callingUid = Binder.getCallingUid();
            if (callingUid != Process.KEYSTORE_UID) {
                throw new SecurityException("Only Keystore daemon can call this service");
            }
        }

        private static KeyDescriptor buildDescriptor(String alias, int domain, long nspace) {
            KeyDescriptor descriptor = new KeyDescriptor();
            descriptor.alias = alias;
            descriptor.domain = domain;
            descriptor.nspace = nspace;
            descriptor.blob = null;
            return descriptor;
        }

        @Override
        public AttestationCertificates generateCertificateChain(int targetUid, String alias,
                int domain, long nspace, KeyboxKeyParameters params, byte[] leafCertificate) {
            enforcePermission();
            try {
                KeyGenParameters keyGenParams = new KeyGenParameters(params);
                KeyDescriptor descriptor = buildDescriptor(alias, domain, nspace);
                List<Certificate> chain = KeyboxChainGenerator.generateCertChainFromCert(
                        targetUid, descriptor, keyGenParams, leafCertificate);

                if (chain == null || chain.isEmpty()) {
                    return emptyCertificates();
                }

                return buildCertificates(chain, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate certificate chain", e);
                return emptyCertificates();
            }
        }

        @Override
        public AttestationCertificates generateSoftwareKey(int targetUid, String alias,
                int domain, long nspace, KeyboxKeyParameters params, byte[] entropy) {
            enforcePermission();
            try {
                KeyGenParameters keyGenParams = new KeyGenParameters(params);
                KeyDescriptor descriptor = buildDescriptor(alias, domain, nspace);

                KeyboxChainGenerator.GeneratedKeyMaterial material =
                        KeyboxChainGenerator.generateKeyMaterial(
                                targetUid, descriptor, keyGenParams, entropy);

                if (material == null || material.keyPair == null
                        || material.certificateChain == null
                        || material.certificateChain.isEmpty()) {
                    return emptyCertificates();
                }

                byte[] privateKey = material.keyPair.getPrivate().getEncoded();
                if (privateKey == null || privateKey.length == 0) {
                    return emptyCertificates();
                }

                return buildCertificates(material.certificateChain, privateKey);
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate software key and certificates", e);
                return emptyCertificates();
            }
        }
    }
}
