package com.dream.ambientrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class SyncSettings {
    record Target(String host, String username, String directory, String fingerprint) {
        Target validated() {
            if (!privateIpv4(host) || !username.matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("Enter a private IPv4 address and an SSH username.");
            }
            if (!directory.matches("(/[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}){1,16}")) {
                throw new IllegalArgumentException("Enter the full destination path on the server.");
            }
            if (!fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
                throw new IllegalArgumentException("Enter the server’s SHA256 SSH host fingerprint.");
            }
            return this;
        }

        private static boolean privateIpv4(String address) {
            if (!address.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) {
                return false;
            }
            int[] octets = Arrays.stream(address.split("\\.")).mapToInt(Integer::parseInt).toArray();
            if (Arrays.stream(octets).anyMatch(value -> value > 255)) {
                return false;
            }
            return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168);
        }
    }

    private final SharedPreferences prefs;
    private final AtomicFile identity;

    SyncSettings(Context context) {
        prefs = context.getSharedPreferences("local_backup", Context.MODE_PRIVATE);
        identity = new AtomicFile(new File(context.getNoBackupFilesDir(), "backup-identity"));
    }

    Target target() {
        return new Target(prefs.getString("host", ""), prefs.getString("username", ""),
                prefs.getString("directory", ""), prefs.getString("fingerprint", ""));
    }

    boolean enabled() { return prefs.getBoolean("enabled", false); }
    String message() {
        return enabled() ? prefs.getString("status", "Waiting for your Wi-Fi.") : "Sync is off.";
    }

    void save(Target target) {
        prefs.edit().putString("host", target.host()).putString("username", target.username())
                .putString("directory", target.directory()).putString("fingerprint", target.fingerprint())
                .putBoolean("enabled", true).putString("status", "Waiting for your Wi-Fi.").apply();
    }

    void disable() {
        prefs.edit().putBoolean("enabled", false).putString("status", "Sync is off.").apply();
    }

    void report(Target target, String message) {
        if (enabled() && target.equals(target())) {
            prefs.edit().putString("status", message).apply();
        }
    }

    String publicKey() throws IOException, JSchException {
        byte[] bytes = privateKey(true);
        KeyPair pair = null;
        try {
            pair = KeyPair.load(new JSch(), bytes, null);
            return "ecdsa-sha2-nistp256 " + android.util.Base64.encodeToString(pair.getPublicKeyBlob(),
                    android.util.Base64.NO_WRAP) + " ambient-recorder";
        } finally {
            if (pair != null) {
                pair.dispose();
            }
            Arrays.fill(bytes, (byte) 0);
        }
    }

    byte[] privateKey(boolean create) throws IOException, JSchException {
        synchronized (SyncSettings.class) {
            try {
                return identity.readFully();
            } catch (FileNotFoundException missing) {
                if (!create) {
                    throw missing;
                }
            }
            KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256);
            try {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                pair.writePrivateKey(encoded);
                FileOutputStream output = identity.startWrite();
                try {
                    encoded.writeTo(output);
                    identity.finishWrite(output);
                } catch (IOException | RuntimeException error) {
                    identity.failWrite(output);
                    throw error;
                }
            } finally {
                pair.dispose();
            }
            return identity.readFully();
        }
    }
}
