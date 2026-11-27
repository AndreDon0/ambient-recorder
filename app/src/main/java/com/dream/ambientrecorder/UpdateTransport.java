package com.dream.ambientrecorder;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class UpdateTransport {
    private static final int MAX_FEED_BYTES = 32 * 1024;
    private static final int TIMEOUT_MS = 5000;

    private UpdateTransport() {}

    static UpdateRelease latest(BooleanSupplier allowed) throws IOException {
        HttpURLConnection connection = connect(null, new URL(UpdateRelease.FEED), 0);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Update feed unavailable");
            }
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    requireActive(allowed);
                    if (body.size() + count > MAX_FEED_BYTES) {
                        throw new IOException("Update feed is too large");
                    }
                    body.write(buffer, 0, count);
                }
                requireActive(allowed);
                return UpdateRelease.parse(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    static void download(Network network, UpdateRelease release, File partial, BooleanSupplier allowed,
                         IntConsumer progress) throws IOException {
        long offset = partial.length();
        if (offset >= release.size()) {
            if (offset == release.size()) {
                return;
            }
            if (!partial.delete()) {
                throw new IOException("Could not reset update download");
            }
            offset = 0;
        }

        URL url = release.url();
        for (int redirect = 0; redirect < 4; redirect++) {
            requireActive(allowed);
            HttpURLConnection connection = connect(network, url, offset);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("Missing release redirect");
                    }
                    url = new URL(url, location);
                    if (!permittedRedirect(url)) {
                        throw new IOException("Unexpected release redirect");
                    }
                    continue;
                }
                receive(connection, status, release.size(), partial, offset, allowed, progress);
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many release redirects");
    }

    private static void receive(HttpURLConnection connection, int status, long size, File partial, long offset,
                                BooleanSupplier allowed, IntConsumer progress) throws IOException {
        if (status == HttpURLConnection.HTTP_OK) {
            offset = 0;
        } else if (status != HttpURLConnection.HTTP_PARTIAL || offset == 0
                || !validRange(connection.getHeaderField("Content-Range"), offset, size)) {
            throw new IOException("Invalid update download response");
        }

        long length = connection.getContentLengthLong();
        if (length >= 0 && length != size - offset) {
            throw new IOException("Unexpected APK size");
        }

        requireActive(allowed);
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
            byte[] buffer = new byte[32 * 1024];
            long received = offset;
            int displayed = -1;
            while (received < size) {
                requireActive(allowed);
                int count = input.read(buffer);
                if (count < 0 || received + count > size) {
                    throw new IOException("Incomplete or oversized APK");
                }
                output.write(buffer, 0, count);
                received += count;
                int percent = (int) (100 * received / size);
                if (percent != displayed) {
                    progress.accept(percent);
                    displayed = percent;
                }
            }
        }
    }

    static boolean validRange(String header, long offset, long size) {
        return ("bytes " + offset + "-" + (size - 1) + "/" + size).equals(header);
    }

    static boolean permittedRedirect(URL url) {
        return url.getProtocol().equals("https") && url.getUserInfo() == null && url.getRef() == null
                && (url.getPort() == -1 || url.getPort() == 443)
                && (url.getHost().equals("release-assets.githubusercontent.com")
                    || url.getHost().equals("objects.githubusercontent.com"));
    }

    private static HttpURLConnection connect(Network network, URL url, long offset) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        return connection;
    }

    static void requireActive(BooleanSupplier allowed) throws InterruptedIOException {
        if (!allowed.getAsBoolean()) {
            throw new InterruptedIOException("Update paused");
        }
    }
}
