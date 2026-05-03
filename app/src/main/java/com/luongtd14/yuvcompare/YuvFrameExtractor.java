package com.luongtd14.yuvcompare;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

public class YuvFrameExtractor {
    private static final String TAG = "YuvFrameExtractor";
    private MediaExtractor extractor;
    private MediaCodec decoder;
    private int videoTrack;
    private MediaFormat videoFormat;
    private int width, height;
    private boolean isExtractorSetup = false;

    public YuvFrameExtractor(String videoPath) throws Exception {
        extractor = new MediaExtractor();
        extractor.setDataSource(videoPath);
        setupVideoTrack();
    }

    private void setupVideoTrack() throws Exception {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrack = i;
                videoFormat = format;
                width = format.getInteger(MediaFormat.KEY_WIDTH);
                height = format.getInteger(MediaFormat.KEY_HEIGHT);
                extractor.selectTrack(videoTrack);
                isExtractorSetup = true;
                break;
            }
        }
        if (!isExtractorSetup) throw new IllegalArgumentException("No video track found");
        decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(videoFormat, null, null, 0);
        decoder.start();
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /**
     * Trả về mảng [yPlane, uPlane, vPlane] cho khung hình tiếp theo.
     * Trả về null khi hết video.
     */
    public byte[][] getNextYUVFrame() {
        boolean sawEOS = false;
        boolean gotOutput = false;
        byte[][] yuv = null;

        while (!gotOutput && !sawEOS) {
            // Input buffer như cũ...
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                if (sampleSize < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    sawEOS = true;
                } else {
                    long presentationTime = extractor.getSampleTime();
                    decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0);
                    extractor.advance();
                }
            }

            // Output buffer
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
            if (outputIndex >= 0) {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                    yuv = extractYUVPlanes(outputBuffer, info);
                    gotOutput = true;
                }
                decoder.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEOS = true;
            }
        }
        return yuv;
    }

    private byte[][] extractYUVPlanes(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        buffer.position(info.offset);
        byte[] full = new byte[info.size];
        buffer.get(full);

        int ySize = width * height;
        int uvSize = (width / 2) * (height / 2);
        byte[] y = new byte[ySize];
        byte[] u = new byte[uvSize];
        byte[] v = new byte[uvSize];

        System.arraycopy(full, 0, y, 0, ySize);
        System.arraycopy(full, ySize, u, 0, uvSize);
        System.arraycopy(full, ySize + uvSize, v, 0, uvSize);
        return new byte[][]{y, u, v};
    }

    public void release() {
        if (decoder != null) {
            decoder.stop();
            decoder.release();
        }
        if (extractor != null) {
            extractor.release();
        }
    }
}
