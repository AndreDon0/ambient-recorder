package com.dream.ambientrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

final class UpdatesUi {
    private final Activity activity;
    private final RecorderStyle style;
    private final UpdateManager manager;
    private final Consumer<String> message;
    private final Consumer<UpdateManager.Status> listener = this::render;
    private final Button button;
    private AlertDialog dialog;
    private TextView summary;
    private TextView notes;
    private ProgressBar progress;
    private UpdateManager.Status status;
    private boolean installing;

    UpdatesUi(Activity activity, RecorderStyle style, Consumer<String> message) {
        this.activity = activity;
        this.style = style;
        this.message = message;
        manager = UpdateManager.get(activity);
        button = style.button("Updates", RecorderStyle.PANEL, RecorderStyle.TEXT);
        button.setOnClickListener(view -> open());
    }

    Button button() { return button; }
    void resume() { manager.resume(listener); }
    void pause() {
        manager.pause(listener);
        installing = false;
    }

    private void open() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(24), style.dp(8), style.dp(24), style.dp(10));
        summary = style.text(15, RecorderStyle.TEXT, false);
        body.addView(summary);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        body.addView(progress);
        notes = style.text(15, RecorderStyle.MUTED, false);
        notes.setPadding(0, style.dp(12), 0, style.dp(12));
        body.addView(notes);
        CheckBox wifi = new CheckBox(activity);
        wifi.setText("Download updates only over Wi-Fi");
        wifi.setChecked(manager.wifiOnly());
        wifi.setOnCheckedChangeListener((view, checked) -> manager.setWifiOnly(checked));
        body.addView(wifi);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Updates")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setNeutralButton("Check again", null)
                .setPositiveButton("Install", null)
                .create();
        dialog.setOnDismissListener(ignored -> dialog = null);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> manager.check(true));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> install());
        render(status);
        manager.check(false);
    }

    private void render(UpdateManager.Status next) {
        status = next;
        if (next == null) {
            return;
        }
        button.setText(switch (next.phase()) {
            case READY -> "Update ready";
            case DOWNLOADING -> "Update " + next.percent() + "%";
            default -> "Updates";
        });
        if (dialog == null) {
            return;
        }
        String version = next.release() == null ? "" : "\nAvailable " + next.release().versionName();
        summary.setText("Installed " + manager.installedVersion() + version + "\n" + next.detail());
        notes.setText(next.release() == null ? "" : next.release().notes());
        notes.setVisibility(next.release() == null ? View.GONE : View.VISIBLE);
        progress.setVisibility(next.phase() == UpdateManager.Phase.DOWNLOADING ? View.VISIBLE : View.GONE);
        progress.setProgress(next.percent());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(
                next.phase() == UpdateManager.Phase.READY ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!installing);
    }

    private void install() {
        if (RecordingService.status().active()) {
            message.accept("Stop recording before installing the update.");
            return;
        }
        if (installing) {
            return;
        }
        installing = true;
        render(status);
        manager.verifyForInstall(release -> {
            installing = false;
            render(status);
            if (dialog == null) {
                return;
            }
            if (RecordingService.status().active()) {
                message.accept("Stop recording before installing the update.");
                return;
            }
            try {
                requestInstallation(release);
            } catch (RuntimeException error) {
                message.accept("Android could not open the installer.");
            }
        }, () -> {
            installing = false;
            render(status);
            message.accept("The downloaded update could not be verified.");
        });
    }

    private void requestInstallation(UpdateRelease release) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", activity.getPackageName(), null));
            activity.startActivity(settings);
            message.accept("After allowing installs from this app, return and tap Install again.");
            return;
        }
        Uri apk = UpdateApkProvider.uri(activity, release);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, UpdateApkProvider.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Ambient Recorder update", apk));
        activity.startActivity(intent);
    }
}
