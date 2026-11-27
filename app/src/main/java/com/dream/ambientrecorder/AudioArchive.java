package com.dream.ambientrecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class AudioArchive {
    enum Mode {
        WAV("wav", "WAV · lossless", "audio/wav"),
        AAC("aac", "AAC · 64 kbps", "audio/aac");

        final String extension;
        final String label;
        final String mimeType;

        Mode(String extension, String label, String mimeType) {
            this.extension = extension;
            this.label = label;
            this.mimeType = mimeType;
        }
    }

    record Clip(File file, long startedAt, long durationMs, long size, Mode mode) {}
    record Draft(File file, int sampleRate, Mode mode) {}

    static final int WAV_HEADER_BYTES = 44;
    static final long FREE_SPACE_RESERVE = 64L * 1024 * 1024;
    private static final int ADTS_HEADER_BYTES = 7;
    private static final int AAC_FRAME_SAMPLES = 1024;
    private static final Set<File> OPEN_DRAFTS = new HashSet<>();
    private static final AtomicLong REVISION = new AtomicLong();
    private final File directory;

    AudioArchive(File privateDirectory) {
        directory = new File(privateDirectory, "recordings");
    }

    static long revision() {
        return REVISION.get();
    }

    long availableBytes() {
        return (directory.isDirectory() ? directory : directory.getParentFile()).getUsableSpace();
    }

    Draft open(long startedAt, int rate, Mode mode) throws IOException {
        synchronized (OPEN_DRAFTS) {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Cannot create recordings directory");
            }
            String name = startedAt + "_" + rate + "_" + UUID.randomUUID() + "." + mode.extension + ".part";
            File file = new File(directory, name);
            if (!file.createNewFile()) {
                throw new IOException("Cannot create recording file");
            }
            OPEN_DRAFTS.add(file);
            return new Draft(file, rate, mode);
        }
    }

    void commit(Draft draft, long samples) throws IOException {
        synchronized (OPEN_DRAFTS) {
            try {
                if (samples == 0) {
                    discardEmpty(draft.file());
                    return;
                }

                long milliseconds = Math.max(1, samples * 1000 / draft.sampleRate());
                String name = stem(draft.file().getName()) + "_" + milliseconds + "." + draft.mode().extension;
                if (!draft.file().renameTo(new File(directory, name))) {
                    throw new IOException("Cannot finalize recording");
                }
                REVISION.incrementAndGet();
            } finally {
                OPEN_DRAFTS.remove(draft.file());
            }
        }
    }

    void leaveForRecovery(Draft draft) {
        synchronized (OPEN_DRAFTS) {
            OPEN_DRAFTS.remove(draft.file());
        }
    }

    void recover() throws IOException {
        synchronized (OPEN_DRAFTS) {
            File[] unfinished = directory.listFiles((parent, name) -> name.endsWith(".part"));
            if (unfinished == null) {
                return;
            }

            IOException failure = null;
            for (File file : unfinished) {
                if (OPEN_DRAFTS.contains(file)) {
                    continue;
                }
                Mode mode = modeOf(file.getName().substring(0, file.getName().length() - 5));
                String[] fields = stem(file.getName()).split("_");
                if (mode == null || fields.length != 3) {
                    continue;
                }
                try {
                    int rate = Integer.parseInt(fields[1]);
                    if (rate != 48000 && rate != 44100) {
                        continue;
                    }
                    long samples = recoverSamples(file, rate, mode);
                    commit(new Draft(file, rate, mode), samples);
                } catch (IOException error) {
                    failure = error;
                } catch (NumberFormatException ignored) {
                    // Only files with a recognized archive name can be recovered.
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    List<Clip> clips() {
        File[] files = directory.listFiles();
        List<Clip> clips = new ArrayList<>();
        if (files == null) {
            return clips;
        }

        for (File file : files) {
            Mode mode = modeOf(file.getName());
            String[] fields = stem(file.getName()).split("_");
            if (mode == null || fields.length != 4 || !file.isFile()) {
                continue;
            }
            try {
                clips.add(new Clip(file, Long.parseLong(fields[0]), Long.parseLong(fields[3]), file.length(), mode));
            } catch (NumberFormatException ignored) {
                // Ignore names outside the archive's format.
            }
        }
        clips.sort(Comparator.comparingLong(Clip::startedAt).reversed().thenComparing(clip -> clip.file().getName()));
        return clips;
    }

    boolean delete(Clip clip) {
        if (!clip.file().delete()) {
            return false;
        }
        REVISION.incrementAndGet();
        return true;
    }

    static void writeWavHeader(RandomAccessFile output, int rate, int dataBytes) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        header.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        header.putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        output.seek(0);
        output.write(header.array());
    }

    private static long recoverSamples(File file, int rate, Mode mode) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            if (mode == Mode.WAV) {
                long bytes = Math.max(0, output.length() - WAV_HEADER_BYTES) & ~1L;
                if (bytes == 0) {
                    return 0;
                }
                output.setLength(WAV_HEADER_BYTES + bytes);
                writeWavHeader(output, rate, Math.toIntExact(bytes));
                return bytes / 2;
            }

            byte[] header = new byte[ADTS_HEADER_BYTES];
            long end = output.length();
            long position = 0;
            long frames = 0;
            while (position + header.length <= end) {
                output.seek(position);
                output.readFully(header);
                if ((header[0] & 0xff) != 0xff || (header[1] & 0xf7) != 0xf1) {
                    break;
                }
                int frameLength = ((header[3] & 3) << 11) | ((header[4] & 0xff) << 3) | ((header[5] & 0xe0) >> 5);
                if (frameLength <= header.length || position + frameLength > end) {
                    break;
                }
                position += frameLength;
                frames++;
            }
            output.setLength(position);
            return frames * AAC_FRAME_SAMPLES;
        }
    }

    private static void discardEmpty(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Cannot remove empty recording");
        }
    }

    private static Mode modeOf(String filename) {
        if (filename.endsWith(".wav")) {
            return Mode.WAV;
        }
        return filename.endsWith(".aac") ? Mode.AAC : null;
    }

    private static String stem(String filename) {
        int firstDot = filename.indexOf('.');
        return firstDot < 0 ? filename : filename.substring(0, firstDot);
    }
}
