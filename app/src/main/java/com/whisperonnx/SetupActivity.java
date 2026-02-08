package com.whisperonnx;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.whisperonnx.utils.ThemeUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SetupActivity extends AppCompatActivity {
    private static final String TAG = "SetupActivity";
    ActivityResultLauncher<Intent> install;
    ProgressBar progressBar;
    TextView extractedFileTV;
    Button startButton;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        progressBar = findViewById(R.id.progress_bar);
        extractedFileTV = findViewById(R.id.extracted_file);
        startButton = findViewById(R.id.button_start);

        File sdcardDataFolder = getExternalFilesDir(null);

        install = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getData()!=null && result.getData().getData()!=null) zipExtract(this, sdcardDataFolder, result.getData().getData());
                });

    }

     public void downloadModel(View v){
         Toast.makeText(this,"Download",Toast.LENGTH_SHORT).show();
         startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/DocWolle/whisperOnnx/blob/main/whisper_small_int8.zip")));
     }
    public void installModel(View v){
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/zip");
        install.launch(intent);

    }

    public void startMain(View v){
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    public void zipExtract(Context context, File targetDir, Uri zipFile) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        startButton.setVisibility(View.GONE);

        Thread thread = new Thread(() -> {
            ZipEntry zipEntry;
            int readLen;
            byte[] readBuffer = new byte[4096];
            boolean success = false;
            String errorMessage = "";

            try {
                InputStream src = context.getContentResolver().openInputStream(zipFile);
                if (src == null) {
                    throw new IOException("Cannot open input stream for selected file");
                }

                try (ZipInputStream zipInputStream = new ZipInputStream(src)) {
                    // Pre-compute target directory canonical path for performance and security
                    String canonicalTarget = targetDir.getCanonicalPath();
                    if (!canonicalTarget.endsWith(File.separator)) {
                        canonicalTarget += File.separator;
                    }

                    int fileCount = 0;
                    long lastUiUpdateTime = 0;

                    while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                        String entryName = zipEntry.getName();

                        // Skip macOS metadata files and directories
                        if (entryName.startsWith("__MACOSX") || entryName.startsWith("._") || zipEntry.isDirectory()) {
                            zipInputStream.closeEntry();
                            continue;
                        }

                        File extractedFile = new File(targetDir, entryName);

                        // Zip Slip protection
                        String canonicalPath = extractedFile.getCanonicalPath();
                        if (!canonicalPath.startsWith(canonicalTarget)) {
                            throw new SecurityException("Zip Slip vulnerability detected: " + canonicalPath);
                        }

                        // Ensure parent directories exist
                        File parentDir = extractedFile.getParentFile();
                        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                            throw new IOException("Failed to create directory " + parentDir);
                        }

                        // Throttle UI updates to every 100ms
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUiUpdateTime > 100) {
                            final String fileName = extractedFile.getName();
                            runOnUiThread(() -> {
                                extractedFileTV.setVisibility(View.VISIBLE);
                                extractedFileTV.setText(fileName);
                            });
                            lastUiUpdateTime = currentTime;
                        }

                        try (OutputStream outputStream = Files.newOutputStream(extractedFile.toPath())) {
                            while ((readLen = zipInputStream.read(readBuffer)) != -1) {
                                outputStream.write(readBuffer, 0, readLen);
                            }
                        }
                        zipInputStream.closeEntry();
                        fileCount++;
                    }

                    if (fileCount > 0) {
                        success = true;
                    } else {
                        errorMessage = "No valid files found in ZIP";
                    }
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found", e);
                errorMessage = "Selected file not found";
            } catch (IOException e) {
                Log.e(TAG, "Error extracting ZIP", e);
                errorMessage = "Extraction failed: " + e.getMessage();
            } catch (SecurityException e) {
                Log.e(TAG, "Security violation", e);
                errorMessage = e.getMessage();
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error", e);
                errorMessage = "Unexpected error: " + e.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalError = errorMessage;

            runOnUiThread(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setVisibility(View.GONE);
                extractedFileTV.setVisibility(View.GONE);

                if (finalSuccess) {
                    startButton.setVisibility(View.VISIBLE);
                    Toast.makeText(context, "Model installed successfully", Toast.LENGTH_SHORT).show();
                } else {
                    extractedFileTV.setVisibility(View.VISIBLE);
                    extractedFileTV.setText(finalError);
                    extractedFileTV.setTextColor(0xFFFF0000); // Red color for error
                    startButton.setVisibility(View.GONE);
                    Toast.makeText(context, finalError, Toast.LENGTH_LONG).show();
                }
            });
        });
        thread.start();
    }
}
