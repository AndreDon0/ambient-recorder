package com.dream.ambientrecorder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SyncJobService extends JobService {
    private static final int NOW = 74;
    private static final int PERIODIC = 75;
    private static final long INTERVAL_MS = 15 * 60_000L;
    private static final long BATCH_MS = 4 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SyncRun current;

    static synchronized boolean schedule(Context context) {
        if (!new SyncSettings(context).enabled()) {
            return false;
        }
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        ComponentName service = new ComponentName(context, SyncJobService.class);
        NetworkRequest wifi = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        if (scheduler.getPendingJob(PERIODIC) == null) {
            scheduler.schedule(new JobInfo.Builder(PERIODIC, service)
                    .setRequiredNetwork(wifi).setPeriodic(INTERVAL_MS).setPersisted(true).build());
        }
        return scheduler.getPendingJob(NOW) != null || scheduler.schedule(new JobInfo.Builder(NOW, service)
                .setRequiredNetwork(wifi).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS;
    }

    static synchronized void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(NOW);
        scheduler.cancel(PERIODIC);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (current != null || !new SyncSettings(this).enabled()) {
            return false;
        }
        SyncRun run = new SyncRun(params);
        current = run;
        worker.execute(() -> {
            boolean retry = run.run();
            main.post(() -> {
                if (current == run) {
                    current = null;
                    jobFinished(params, retry);
                }
            });
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (current != null && current.params.getJobId() == params.getJobId()) {
            current.stop();
            current = null;
        }
        return new SyncSettings(this).enabled();
    }

    @Override
    public void onDestroy() {
        if (current != null) {
            current.stop();
            current = null;
        }
        worker.shutdown();
        super.onDestroy();
    }

    private boolean onSameWifi(Network network, String host) throws IOException {
        if (network == null) {
            return false;
        }
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        LinkProperties link = manager.getLinkProperties(network);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || link == null) {
            return false;
        }
        InetAddress server = InetAddress.getByName(host);
        return link.getRoutes().stream().anyMatch(route ->
                !route.hasGateway() && route.getDestination().getPrefixLength() >= 8 && route.matches(server));
    }

    private final class SyncRun {
        private final JobParameters params;
        private final SyncSettings settings = new SyncSettings(SyncJobService.this);
        private final SyncSettings.Target target = settings.target();
        private final SftpBackup backup = new SftpBackup(target, this::active);
        private volatile boolean stopped;

        SyncRun(JobParameters params) { this.params = params; }

        boolean active() {
            return !stopped && settings.enabled() && target.equals(settings.target());
        }

        void stop() {
            stopped = true;
            backup.abort();
            settings.report(target, "Sync paused. Remaining recordings will retry.");
        }

        boolean run() {
            if (!active()) {
                return false;
            }
            try {
                target.validated();
                if (!onSameWifi(params.getNetwork(), target.host())) {
                    settings.report(target, "Waiting for the server’s Wi-Fi network.");
                    return false;
                }
                try (backup; SyncJournal journal = new SyncJournal(SyncJobService.this, target)) {
                    return uploadPending(journal);
                }
            } catch (JSchException error) {
                String message = backup.keyRejected() ? "Server fingerprint does not match."
                        : error.getMessage() != null && error.getMessage().startsWith("Auth fail")
                        ? "Authorize the phone’s SSH key on the server."
                        : "Could not connect to the SSH server. Will retry.";
                report(message);
                return active() && !backup.keyRejected();
            } catch (SftpException error) {
                report(error.id == ChannelSftp.SSH_FX_PERMISSION_DENIED
                        ? "The SSH user needs access to the destination folder."
                        : "Could not write to the destination folder. Will retry.");
                return active();
            } catch (IOException | RuntimeException error) {
                report("Sync could not finish. Remaining recordings will retry.");
                return active();
            }
        }

        private boolean uploadPending(SyncJournal journal) throws IOException, JSchException, SftpException {
            AudioArchive archive = new AudioArchive(getFilesDir());
            archive.recover();
            List<AudioArchive.Clip> clips = archive.clips();
            Set<String> sent = journal.sent();
            if (clips.stream().noneMatch(clip -> !sent.contains(clip.file().getName()))) {
                report(clips.isEmpty() ? "No recordings to upload yet." : "All recordings backed up.");
                return false;
            }
            report("Connecting to the server…");
            byte[] key = settings.privateKey(false);
            try {
                backup.connect(params.getNetwork(), key);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
            long until = SystemClock.elapsedRealtime() + BATCH_MS;
            int uploaded = 0;
            for (int i = clips.size() - 1; i >= 0; i--) {
                AudioArchive.Clip clip = clips.get(i);
                String name = clip.file().getName();
                if (sent.contains(name)) {
                    continue;
                }
                if (!active()) {
                    return false;
                }
                if (SystemClock.elapsedRealtime() >= until) {
                    report("Sync will resume with the remaining recordings.");
                    return true;
                }
                report("Uploading recordings… " + uploaded + " finished");
                try {
                    backup.upload(clip);
                } catch (FileNotFoundException error) {
                    if (!clip.file().exists()) {
                        continue;
                    }
                    throw error;
                }
                journal.record(name);
                uploaded++;
            }
            report("All recordings backed up.");
            return false;
        }

        private void report(String message) {
            if (active()) {
                settings.report(target, message);
            }
        }
    }
}
