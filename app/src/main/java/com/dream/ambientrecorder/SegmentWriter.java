package com.dream.ambientrecorder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Objects;

final class SegmentWriter implements AutoCloseable {
    static final int BATTERY_BITRATE = 64_000;
    private final AudioArchive archive;
    private final AudioArchive.Draft draft;
    private final AudioSink sink;
    private long samples;
    private boolean writeFailed;

    SegmentWriter(AudioArchive archive, long startedAt, int rate, AudioArchive.Mode mode) throws IOException {
        this.archive = archive;
        draft = archive.open(startedAt, rate, mode);
        try {
            sink = mode == AudioArchive.Mode.WAV
                    ? new WavSink(draft.file(), rate)
                    : new AacSink(draft.file(), rate);
        } catch (IOException | RuntimeException error) {
            archive.leaveForRecovery(draft);
            throw error;
        }
    }

    long samples() {
        return samples;
    }

    AudioArchive.Mode mode() {
        return draft.mode();
    }

    void write(byte[] pcm, int bytes) throws IOException {
        try {
            sink.write(pcm, bytes);
            samples += bytes / 2;
        } catch (IOException | RuntimeException error) {
            writeFailed = true;
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            boolean hasAudio = sink.finish();
            if (!writeFailed) {
                archive.commit(draft, hasAudio ? samples : 0);
            }
        } finally {
            archive.leaveForRecovery(draft);
        }
    }

    private interface AudioSink {
        void write(byte[] pcm, int bytes) throws IOException;
        boolean finish() throws IOException;
    }

    private static final class WavSink implements AudioSink {
        private final RandomAccessFile file;
        private final int sampleRate;

        WavSink(File path, int sampleRate) throws IOException {
            this.sampleRate = sampleRate;
            file = new RandomAccessFile(path, "rw");
            try {
                AudioArchive.writeWavHeader(file, sampleRate, 0);
            } catch (IOException error) {
                file.close();
                throw error;
            }
        }

        @Override
        public void write(byte[] pcm, int bytes) throws IOException {
            file.write(pcm, 0, bytes);
        }

        @Override
        public boolean finish() throws IOException {
            try (file) {
                int dataBytes = Math.toIntExact(file.length() - AudioArchive.WAV_HEADER_BYTES) & ~1;
                file.setLength(AudioArchive.WAV_HEADER_BYTES + (long) dataBytes);
                AudioArchive.writeWavHeader(file, sampleRate, dataBytes);
                return dataBytes > 0;
            }
        }
    }

    private static final class AacSink implements AudioSink {
        private static final int HEADER_BYTES = 7;
        private static final long TIMEOUT_MS = 3000;
        private final int sampleRate;
        private final int frequencyIndex;
        private final BufferedOutputStream output;
        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final byte[] packet = new byte[8191];
        private long framesQueued;
        private long packetsWritten;
        private long lastFlush = SystemClock.elapsedRealtime();

        AacSink(File path, int sampleRate) throws IOException {
            this.sampleRate = sampleRate;
            frequencyIndex = sampleRate == 48000 ? 3 : 4;
            output = new BufferedOutputStream(new FileOutputStream(path));
            MediaCodec encoder = null;
            try {
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BATTERY_BITRATE);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
            } catch (IOException | RuntimeException error) {
                try (output) {
                    if (encoder != null) {
                        encoder.release();
                    }
                    throw error;
                }
            }
            codec = encoder;
        }

        @Override
        public void write(byte[] pcm, int bytes) throws IOException {
            int position = 0;
            while (position < bytes) {
                int index = inputBuffer();
                ByteBuffer input = Objects.requireNonNull(codec.getInputBuffer(index));
                int count = Math.min(input.remaining(), bytes - position) & ~1;
                if (count == 0) {
                    throw new IOException("AAC encoder returned an empty input buffer");
                }
                input.put(pcm, position, count);
                codec.queueInputBuffer(index, 0, count, presentationTime(), 0);
                framesQueued += count / 2;
                position += count;
                while (drain(0) > 0) {
                    // Drain available output before requesting more input.
                }
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastFlush >= 1000) {
                output.flush();
                lastFlush = now;
            }
        }

        @Override
        public boolean finish() throws IOException {
            try (output) {
                if (framesQueued != 0) {
                    codec.queueInputBuffer(inputBuffer(), 0, 0, presentationTime(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
                    while (drain(10_000) >= 0) {
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            throw new IOException("Timed out finishing AAC recording");
                        }
                    }
                    if (packetsWritten == 0) {
                        throw new IOException("AAC encoder produced no audio");
                    }
                }
                return packetsWritten > 0;
            } finally {
                codec.release();
            }
        }

        private int inputBuffer() throws IOException {
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            while (SystemClock.elapsedRealtime() < deadline) {
                int index = codec.dequeueInputBuffer(5_000);
                if (index >= 0) {
                    return index;
                }
                drain(0);
            }
            throw new IOException("AAC encoder stopped accepting audio");
        }

        private long presentationTime() {
            return framesQueued * 1_000_000 / sampleRate;
        }

        private int drain(long timeoutUs) throws IOException {
            int index = codec.dequeueOutputBuffer(info, timeoutUs);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return 1;
            }
            if (index < 0) {
                return 0;
            }
            try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer encoded = Objects.requireNonNull(codec.getOutputBuffer(index));
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    writePacket(encoded);
                }
                return (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0 ? 1 : -1;
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }

        private void writePacket(ByteBuffer encoded) throws IOException {
            int size = HEADER_BYTES + encoded.remaining();
            if (size > packet.length) {
                throw new IOException("AAC frame exceeds the ADTS size limit");
            }
            packet[0] = (byte) 0xff;
            packet[1] = (byte) 0xf1;
            packet[2] = (byte) (0x40 | (frequencyIndex << 2)); // AAC-LC, mono.
            packet[3] = (byte) (0x40 | (size >> 11));
            packet[4] = (byte) (size >> 3);
            packet[5] = (byte) (((size & 7) << 5) | 0x1f);
            packet[6] = (byte) 0xfc;
            encoded.get(packet, HEADER_BYTES, size - HEADER_BYTES);
            output.write(packet, 0, size);
            packetsWritten++;
        }
    }
}
