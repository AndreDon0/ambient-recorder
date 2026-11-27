package com.dream.ambientrecorder;

import static com.dream.ambientrecorder.RecorderStyle.ACCENT;
import static com.dream.ambientrecorder.RecorderStyle.BACKGROUND;
import static com.dream.ambientrecorder.RecorderStyle.ERROR;
import static com.dream.ambientrecorder.RecorderStyle.LINE;
import static com.dream.ambientrecorder.RecorderStyle.MUTED;
import static com.dream.ambientrecorder.RecorderStyle.ON_ACCENT;
import static com.dream.ambientrecorder.RecorderStyle.PANEL;
import static com.dream.ambientrecorder.RecorderStyle.TEXT;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 10;
    private static final int EXPORT_REQUEST = 11;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::updateScreen;
    private RecorderStyle style;
    private AudioArchive archive;
    private ClipPlayer player;
    private UpdatesUi updates;
    private SyncUi sync;
    private ClipAdapter adapter;
    private TextView marker;
    private TextView stateTitle;
    private TextView stateDetail;
    private TextView timer;
    private Button recordButton;
    private TextView storage;
    private LinearLayout playbackBar;
    private TextView playbackTitle;
    private TextView playbackTime;
    private Button playbackToggle;
    private SeekBar seek;
    private boolean seeking;
    private boolean loading;
    private boolean recordCommandPending;
    private long nextStorageRefresh;
    private long displayedRevision = -1;
    private String pendingExport;
    private RecordingService.Phase renderedPhase;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        style = new RecorderStyle(this);
        archive = new AudioArchive(getFilesDir());
        player = new ClipPlayer(this, this::renderPlayback, this::showMessage);
        updates = new UpdatesUi(this, style, this::showMessage);
        sync = new SyncUi(this, style, disk, this::showMessage);
        pendingExport = state == null ? null : state.getString("pendingExport");
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        setContentView(buildScreen());
        disk.execute(() -> {
            try {
                archive.recover();
                SyncJobService.schedule(this);
            } catch (IOException error) {
                runOnUiThread(() -> showMessage("Some unfinished recordings could not be recovered."));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        updates.resume();
        updateScreen();
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(tick);
        updates.pause();
        player.close();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        disk.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingExport", pendingExport);
        super.onSaveInstanceState(state);
    }

    @SuppressWarnings("deprecation")
    private View buildScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(style.dp(20), style.dp(14), style.dp(20), style.dp(10));
        root.setOnApplyWindowInsetsListener((view, window) -> {
            Insets insets = Build.VERSION.SDK_INT >= 30
                    ? window.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())
                    : window.getSystemWindowInsets();
            view.setPadding(style.dp(20) + insets.left, style.dp(14) + insets.top,
                    style.dp(20) + insets.right, style.dp(10) + insets.bottom);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : window.consumeSystemWindowInsets();
        });

        TextView title = style.text(30, TEXT, true);
        title.setText(R.string.app_name);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        LinearLayout.LayoutParams updateButton = new LinearLayout.LayoutParams(WRAP, WRAP);
        updateButton.setMarginStart(style.dp(8));
        header.addView(updates.button(), updateButton);
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        root.addView(recorderPanel(), marginTop(16));

        TextView archiveTitle = style.text(23, TEXT, true);
        archiveTitle.setText("Recordings");
        LinearLayout archiveHeader = new LinearLayout(this);
        archiveHeader.setGravity(Gravity.CENTER_VERTICAL);
        archiveHeader.addView(archiveTitle, new LinearLayout.LayoutParams(0, WRAP, 1));
        archiveHeader.addView(sync.button(), new LinearLayout.LayoutParams(WRAP, WRAP));
        root.addView(archiveHeader, marginTop(16));
        storage = style.text(13, MUTED, false);
        root.addView(storage, marginTop(3));

        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(style.dp(1));
        list.setClipToPadding(false);
        list.setItemsCanFocus(false);
        list.setSelector(style.tappable(0x162d3b2c, 12));
        adapter = new ClipAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> player.open(adapter.getItem(position)));
        listArea.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));

        TextView empty = style.text(15, MUTED, false);
        empty.setText("Finished recordings will appear here.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12));
        listArea.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        list.setEmptyView(empty);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(MATCH, 0, 1);
        listParams.topMargin = style.dp(5);
        root.addView(listArea, listParams);

        root.addView(playbackPanel(), new LinearLayout.LayoutParams(MATCH, WRAP));
        return root;
    }

    private LinearLayout recorderPanel() {
        LinearLayout panel = column();
        panel.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(14));
        panel.setBackground(style.rounded(PANEL, 21, LINE));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        marker = style.text(13, ACCENT, false);
        marker.setText("●");
        marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(marker);
        stateTitle = style.text(20, TEXT, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        titleParams.setMarginStart(style.dp(9));
        row.addView(stateTitle, titleParams);
        timer = style.text(16, ACCENT, true);
        timer.setFontFeatureSettings("tnum");
        row.addView(timer);
        panel.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));

        stateDetail = style.text(14, MUTED, false);
        panel.addView(stateDetail, marginTop(4));

        recordButton = style.button("Start recording", ACCENT, ON_ACCENT);
        recordButton.setOnClickListener(view -> recordButtonPressed());
        panel.addView(recordButton, marginTop(12));
        return panel;
    }

    private LinearLayout playbackPanel() {
        playbackBar = column();
        playbackBar.setVisibility(View.GONE);
        playbackBar.setPadding(style.dp(14), style.dp(8), style.dp(12), style.dp(5));
        playbackBar.setBackground(style.rounded(PANEL, 18, LINE));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        playbackTitle = style.text(15, TEXT, true);
        playbackTitle.setSingleLine();
        playbackTitle.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(playbackTitle, new LinearLayout.LayoutParams(0, WRAP, 1));
        playbackToggle = style.button("Pause", ACCENT, ON_ACCENT);
        playbackToggle.setOnClickListener(view -> player.toggle());
        row.addView(playbackToggle, new LinearLayout.LayoutParams(WRAP, WRAP));
        playbackBar.addView(row);

        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setContentDescription("Playback position");
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                player.seek(bar.getProgress());
                seeking = false;
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && !seeking) {
                    player.seek(value);
                }
                if (fromUser) {
                    showPlaybackTime(value, bar.getMax());
                }
            }
        });
        timeline.addView(seek, new LinearLayout.LayoutParams(0, style.dp(42), 1));
        playbackTime = style.text(13, MUTED, false);
        playbackTime.setFontFeatureSettings("tnum");
        timeline.addView(playbackTime);
        playbackBar.addView(timeline);
        return playbackBar;
    }

    private void recordButtonPressed() {
        if (recordCommandPending) {
            return;
        }
        if (RecordingService.status().active()) {
            startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            String[] permissions = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO};
            requestPermissions(permissions, MICROPHONE_REQUEST);
            return;
        }
        startRecording();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                showMessage("Microphone permission is required to record.");
            }
        }
    }

    private void startRecording() {
        try {
            startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.START));
            recordCommandPending = true;
            recordButton.setEnabled(false);
            main.postDelayed(() -> recordCommandPending = false, 1000);
        } catch (RuntimeException error) {
            showMessage("Android could not start recording. Try again with the app open.");
        }
    }

    private void updateScreen() {
        sync.refresh();
        RecordingService.Status status = RecordingService.status();
        renderCapture(status);
        if (player.ready() && !seeking) {
            renderProgress();
        }
        long now = SystemClock.elapsedRealtime();
        if (!loading && (AudioArchive.revision() != displayedRevision || now >= nextStorageRefresh)) {
            refreshArchive();
            nextStorageRefresh = now + 10_000;
        }
        main.removeCallbacks(tick);
        main.postDelayed(tick, player.playing() || recordCommandPending ? 250 : 1000);
    }

    private void renderCapture(RecordingService.Status status) {
        RecordingService.Phase phase = status.phase();
        String title = switch (phase) {
            case IDLE -> "Ready to record";
            case STARTING -> "Starting";
            case RECORDING -> "Recording";
            case INTERRUPTED -> "Microphone interrupted";
            case STOPPING -> "Saving recording";
            case ERROR -> "Recording stopped";
        };
        if (renderedPhase != phase) {
            stateTitle.setText(title);
            marker.setTextColor(phase == RecordingService.Phase.RECORDING ? ACCENT
                    : phase == RecordingService.Phase.ERROR || phase == RecordingService.Phase.INTERRUPTED ? ERROR : MUTED);
            recordButton.setText(status.active() ? "Stop recording" : "Start recording");
            recordButton.setTextColor(status.active() ? TEXT : ON_ACCENT);
            recordButton.setBackground(style.tappable(status.active() ? LINE : ACCENT, 16));
            renderedPhase = phase;
        }
        timer.setVisibility(status.active() ? View.VISIBLE : View.GONE);
        if (status.active()) {
            timer.setText(RecorderStyle.clock(SystemClock.elapsedRealtime() - status.startedElapsed()));
        }
        String detail = phase == RecordingService.Phase.IDLE
                ? "WAV on external power · AAC on battery"
                : status.message();
        stateDetail.setText(detail);
        recordButton.setEnabled(!recordCommandPending && phase != RecordingService.Phase.STOPPING);
    }

    private void refreshArchive() {
        loading = true;
        long revision = AudioArchive.revision();
        boolean reload = revision != displayedRevision;
        disk.execute(() -> {
            List<AudioArchive.Clip> clips = reload ? archive.clips() : null;
            long available = archive.availableBytes();
            runOnUiThread(() -> {
                loading = false;
                if (isDestroyed()) {
                    return;
                }
                if (clips != null) {
                    adapter.clips = clips;
                    adapter.notifyDataSetChanged();
                    displayedRevision = revision;
                }
                int count = adapter.getCount();
                storage.setText(getResources().getQuantityString(R.plurals.recordings, count, count)
                        + "  ·  " + Formatter.formatShortFileSize(this, available) + " free");
                storage.setTextColor(available < AudioArchive.FREE_SPACE_RESERVE ? ERROR : MUTED);
            });
        });
    }

    private void renderPlayback() {
        AudioArchive.Clip clip = player.selected();
        playbackBar.setVisibility(clip == null ? View.GONE : View.VISIBLE);
        if (clip != null) {
            playbackTitle.setText(RecorderStyle.timestamp(clip.startedAt()));
            playbackToggle.setText(player.playing() ? "Pause" : "Play");
            playbackToggle.setEnabled(player.ready());
            seek.setEnabled(player.ready());
            renderProgress();
        }
        adapter.notifyDataSetChanged();
    }

    private void renderProgress() {
        int duration = player.duration();
        int position = player.position();
        seek.setMax(duration);
        seek.setProgress(position);
        showPlaybackTime(position, duration);
    }

    private void showPlaybackTime(int position, int duration) {
        playbackTime.setText(RecorderStyle.clock(position) + " / " + RecorderStyle.clock(duration));
    }

    private void showClipMenu(View anchor, AudioArchive.Clip clip) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Export audio").setOnMenuItemClickListener(item -> {
            pendingExport = clip.file().getName();
            String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date(clip.startedAt()));
            Intent target = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(clip.mode().mimeType)
                    .putExtra(Intent.EXTRA_TITLE, "ambient_" + date + "." + clip.mode().extension);
            startActivityForResult(target, EXPORT_REQUEST);
            return true;
        });
        menu.getMenu().add("Delete recording").setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle("Delete recording?")
                    .setMessage(RecorderStyle.timestamp(clip.startedAt()))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        if (clip.equals(player.selected())) {
                            player.close();
                        }
                        disk.execute(() -> {
                            if (!archive.delete(clip)) {
                                runOnUiThread(() -> showMessage("Could not delete the recording."));
                            }
                        });
                    }).show();
            return true;
        });
        menu.show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST) {
            return;
        }
        String filename = pendingExport;
        pendingExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null || filename == null) {
            return;
        }
        disk.execute(() -> {
            try {
                AudioArchive.Clip clip = archive.clips().stream()
                        .filter(item -> item.file().getName().equals(filename)).findFirst()
                        .orElseThrow(() -> new IOException("Recording no longer exists"));
                try (FileInputStream source = new FileInputStream(clip.file());
                     OutputStream target = getContentResolver().openOutputStream(data.getData(), "w")) {
                    if (target == null) {
                        throw new IOException("Document destination unavailable");
                    }
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        target.write(buffer, 0, count);
                    }
                }
                runOnUiThread(() -> showMessage("Recording exported."));
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showMessage("Could not export the recording."));
            }
        });
    }

    private void showMessage(String message) {
        if (!isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams marginTop(int size) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = style.dp(size);
        return params;
    }

    private final class ClipAdapter extends BaseAdapter {
        private List<AudioArchive.Clip> clips = Collections.emptyList();

        @Override
        public int getCount() {
            return clips.size();
        }

        @Override
        public AudioArchive.Clip getItem(int position) {
            return clips.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            ClipRow row = reusable == null ? new ClipRow() : (ClipRow) reusable;
            row.bind(getItem(position));
            return row;
        }
    }

    private final class ClipRow extends LinearLayout {
        private final TextView glyph = style.text(18, ACCENT, false);
        private final TextView title = style.text(16, TEXT, true);
        private final TextView details = style.text(13, MUTED, false);
        private final Button more = style.button("⋮", BACKGROUND, MUTED);

        ClipRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(style.dp(67));
            setPadding(0, style.dp(5), 0, style.dp(5));
            glyph.setGravity(Gravity.CENTER);
            glyph.setBackground(style.rounded(PANEL, 13, 0));
            glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(glyph, new LinearLayout.LayoutParams(style.dp(42), style.dp(42)));

            LinearLayout labels = column();
            labels.addView(title);
            labels.addView(details);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, WRAP, 1);
            labelParams.setMarginStart(style.dp(12));
            addView(labels, labelParams);

            more.setTextSize(22);
            more.setPadding(0, 0, 0, 0);
            more.setMinWidth(0);
            more.setMinimumWidth(0);
            more.setFocusable(false);
            addView(more, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        }

        void bind(AudioArchive.Clip clip) {
            glyph.setText(clip.equals(player.selected()) && player.playing() ? "Ⅱ" : "▶︎");
            title.setText(RecorderStyle.timestamp(clip.startedAt()));
            details.setText(RecorderStyle.clock(clip.durationMs()) + "  ·  " + clip.mode().label + "  ·  "
                    + Formatter.formatShortFileSize(MainActivity.this, clip.size()));
            more.setContentDescription("Options for " + title.getText());
            more.setOnClickListener(view -> showClipMenu(view, clip));
        }
    }
}
