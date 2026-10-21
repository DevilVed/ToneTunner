package com.devilved.tonetunner;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.utils.ThemeUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** One place for every app setting; detailed screens open from here. */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.settings_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Whether ToneTunner's keyboard is enabled in Android's input method settings. */
    static boolean isKeyboardEnabled(Context context) {
        InputMethodManager manager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager == null) return false;
        String id = context.getPackageName() + "/" + WhisperInputMethodService.class.getName();
        for (InputMethodInfo method : manager.getEnabledInputMethodList()) {
            if (method.getId().equals(id)) return true;
        }
        return false;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private final ExecutorService summaries = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private int summaryGeneration;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.settings_root, rootKey);
            open("ai_settings", new Intent(requireContext(), AiSettingsActivity.class));
            open("local_model", new Intent(requireContext(), SetupActivity.class));
            open("keyboard", new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            open("voice_input", new Intent(requireContext(), WhisperRecognitionServiceSettingsActivity.class));
            open("source", new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DevilVed/ToneTunner")));
            Preference version = findPreference("version");
            if (version != null) version.setSummary(versionName(requireContext()));
        }

        @Override
        public void onResume() {
            super.onResume();
            Context context = requireContext();
            Preference keyboard = findPreference("keyboard");
            if (keyboard != null) keyboard.setSummary(isKeyboardEnabled(context)
                    ? R.string.settings_keyboard_enabled : R.string.settings_keyboard_disabled);
            refreshSummaries(context.getApplicationContext());
        }

        @Override
        public void onDestroy() {
            ++summaryGeneration;
            summaries.shutdownNow();
            super.onDestroy();
        }

        // Model files and saved-key state are read off the UI thread, like the main screen does.
        private void refreshSummaries(Context context) {
            int generation = ++summaryGeneration;
            try {
                summaries.execute(() -> {
                    String ai;
                    try {
                        ai = new AiSettings(context).getSummary();
                    } catch (RuntimeException e) {
                        ai = context.getString(R.string.ai_key_unavailable_summary);
                    }
                    boolean modelInstalled = Whisper.isModelInstalled(context);
                    String aiSummary = ai;
                    main.post(() -> {
                        if (generation != summaryGeneration || !isAdded()) return;
                        Preference aiPreference = findPreference("ai_settings");
                        if (aiPreference != null) aiPreference.setSummary(aiSummary);
                        Preference model = findPreference("local_model");
                        if (model != null) model.setSummary(modelInstalled
                                ? R.string.settings_local_model_installed : R.string.settings_local_model_missing);
                    });
                });
            } catch (RejectedExecutionException ignored) {
                // The screen is closing.
            }
        }

        private void open(String key, Intent intent) {
            Preference preference = findPreference(key);
            if (preference == null) return;
            preference.setOnPreferenceClickListener(clicked -> {
                try {
                    startActivity(intent);
                } catch (android.content.ActivityNotFoundException ignored) {
                    // No browser or settings app can handle this link on this device.
                }
                return true;
            });
        }

        private static String versionName(Context context) {
            try {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }
    }
}
