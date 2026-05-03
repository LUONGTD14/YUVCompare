package com.luongtd14.yuvcompare;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.luongtd14.yuvcompare.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;
    private static final String input1 = Environment.getExternalStorageDirectory().getAbsolutePath() + "/input1.mp4",
            input2 = Environment.getExternalStorageDirectory().getAbsolutePath() + "/input2.mp4";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requestPermission();

        binding.btnCompare.setOnClickListener(view -> {
            long start = System.nanoTime();
            VideoComparator.compare(input1, input2, new VideoComparator.ComparisonListener() {
                @Override
                public void onProgress(int frameIndex, double psnr, double ssim) {
                    Log.e("luongtd14", String.format("Đang xử lý frame %d\nPSNR: %.2f dB\nSSIM: %.4f", frameIndex, psnr, ssim));
                }

                @Override
                public void onComplete(int total, double avgPsnr, double avgSsim) {
                    Log.e("luongtd14", String.format("Kết quả trung bình (%d frames):\nPSNR = %.2f dB\nSSIM = %.4f", total, avgPsnr, avgSsim));
                    Log.e("luongtd14", String.format("Time = %d (ms)", (System.nanoTime() - start) / 1000000));
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> {
                        Log.e("luongtd14","Lỗi: " + e.getMessage());
                        e.printStackTrace();
                    });
                }
            });
        });

    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }
        }
    }
}