package com.dream.ambientrecorder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RecordingService extends Service {
    static final String START = "com.dream.ambientrecorder.START";
    static final String STOP = "com.dream.ambientrecorder.STOP";
    private static final String RECORDING_CHANNEL = "ambient_recording";
    private static final String ISSUE_CHANNEL = "recording_issues";
    private static final int RECORDING_NOTIFICATION = 1;
    private static final int ISSUE_NOTIFICATION = 2;
    private static final String TAG = "AmbientRecorder";

    enum Phase { IDLE, STARTING, RECORDING, INTERRUPTED, STOPPING, ERROR }
    record Status(Phase phase, AudioArchive.Mode mode, long startedElapsed, String message) {
        boolean active() {
            return phase != Phase.IDLE && phase != Phase.ERROR;
        }
    }

    private static volatile Status status = new Status(Phase.IDLE, null, 0, "");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mode = modeFor(intent);
        }
    };

    private volatile boolean stopRequested;
    private volatile AudioArchive.Mode mode;
    private boolean running;
    private boolean destroyed;
    private long startedElapsed;
    private long startedWallTime;

    static Status status() {
        return status;
    }

    private static AudioArchive.Mode modeFor(Intent battery) {
        boolean plugged = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return plugged ? AudioArchive.Mode.WAV : AudioArchive.Mode.AAC;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Intent battery = registerReceiver(powerReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        mode = modeFor(battery);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(RECORDING_CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(ISSUE_CHANNEL, "Recording issues", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) {
            if (running) {
                stopRequested = true;
                update(Phase.STOPPING, status.mode(), "Saving the current recording");
            } else {
                stopSelf(startId);
            }
        } else if (START.equals(action) && !running) {
            begin();
        } else if (!running) {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        startedElapsed = SystemClock.elapsedRealtime();
        startedWallTime = System.currentTimeMillis();
        status = new Status(Phase.STARTING, mode, startedElapsed, "Opening the microphone");
        try {
            startForeground(RECORDING_NOTIFICATION, recordingNotification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException error) {
            complete("Android could not start the microphone. Open the app and check microphone permission.", error);
            return;
        }

        running = true;
        stopRequested = false;
        getSystemService(NotificationManager.class).cancel(ISSUE_NOTIFICATION);
        worker.execute(this::record);
    }

    @SuppressLint({"MissingPermission", "WakelockTimeout"})
    private void record() {
        AudioArchive archive = new AudioArchive(getFilesDir());
        PowerManager.WakeLock wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":Recording");
        String issue = null;
        Throwable failure = null;
        try {
            // The capture loop must continue supplying PCM to the encoder with the screen off.
            wakeLock.acquire();
            AudioManager audio = getSystemService(AudioManager.class);
            boolean rawInput = "true".equals(audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            new CaptureEngine(archive, () -> !stopRequested, () -> mode, (interrupted, actualMode) -> main.post(() -> {
                SyncJobService.schedule(this);
                if (running && !stopRequested && !destroyed) {
                    if (interrupted) {
                        update(Phase.INTERRUPTED, actualMode, "Microphone unavailable. Waiting to resume.");
                    } else {
                        update(Phase.RECORDING, actualMode, actualMode.label);
                    }
                }
            })).run(rawInput);
        } catch (CaptureEngine.LowStorage error) {
            issue = "Recording stopped because storage is low. Free space and tap Start to resume.";
            failure = error;
        } catch (IOException | RuntimeException error) {
            issue = "Recording stopped unexpectedly. Saved recordings are still in the archive.";
            failure = error;
            try {
                archive.recover();
            } catch (IOException recoveryError) {
                error.addSuppressed(recoveryError);
            }
        } finally {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            String result = issue;
            Throwable cause = failure;
            main.post(() -> {
                SyncJobService.schedule(this);
                if (!destroyed) {
                    complete(result, cause);
                }
            });
        }
    }

    private void update(Phase phase, AudioArchive.Mode actualMode, String message) {
        Status next = new Status(phase, actualMode, startedElapsed, message);
        if (!next.equals(status)) {
            status = next;
            getSystemService(NotificationManager.class).notify(RECORDING_NOTIFICATION, recordingNotification(next));
        }
    }

    private void complete(String issue, Throwable cause) {
        running = false;
        if (issue == null) {
            status = new Status(Phase.IDLE, null, 0, "");
        } else {
            Log.e(TAG, issue, cause);
            status = new Status(Phase.ERROR, null, 0, issue);
            Notification update = new Notification.Builder(this, ISSUE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_record)
                    .setContentTitle("Recording stopped")
                    .setContentText(issue)
                    .setStyle(new Notification.BigTextStyle().bigText(issue))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class).notify(ISSUE_NOTIFICATION, update);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification recordingNotification(Status state) {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = switch (state.phase()) {
            case STARTING -> "Starting recording";
            case INTERRUPTED -> "Microphone interrupted";
            case STOPPING -> "Saving recording";
            default -> "Recording nearby sound";
        };
        Notification.Builder builder = new Notification.Builder(this, RECORDING_CHANNEL)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(title)
                .setContentText(state.message())
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(startedWallTime)
                .setUsesChronometer(state.phase() == Phase.RECORDING)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopRequested = true;
        if (running) {
            status = new Status(Phase.IDLE, null, 0, "");
        }
        unregisterReceiver(powerReceiver);
        worker.shutdown();
        super.onDestroy();
    }
}
