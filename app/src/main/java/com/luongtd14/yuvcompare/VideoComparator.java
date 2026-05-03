package com.luongtd14.yuvcompare;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoComparator {
    public interface ComparisonListener {
        void onProgress(int frameIndex, double psnr, double ssim);
        void onComplete(int total, double avgPsnr, double avgSsim);
        void onError(Exception e);
    }

    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void compare(String path1, String path2, ComparisonListener listener) {
        executor.execute(() -> {
            double sumMseWeighted = 0;  // tốt hơn: tính tổng MSE weighted rồi chia trung bình
            double sumSsim = 0;
            int framesCompared = 0;
            Exception error = null;
            YuvFrameExtractor ext1 = null, ext2 = null;

            try {
                ext1 = new YuvFrameExtractor(path1);
                ext2 = new YuvFrameExtractor(path2);

                int w1 = ext1.getWidth(), h1 = ext1.getHeight();
                int w2 = ext2.getWidth(), h2 = ext2.getHeight();

                // Căn chỉnh kích thước về chung (target)
                boolean needResize = (w1 != w2 || h1 != h2);
                int targetW = Math.min(w1, w2);
                int targetH = Math.min(w2, h2);
                int targetUvW = targetW / 2;
                int targetUvH = targetH / 2;

                byte[][] frame1, frame2;
                while ((frame1 = ext1.getNextYUVFrame()) != null && (frame2 = ext2.getNextYUVFrame()) != null) {
                    byte[] y1 = frame1[0], u1 = frame1[1], v1 = frame1[2];
                    byte[] y2 = frame2[0], u2 = frame2[1], v2 = frame2[2];

                    if (needResize) {
                        // Resize Y, U, V riêng
                        y1 = resizeY(y1, w1, h1, targetW, targetH);
                        y2 = resizeY(y2, w2, h2, targetW, targetH);
                        u1 = resizeUV(u1, w1/2, h1/2, targetUvW, targetUvH);
                        u2 = resizeUV(u2, w2/2, h2/2, targetUvW, targetUvH);
                        v1 = resizeUV(v1, w1/2, h1/2, targetUvW, targetUvH);
                        v2 = resizeUV(v2, w2/2, h2/2, targetUvW, targetUvH);
                    }

                    // MSE từng kênh
                    double mseY = computeMSE(y1, y2);
                    double mseU = computeMSE(u1, u2);
                    double mseV = computeMSE(v1, v2);

                    // Trọng số 6:1:1 -> MSE weighted
                    double weightedMse = (6 * mseY + mseU + mseV) / 8.0;

                    double psnr = weightedMse > 0 ? 10 * Math.log10((255 * 255) / weightedMse) : 100;

                    // SSIM vẫn tính trên Y (có thể mở rộng nếu muốn)
                    double ssim = SSIMCalculator.calculate(y1, y2, targetW, targetH);

                    sumMseWeighted += weightedMse;  // hoặc sumPsnr += psnr tuỳ cách
                    sumSsim += ssim;
                    framesCompared++;

                    final int currentFrame = framesCompared;
                    final double currentPsnr = psnr;
                    final double currentSsim = ssim;
                    mainHandler.post(() -> listener.onProgress(currentFrame, currentPsnr, currentSsim));
                }

                double avgWeightedMse = sumMseWeighted / framesCompared;
                double avgPsnrWeighted = avgWeightedMse > 0 ? 10 * Math.log10((255 * 255) / avgWeightedMse) : 100;
                double avgSsim = sumSsim / framesCompared; // nếu bạn lưu sumSsim

                final double finalAvgPsnr = avgPsnrWeighted;
                final double finalAvgSsim = avgSsim;
                int finalFramesCompared = framesCompared;
                mainHandler.post(() -> listener.onComplete(finalFramesCompared, finalAvgPsnr, finalAvgSsim));

            } catch (Exception e) {
                error = e;
            } finally {
                if (ext1 != null) ext1.release();
                if (ext2 != null) ext2.release();
            }

            // xử lý error ...
        });
    }

    private static double computeMSE(byte[] a, byte[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Size mismatch");
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            sum += diff * diff;
        }
        return sum / a.length;
    }

    private static byte[] resizeY(byte[] srcY, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH];
        float scaleX = (float) srcW / dstW;
        float scaleY = (float) srcH / dstH;
        for (int y = 0; y < dstH; y++) {
            int srcY0 = (int) (y * scaleY);
            int srcY1 = Math.min(srcY0 + 1, srcH - 1);
            float fy = (y * scaleY) - srcY0;
            for (int x = 0; x < dstW; x++) {
                int srcX0 = (int) (x * scaleX);
                int srcX1 = Math.min(srcX0 + 1, srcW - 1);
                float fx = (x * scaleX) - srcX0;
                int v00 = srcY[srcY0 * srcW + srcX0] & 0xFF;
                int v01 = srcY[srcY0 * srcW + srcX1] & 0xFF;
                int v10 = srcY[srcY1 * srcW + srcX0] & 0xFF;
                int v11 = srcY[srcY1 * srcW + srcX1] & 0xFF;
                float interp = (v00 * (1 - fx) + v01 * fx) * (1 - fy) +
                        (v10 * (1 - fx) + v11 * fx) * fy;
                dst[y * dstW + x] = (byte) Math.round(interp);
            }
        }
        return dst;
    }

    private static byte[] resizeUV(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        if (srcW == dstW && srcH == dstH) return src;
        byte[] dst = new byte[dstW * dstH];
        float scaleX = (float) srcW / dstW;
        float scaleY = (float) srcH / dstH;
        for (int y = 0; y < dstH; y++) {
            int srcY0 = (int) (y * scaleY);
            int srcY1 = Math.min(srcY0 + 1, srcH - 1);
            float fy = (y * scaleY) - srcY0;
            for (int x = 0; x < dstW; x++) {
                int srcX0 = (int) (x * scaleX);
                int srcX1 = Math.min(srcX0 + 1, srcW - 1);
                float fx = (x * scaleX) - srcX0;
                int v00 = src[srcY0 * srcW + srcX0] & 0xFF;
                int v01 = src[srcY0 * srcW + srcX1] & 0xFF;
                int v10 = src[srcY1 * srcW + srcX0] & 0xFF;
                int v11 = src[srcY1 * srcW + srcX1] & 0xFF;
                float interp = (v00 * (1 - fx) + v01 * fx) * (1 - fy) +
                        (v10 * (1 - fx) + v11 * fx) * fy;
                dst[y * dstW + x] = (byte) Math.round(interp);
            }
        }
        return dst;
    }
}
