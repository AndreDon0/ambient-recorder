package com.dream.ambientrecorder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.regex.Pattern;

record UpdateRelease(long versionCode, String versionName, int minSdk, long size, String sha256, String notes, URL url) {
    static final String REPOSITORY = "AndreDon0/ambient-recorder";
    static final String FEED = "https://raw.githubusercontent.com/" + REPOSITORY + "/main/updates/stable.json";
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]{0,3})(\\.(0|[1-9][0-9]{0,3})){2}");
    static final int MAX_APK_BYTES = 32 * 1024 * 1024;

    static UpdateRelease parse(String document) throws IOException {
        try {
            JSONObject json = new JSONObject(document);
            long code = json.getLong("versionCode");
            String version = json.getString("versionName");
            int sdk = json.getInt("minSdk");
            long length = json.getLong("size");
            String sha = json.getString("sha256");
            String notes = json.getJSONObject("notes").getString("en").trim();
            String asset = "https://github.com/" + REPOSITORY + "/releases/download/v" + version
                    + "/Ambient-Recorder-" + version + ".apk";
            boolean legacy = code == 1 && version.equals("1.0");
            if (json.getInt("schema") != 1 || code < 1 || code > 2_100_000_000L
                    || (!legacy && !VERSION.matcher(version).matches()) || sdk < 29 || length < 1 || length > MAX_APK_BYTES
                    || !SHA256.matcher(sha).matches() || notes.isEmpty() || notes.length() > 4000
                    || !asset.equals(json.getString("url"))) {
                throw new IOException("Invalid release metadata");
            }
            return new UpdateRelease(code, version, sdk, length, sha, notes, new URL(asset));
        } catch (JSONException error) {
            throw new IOException("Invalid release metadata", error);
        }
    }

    String apkName() {
        return sha256 + ".apk";
    }
}
