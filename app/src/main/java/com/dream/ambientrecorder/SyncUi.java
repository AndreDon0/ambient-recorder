package com.dream.ambientrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jcraft.jsch.JSchException;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class SyncUi {
    private final Activity activity;
    private final RecorderStyle style;
    private final Executor disk;
    private final Consumer<String> message;
    private final SyncSettings settings;
    private TextView status;

    SyncUi(Activity activity, RecorderStyle style, Executor disk, Consumer<String> message) {
        this.activity = activity;
        this.style = style;
        this.disk = disk;
        this.message = message;
        settings = new SyncSettings(activity);
    }

    Button button() {
        Button button = style.button("Sync", RecorderStyle.PANEL, RecorderStyle.TEXT);
        button.setContentDescription("Recording sync");
        button.setOnClickListener(view -> open());
        return button;
    }

    void refresh() {
        if (status != null && !settings.message().contentEquals(status.getText())) {
            status.setText(settings.message());
        }
    }

    private void open() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(24), style.dp(8), style.dp(24), style.dp(12));
        status = style.text(15, RecorderStyle.TEXT, true);
        refresh();
        body.addView(status);

        SyncSettings.Target saved = settings.target();
        EditText host = field(body, "Server IPv4 address", saved.host());
        EditText username = field(body, "SSH username", saved.username());
        EditText folder = field(body, "Destination folder", saved.directory());
        EditText fingerprint = field(body, "SSH host fingerprint", saved.fingerprint());

        TextView instructions = style.text(14, RecorderStyle.MUTED, false);
        instructions.setText("Add the phone’s public key to the SSH user on your server before enabling sync.");
        instructions.setPadding(0, style.dp(8), 0, style.dp(8));
        body.addView(instructions);

        Button key = style.button("Copy phone’s public key", RecorderStyle.PANEL, RecorderStyle.TEXT);
        key.setOnClickListener(view -> copyKey(key));
        body.addView(key);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Recording sync")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setPositiveButton(settings.enabled() ? "Save and sync" : "Enable sync", null);
        if (settings.enabled()) {
            builder.setNeutralButton("Turn off", (dialog, which) -> {
                settings.disable();
                SyncJobService.cancel(activity);
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(ignored -> status = null);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            SyncSettings.Target target;
            try {
                target = new SyncSettings.Target(text(host), text(username), text(folder), text(fingerprint)).validated();
            } catch (IllegalArgumentException error) {
                message.accept(error.getMessage());
                return;
            }
            view.setEnabled(false);
            disk.execute(() -> {
                try {
                    settings.publicKey();
                    activity.runOnUiThread(() -> {
                        if (!dialog.isShowing() || activity.isDestroyed()) {
                            return;
                        }
                        if (!target.equals(settings.target())) {
                            SyncJobService.cancel(activity);
                        }
                        settings.save(target);
                        boolean scheduled = SyncJobService.schedule(activity);
                        refresh();
                        view.setEnabled(true);
                        message.accept(scheduled ? "Sync scheduled." : "Android couldn’t schedule sync. Reopen the app to retry.");
                    });
                } catch (IOException | JSchException error) {
                    activity.runOnUiThread(() -> {
                        view.setEnabled(true);
                        message.accept("Could not create the phone’s SSH key.");
                    });
                }
            });
        });
    }

    private void copyKey(Button button) {
        button.setEnabled(false);
        disk.execute(() -> {
            try {
                String key = settings.publicKey();
                activity.runOnUiThread(() -> {
                    button.setEnabled(true);
                    if (activity.isDestroyed()) {
                        return;
                    }
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ambient Recorder public SSH key", key));
                    message.accept("Public key copied.");
                });
            } catch (IOException | JSchException error) {
                activity.runOnUiThread(() -> {
                    button.setEnabled(true);
                    message.accept("Could not create the phone’s SSH key.");
                });
            }
        });
    }

    private EditText field(LinearLayout body, String title, String value) {
        TextView label = style.text(13, RecorderStyle.MUTED, false);
        label.setText(title);
        label.setPadding(0, style.dp(10), 0, 0);
        body.addView(label);
        EditText field = new EditText(activity);
        field.setId(View.generateViewId());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        field.setText(value);
        label.setLabelFor(field.getId());
        body.addView(field);
        return field;
    }

    private static String text(EditText field) { return field.getText().toString().trim(); }
}
