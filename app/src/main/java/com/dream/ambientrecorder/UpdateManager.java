package com.dream.ambientrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class UpdateManager {
    enum Phase { IDLE, CHECKING, CURRENT, WAITING, DOWNLOADING, READY, UNSUPPORTED, ERROR }
    record Status(Phase phase, UpdateRelease release, int percent, String detail) {}

    private static final long FEED_TTL = 5 * 60_000;
    private static final long POLL_INTERVAL = 15_000;
    private static UpdateManager manager;

    static synchronized UpdateManager get(Context context) {
        if (manager == null) {
            manager = new UpdateManager(context.getApplicationContext());
        }
        return manager;
    }

    private final Context context;
    private final PackageInfo installed;
    private final ConnectivityManager connectivity;
    private final SharedPreferences settings;
    private final File directory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable poll = () -> check(false);
    private volatile boolean visible;
    private volatile boolean wifiOnly;
    private volatile int generation;
    private Consumer<Status> listener;
    private boolean busy;
    private boolean forcePending;
    private Status status = new Status(Phase.IDLE, null, 0, "");
    private UpdateRelease latest;
    private volatile long expiresAt;

    private UpdateManager(Context context) {
        this.context = context;
        settings = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
        wifiOnly = settings.getBoolean("wifiOnly", true);
        connectivity = context.getSystemService(ConnectivityManager.class);
        directory = new File(context.getCacheDir(), "updates");
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
    }

    String installedVersion() { return installed.versionName; }
    boolean wifiOnly() { return wifiOnly; }

    void setWifiOnly(boolean value) {
        wifiOnly = value;
        settings.edit().putBoolean("wifiOnly", value).apply();
        check(false);
    }

    void resume(Consumer<Status> observer) {
        if (listener != observer) {
            generation++;
        }
        listener = observer;
        visible = true;
        listener.accept(status);
        check(false);
    }

    void pause(Consumer<Status> observer) {
        if (listener == observer) {
            listener = null;
            visible = false;
            generation++;
            main.removeCallbacks(poll);
        }
    }

    void check(boolean force) {
        if (!visible) {
            return;
        }
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_INTERVAL);
        if (busy) {
            forcePending |= force;
            return;
        }
        if (!force && (status.phase() == Phase.READY || status.phase() == Phase.CURRENT
                || status.phase() == Phase.UNSUPPORTED) && SystemClock.elapsedRealtime() < expiresAt) {
            return;
        }
        busy = true;
        int run = generation;
        io.execute(() -> {
            try {
                refresh(run, force);
            } catch (IOException | RuntimeException error) {
                if (active(run)) {
                    publish(run, Phase.ERROR, latest, 0, latest == null
                            ? "Could not check for updates." : "Could not complete the update. Try again.");
                }
            } finally {
                main.post(() -> {
                    busy = false;
                    boolean retry = forcePending;
                    forcePending = false;
                    if (visible && (retry || run != generation)) {
                        check(retry);
                    }
                });
            }
        });
    }

    private void refresh(int run, boolean force) throws IOException {
        if (!active(run)) {
            return;
        }
        if (force || latest == null || SystemClock.elapsedRealtime() >= expiresAt) {
            publish(run, Phase.CHECKING, latest, 0, "Checking for updates…");
            latest = UpdateTransport.latest(() -> active(run));
            expiresAt = SystemClock.elapsedRealtime() + FEED_TTL;
        }
        UpdateRelease release = latest;
        if (release.versionCode() <= installed.getLongVersionCode()) {
            publish(run, Phase.CURRENT, null, 0, "You’re on the latest version.");
            return;
        }
        if (release.minSdk() > Build.VERSION.SDK_INT) {
            publish(run, Phase.UNSUPPORTED, release, 0, "This update requires a newer Android version.");
            return;
        }

        File apk = new File(directory, release.apkName());
        if (apk.isFile()) {
            try {
                verify(apk, release);
                publish(run, Phase.READY, release, 100, "Ready to install.");
                return;
            } catch (IOException error) {
                if (!apk.delete()) {
                    throw error;
                }
            }
        }
        Network network = connectivity.getActiveNetwork();
        if (!eligible(network)) {
            publish(run, Phase.WAITING, release, 0, wifiOnly ? "Waiting for Wi-Fi." : "Waiting for a connection.");
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create update cache");
        }

        File partial = new File(directory, release.sha256() + ".part");
        removeStalePartials(partial);
        publish(run, Phase.DOWNLOADING, release, (int) Math.min(100, 100 * partial.length() / release.size()), "Downloading update…");
        try {
            UpdateTransport.download(network, release, partial, () -> active(run) && eligible(network),
                    percent -> publish(run, Phase.DOWNLOADING, release, percent, "Downloading update…"));
        } catch (InterruptedIOException interrupted) {
            if (!active(run)) {
                return;
            }
            if (!eligible(network)) {
                publish(run, Phase.WAITING, release, 0, wifiOnly ? "Waiting for Wi-Fi." : "Waiting for a connection.");
                return;
            }
            throw interrupted;
        }
        if (!partial.renameTo(apk)) {
            throw new IOException("Could not finish update download");
        }
        try {
            verify(apk, release);
        } catch (IOException error) {
            if (!apk.delete()) {
                error.addSuppressed(new IOException("Could not remove invalid APK"));
            }
            throw error;
        }
        publish(run, Phase.READY, release, 100, "Ready to install.");
    }

    private void removeStalePartials(File current) {
        File[] stale = directory.listFiles((parent, name) -> name.endsWith(".part") && !name.equals(current.getName()));
        if (stale != null) {
            for (File file : stale) {
                file.delete();
            }
        }
    }

    private boolean eligible(Network network) {
        if (network == null || !network.equals(connectivity.getActiveNetwork())) {
            return false;
        }
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && (!wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    void verifyForInstall(Consumer<UpdateRelease> ready, Runnable failed) {
        Status current = status;
        if (current.phase() != Phase.READY) {
            return;
        }
        int run = generation;
        io.execute(() -> {
            try {
                UpdateRelease release = current.release();
                verify(new File(directory, release.apkName()), release);
                main.post(() -> {
                    if (!active(run)) {
                        return;
                    }
                    if (release.equals(status.release()) && status.phase() == Phase.READY) {
                        ready.accept(release);
                    } else {
                        failed.run();
                    }
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    if (active(run)) {
                        failed.run();
                        check(true);
                    }
                });
            }
        });
    }

    private void verify(File file, UpdateRelease release) throws IOException {
        if (!file.isFile() || file.length() != release.size() || !sha256(file).equals(release.sha256())) {
            throw new IOException("Update APK size or checksum does not match");
        }
        PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || candidate.applicationInfo == null || candidate.signingInfo == null || installed.signingInfo == null
                || !installed.packageName.equals(candidate.packageName)
                || candidate.getLongVersionCode() != release.versionCode()
                || !release.versionName().equals(candidate.versionName)
                || candidate.applicationInfo.minSdkVersion != release.minSdk()
                || !new HashSet<>(Arrays.asList(installed.signingInfo.getApkContentsSigners()))
                    .equals(new HashSet<>(Arrays.asList(candidate.signingInfo.getApkContentsSigners())))) {
            throw new IOException("Update APK identity does not match the installed app");
        }
    }

    private static String sha256(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private boolean active(int run) {
        return visible && run == generation;
    }

    private void publish(int run, Phase phase, UpdateRelease release, int percent, String detail) {
        main.post(() -> {
            if (active(run)) {
                Status next = new Status(phase, release, percent, detail);
                if (!next.equals(status)) {
                    status = next;
                    listener.accept(next);
                }
            }
        });
    }
}
