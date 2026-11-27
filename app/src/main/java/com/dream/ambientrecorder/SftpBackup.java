package com.dream.ambientrecorder;

import android.net.Network;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.BooleanSupplier;

final class SftpBackup implements AutoCloseable {
    private static final int CONNECT_MS = 4500;
    private static final int IO_TIMEOUT_MS = 15000;
    private static final String ECDSA = "ecdsa-sha2-nistp256";
    private final SyncSettings.Target target;
    private final BooleanSupplier active;
    private volatile Session session;
    private volatile Socket socket;
    private ChannelSftp channel;
    private volatile boolean keyRejected;

    SftpBackup(SyncSettings.Target target, BooleanSupplier active) {
        this.target = target;
        this.active = active;
    }

    void connect(Network network, byte[] privateKey) throws IOException, JSchException, SftpException {
        requireActive();
        JSch ssh = new JSch();
        ssh.addIdentity("ambient-recorder", privateKey, null, null);
        ssh.setHostKeyRepository(new PinnedHostKey());
        try {
            session = ssh.getSession(target.username(), target.host(), 22);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey");
            session.setConfig("server_host_key", ECDSA);
            session.setConfig("PubkeyAcceptedAlgorithms", ECDSA);
            session.setConfig("kex", "ecdh-sha2-nistp256");
            session.setSocketFactory(new BoundSocketFactory(network));
            session.connect(CONNECT_MS);
            session.setTimeout(IO_TIMEOUT_MS);
            requireActive();
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CONNECT_MS);
            channel.cd(target.directory());
        } finally {
            ssh.removeAllIdentity();
        }
    }

    boolean keyRejected() { return keyRejected; }

    void abort() {
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // The worker still closes the SSH session.
            }
        }
    }

    void upload(AudioArchive.Clip clip) throws IOException, SftpException {
        requireActive();
        String name = clip.file().getName();
        if (!name.matches("[0-9]+_[0-9]+_[0-9a-f-]{36}_[0-9]+\\.(wav|aac)")) {
            throw new IOException("Unexpected recording filename");
        }
        SftpATTRS existing = attributes(name);
        if (existing != null) {
            if (existing.isReg() && existing.getSize() == clip.size()) {
                return;
            }
            throw new IOException("A different server file already has this name");
        }

        String partial = "." + name + ".uploading";
        if (attributes(partial) != null) {
            channel.rm(partial);
        }
        try (InputStream audio = new FileInputStream(clip.file())) {
            channel.put(audio, partial, new SftpProgressMonitor() {
                @Override public void init(int operation, String source, String destination, long size) {}
                @Override public boolean count(long count) { return active.getAsBoolean(); }
                @Override public void end() {}
            }, ChannelSftp.OVERWRITE);
        }
        requireActive();
        SftpATTRS uploaded = channel.lstat(partial);
        if (!uploaded.isReg() || uploaded.getSize() != clip.size()) {
            throw new IOException("Server received an incomplete recording");
        }
        channel.rename(partial, name);
    }

    private SftpATTRS attributes(String name) throws SftpException {
        try {
            return channel.lstat(name);
        } catch (SftpException error) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw error;
        }
    }

    private void requireActive() throws InterruptedIOException {
        if (!active.getAsBoolean()) {
            throw new InterruptedIOException("Sync paused");
        }
    }

    @Override
    public void close() {
        Session current = session;
        if (current != null) {
            current.disconnect();
        }
    }

    static String fingerprint(byte[] key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private final class PinnedHostKey implements HostKeyRepository {
        @Override
        public int check(String host, byte[] key) {
            boolean matches = target.host().equals(host) && MessageDigest.isEqual(
                    fingerprint(key).getBytes(StandardCharsets.US_ASCII),
                    target.fingerprint().getBytes(StandardCharsets.US_ASCII));
            keyRejected = !matches;
            return matches ? OK : CHANGED;
        }

        @Override public void add(HostKey key, UserInfo userInfo) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "Ambient Recorder"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private final class BoundSocketFactory implements SocketFactory {
        private final Network network;
        BoundSocketFactory(Network network) { this.network = network; }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            requireActive();
            if (!target.host().equals(host) || port != 22) {
                throw new IOException("Unexpected SSH destination");
            }
            Socket socket = network.getSocketFactory().createSocket();
            SftpBackup.this.socket = socket;
            try {
                requireActive();
                socket.setSoTimeout(IO_TIMEOUT_MS);
                socket.connect(new InetSocketAddress(host, port), CONNECT_MS);
                return socket;
            } catch (IOException | RuntimeException error) {
                socket.close();
                throw error;
            }
        }

        @Override public InputStream getInputStream(Socket socket) throws IOException { return socket.getInputStream(); }
        @Override public OutputStream getOutputStream(Socket socket) throws IOException { return socket.getOutputStream(); }
    }
}
