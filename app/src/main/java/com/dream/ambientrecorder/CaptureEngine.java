package com.dream.ambientrecorder;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class CaptureEngine {
    private static final int SEGMENT_SECONDS = 5 * 60;
    private final AudioArchive archive;
    private final BooleanSupplier keepRunning;
    private final Supplier<AudioArchive.Mode> currentMode;
    private final BiConsumer<Boolean, AudioArchive.Mode> reportCapture;

    CaptureEngine(AudioArchive archive, BooleanSupplier keepRunning, Supplier<AudioArchive.Mode> currentMode,
                  BiConsumer<Boolean, AudioArchive.Mode> reportCapture) {
        this.archive = archive;
        this.keepRunning = keepRunning;
        this.currentMode = currentMode;
        this.reportCapture = reportCapture;
    }

    @SuppressLint("MissingPermission")
    void run(boolean rawInputSupported) throws IOException {
        requireFreeSpace();
        archive.recover();
        int retries = 0;
        while (keepRunning.getAsBoolean()) {
            long attemptStartedAt = SystemClock.elapsedRealtime();
            try {
                capture(rawInputSupported);
                return;
            } catch (InputDisconnected error) {
                if (!keepRunning.getAsBoolean()) {
                    return;
                }
                if (SystemClock.elapsedRealtime() - attemptStartedAt > 30_000) {
                    retries = 0;
                }
                if (retries++ >= 3) {
                    throw error;
                }
                reportCapture.accept(true, currentMode.get());
                SystemClock.sleep(500);
            }
        }
    }

    private void requireFreeSpace() throws LowStorage {
        if (archive.availableBytes() < AudioArchive.FREE_SPACE_RESERVE) {
            throw new LowStorage();
        }
    }

    @SuppressLint("MissingPermission")
    private void capture(boolean rawInputSupported) throws IOException {
        AudioRecord recorder = openMicrophone(rawInputSupported);
        int rate = recorder.getSampleRate();
        byte[] pcm = new byte[rate / 5]; // One tenth of a second of mono PCM16.
        ByteBuffer input = ByteBuffer.allocateDirect(pcm.length);
        SilencingMonitor monitor = new SilencingMonitor(recorder.getAudioSessionId());
        boolean callbackRegistered = false;

        try (SegmentSession session = new SegmentSession(rate)) {
            recorder.registerAudioRecordingCallback(new Handler(Looper.getMainLooper())::post, monitor);
            callbackRegistered = true;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Microphone did not start");
            }

            AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
            if (configuration != null) {
                monitor.silenced = configuration.isClientSilenced();
            }

            long nextSpaceCheck = 0;
            long anchor = 0;
            long samplesRead = 0;
            while (keepRunning.getAsBoolean()) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextSpaceCheck) {
                    requireFreeSpace();
                    nextSpaceCheck = now + 1000;
                }

                int bytes = recorder.read(input, pcm.length, AudioRecord.READ_BLOCKING);
                if (bytes == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new InputDisconnected();
                }
                if (bytes < 0) {
                    throw new IOException("Microphone read failed (" + bytes + ")");
                }
                if (bytes == 0) {
                    continue;
                }
                if (anchor == 0) {
                    anchor = System.currentTimeMillis() - (bytes / 2L) * 1000 / rate;
                }
                long capturedAt = anchor + samplesRead * 1000 / rate;
                samplesRead += bytes / 2L;
                input.position(0);
                input.get(pcm, 0, bytes);
                session.append(pcm, bytes, capturedAt, currentMode.get(), monitor.silenced);
            }
        } finally {
            try {
                if (callbackRegistered) {
                    recorder.unregisterAudioRecordingCallback(monitor);
                }
            } finally {
                recorder.release();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private static AudioRecord openMicrophone(boolean rawInputSupported) throws IOException {
        int[] sources = rawInputSupported
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.MIC};
        Throwable lastFailure = null;
        for (int source : sources) {
            for (int rate : new int[]{48000, 44100}) {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) {
                    continue;
                }
                try {
                    AudioRecord recorder = new AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build())
                            .setBufferSizeInBytes(Math.max(minimum, rate * 4))
                            .build();
                    if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                        return recorder;
                    }
                    recorder.release();
                } catch (IllegalArgumentException | UnsupportedOperationException error) {
                    lastFailure = error;
                }
            }
        }
        throw new IOException("Could not open the microphone at 48 or 44.1 kHz", lastFailure);
    }

    private final class SegmentSession implements AutoCloseable {
        private final int rate;
        private SegmentWriter segment;
        private boolean wasSilenced;

        SegmentSession(int rate) {
            this.rate = rate;
        }

        void append(byte[] pcm, int bytes, long capturedAt, AudioArchive.Mode mode, boolean silenced) throws IOException {
            if (silenced) {
                if (!wasSilenced) {
                    close();
                    reportCapture.accept(true, mode);
                }
                wasSilenced = true;
                return;
            }

            if (segment != null && (segment.mode() != mode || segment.samples() >= (long) rate * SEGMENT_SECONDS)) {
                close();
            }
            if (segment == null) {
                segment = new SegmentWriter(archive, capturedAt, rate, mode);
                reportCapture.accept(false, mode);
            }
            wasSilenced = false;
            segment.write(pcm, bytes);
        }

        @Override
        public void close() throws IOException {
            if (segment != null) {
                SegmentWriter finished = segment;
                segment = null;
                finished.close();
            }
        }
    }

    private static final class SilencingMonitor extends AudioManager.AudioRecordingCallback {
        private final int sessionId;
        volatile boolean silenced;

        SilencingMonitor(int sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
            for (AudioRecordingConfiguration configuration : configurations) {
                if (configuration.getClientAudioSessionId() == sessionId) {
                    silenced = configuration.isClientSilenced();
                    return;
                }
            }
        }
    }

    static final class LowStorage extends IOException {
        private static final long serialVersionUID = 1L;
    }
    private static final class InputDisconnected extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
