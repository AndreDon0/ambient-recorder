package com.dream.ambientrecorder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.function.Consumer;

final class ClipPlayer implements AutoCloseable {
    private final AudioManager audioManager;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private final AudioFocusRequest focus;
    private final Runnable changed;
    private final Consumer<String> failure;
    private AudioArchive.Clip clip;
    private MediaPlayer player;
    private boolean ready;
    private boolean hasFocus;
    private boolean completed;

    ClipPlayer(Context context, Runnable changed, Consumer<String> failure) {
        audioManager = context.getSystemService(AudioManager.class);
        this.changed = changed;
        this.failure = failure;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pause();
                    }
                })
                .build();
    }

    AudioArchive.Clip selected() {
        return clip;
    }

    boolean ready() {
        return ready;
    }

    boolean playing() {
        return ready && player.isPlaying();
    }

    int position() {
        return ready ? player.getCurrentPosition() : 0;
    }

    int duration() {
        if (ready && player.getDuration() > 0) {
            return player.getDuration();
        }
        return clip == null ? 0 : (int) clip.durationMs();
    }

    void open(AudioArchive.Clip next) {
        if (clip != null && clip.file().equals(next.file())) {
            toggle();
            return;
        }
        close();
        clip = next;
        MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        try (FileInputStream source = new FileInputStream(next.file())) {
            candidate.setAudioAttributes(attributes);
            candidate.setDataSource(source.getFD(), 0, source.getChannel().size());
            candidate.setOnPreparedListener(prepared -> {
                if (player == prepared) {
                    ready = true;
                    play();
                }
            });
            candidate.setOnCompletionListener(finished -> {
                if (player == finished) {
                    completed = true;
                    abandonFocus();
                    changed.run();
                }
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) {
                    playbackFailed();
                }
                return true;
            });
            candidate.prepareAsync();
            changed.run();
        } catch (IOException | RuntimeException error) {
            playbackFailed();
        }
    }

    void toggle() {
        if (!ready) {
            return;
        }
        if (playing()) {
            pause();
        } else {
            play();
        }
    }

    private void play() {
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        if (!hasFocus) {
            failure.accept("Audio is currently in use. Tap Play to try again.");
            changed.run();
            return;
        }

        try {
            if (completed) {
                seek(0);
            }
            player.start();
            changed.run();
        } catch (IllegalStateException error) {
            playbackFailed();
        }
    }

    private void pause() {
        if (playing()) {
            player.pause();
        }
        abandonFocus();
        changed.run();
    }

    void seek(int milliseconds) {
        if (ready) {
            completed = false;
            player.seekTo(milliseconds, MediaPlayer.SEEK_CLOSEST);
        }
    }

    private void abandonFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focus);
            hasFocus = false;
        }
    }

    private void playbackFailed() {
        close();
        failure.accept("This recording could not be played.");
    }

    @Override
    public void close() {
        abandonFocus();
        if (player != null) {
            player.release();
            player = null;
        }
        clip = null;
        ready = false;
        completed = false;
        changed.run();
    }
}
