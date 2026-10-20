package com.devilved.tonetunner;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.cloud.SecretStore;
import com.devilved.tonetunner.utils.ThemeUtils;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Secure, separate configuration screen for transcription and text enhancement providers. */
public final class AiSettingsActivity extends AppCompatActivity {
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();

    private AiSettings settings;
    private SecretStore secretStore;
    private StageViews transcription;
    private StageViews enhancement;
    private MaterialSwitch enhancementEnabled;
    private LinearLayout enhancementFields;
    private TextInputEditText promptInput;
    private TextInputEditText vocabularyInput;
    private CheckBox cloudConsent;
    private TextView storageStatus;
    private CloudClient activeDiscoveryClient;
    private int discoveryGeneration;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        ThemeUtils.setStatusBarAppearance(this);
        settings = new AiSettings(this);
        secretStore = new SecretStore(this);

        ProviderConfig transcriptionConfig;
        ProviderConfig enhancementConfig;
        boolean storageUnavailable = false;
        try {
            transcriptionConfig = settings.getTranscriptionConfig();
            enhancementConfig = settings.getEnhancementConfig();
        } catch (RuntimeException e) {
            transcriptionConfig = defaultConfig(Provider.GROQ, true);
            enhancementConfig = defaultConfig(Provider.OPENROUTER, false);
            storageUnavailable = true;
        }
        buildScreen(transcriptionConfig, enhancementConfig, settings.isEnhancementEnabled(), storageUnavailable);
        if (storageUnavailable && storageStatus != null) storageStatus.setText(R.string.ai_secure_storage_error);
    }

    private void buildScreen(ProviderConfig transcriptionConfig, ProviderConfig enhancementConfig,
                             boolean enhancementOn, boolean storageUnavailable) {
        int horizontal = dp(18);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(horizontal, dp(8), horizontal, dp(8));
        Button back = new Button(this);
        back.setText(R.string.ai_cancel);
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(ViewGroupWidth.WRAP_CONTENT, dp(48)));
        TextView title = new TextView(this);
        title.setText(R.string.ai_settings_title);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroupWidth.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        toolbar.addView(title, titleParams);
        page.addView(toolbar, new LinearLayout.LayoutParams(ViewGroupWidth.MATCH_PARENT, ViewGroupWidth.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontal, dp(2), horizontal, dp(20));

        TextView introduction = bodyText(R.string.ai_settings_intro);
        introduction.setPadding(dp(4), dp(4), dp(4), dp(16));
        content.addView(introduction, matchWrap());

        content.addView(sectionHeading(R.string.ai_transcription_heading, R.string.ai_transcription_description),
                matchWrap());
        transcription = new StageViews(true, transcriptionConfig);
        content.addView(transcription.card, cardParams());

        content.addView(sectionHeading(R.string.ai_enhancement_heading, R.string.ai_enhancement_description),
                matchWrap());
        MaterialCardView enhancementCard = new MaterialCardView(this);
        enhancementCard.setUseCompatPadding(false);
        enhancementCard.setRadius(dp(18));
        enhancementCard.setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer));
        enhancementCard.setStrokeWidth(dp(1));
        enhancementCard.setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutline));
        LinearLayout enhancementCardBody = cardBody();
        enhancementEnabled = new MaterialSwitch(this);
        enhancementEnabled.setText(R.string.ai_enhancement_enabled);
        enhancementEnabled.setChecked(enhancementOn);
        enhancementCardBody.addView(enhancementEnabled, matchWrap());
        enhancementFields = new LinearLayout(this);
        enhancementFields.setOrientation(LinearLayout.VERTICAL);
        enhancement = new StageViews(false, enhancementConfig);
        enhancementFields.addView(enhancement.card, matchWrap());

        promptInput = addTextInput(enhancementFields, R.string.ai_prompt, settings.getEnhancementPrompt(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_NONE, true);
        promptInput.setMinLines(3);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        vocabularyInput = addTextInput(enhancementFields, R.string.ai_vocabulary,
                settings.getVocabulary(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_NONE, true);
        vocabularyInput.setMinLines(2);
        vocabularyInput.setGravity(Gravity.TOP | Gravity.START);
        TextView vocabularyNote = smallText(R.string.ai_vocabulary_hint);
        enhancementFields.addView(vocabularyNote, matchWrap());
        enhancementCardBody.addView(enhancementFields, matchWrap());
        enhancementCard.addView(enhancementCardBody);
        content.addView(enhancementCard, cardParams());
        enhancementEnabled.setOnCheckedChangeListener((button, checked) -> setEnhancementFieldsEnabled(checked));
        setEnhancementFieldsEnabled(enhancementOn);

        TextView costDisclosure = bodyText(R.string.ai_cost_notice);
        costDisclosure.setPadding(dp(4), dp(14), dp(4), dp(4));
        content.addView(costDisclosure, matchWrap());
        TextView privacyDisclosure = bodyText(R.string.ai_privacy_notice);
        privacyDisclosure.setPadding(dp(4), dp(4), dp(4), dp(12));
        content.addView(privacyDisclosure, matchWrap());

        cloudConsent = new CheckBox(this);
        cloudConsent.setText(R.string.ai_cloud_consent);
        cloudConsent.setChecked(settings.isCloudConsentGranted());
        cloudConsent.setSaveEnabled(false);
        content.addView(cloudConsent, matchWrap());
        storageStatus = smallText(0);
        content.addView(storageStatus, matchWrap());

        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroupWidth.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText(R.string.ai_save);
        save.setAllCaps(false);
        save.setEnabled(!storageUnavailable);
        save.setOnClickListener(v -> saveSettings());
        LinearLayout saveContainer = new LinearLayout(this);
        saveContainer.setPadding(horizontal, dp(8), horizontal, dp(12));
        saveContainer.addView(save, new LinearLayout.LayoutParams(ViewGroupWidth.MATCH_PARENT, dp(52)));
        page.addView(saveContainer, matchWrap());

        setContentView(page);
    }

    private void saveSettings() {
        boolean enabled = enhancementEnabled.isChecked();
        boolean cloud = transcription.provider != Provider.LOCAL
                || (enabled && enhancement.provider != Provider.LOCAL);
        if (cloud && !cloudConsent.isChecked()) {
            cloudConsent.requestFocus();
            toast(R.string.ai_cloud_consent_required);
            return;
        }

        ProviderConfig transcriptionConfig = transcription.currentConfig();
        ProviderConfig enhancementConfig = enhancement.currentConfig();
        try {
            settings.saveSettings(transcriptionConfig, enabled, enhancementConfig,
                    textOf(promptInput), textOf(vocabularyInput), cloudConsent.isChecked());
        } catch (IllegalArgumentException e) {
            showMessage(e.getMessage(), R.string.ai_missing_api_key);
            return;
        } catch (IOException e) {
            showMessage(e.getMessage(), R.string.ai_secure_storage_error);
            return;
        } catch (RuntimeException e) {
            toast(R.string.ai_secure_storage_error);
            return;
        }

        transcription.clearEnteredKey();
        enhancement.clearEnteredKey();
        transcription.refreshKeyStatus();
        enhancement.refreshKeyStatus();
        toast(R.string.ai_saved);
    }

    private void showMessage(String message, int fallback) {
        Toast.makeText(this, message == null || message.isEmpty() ? getString(fallback) : message,
                Toast.LENGTH_LONG).show();
    }

    private void setEnhancementFieldsEnabled(boolean enabled) {
        if (enhancementFields == null) return;
        if (!enabled) invalidateDiscovery();
        enhancementFields.setEnabled(enabled);
        setEnabledRecursive(enhancementFields, enabled);
        enhancementFields.setAlpha(enabled ? 1f : 0.55f);
        if (enhancement != null) {
            enhancement.active = enabled && enhancement.provider != Provider.LOCAL;
            enhancement.applyProviderVisibility();
            enhancement.refreshKeyStatus();
        }
    }

    private void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledRecursive(group.getChildAt(i), enabled);
        }
    }

    private void startModelDiscovery(StageViews stage) {
        if (!stage.active) {
            toast(R.string.ai_missing_api_key);
            return;
        }
        ProviderConfig config = stage.configForDiscovery();
        if (config == null) return;

        int generation = ++discoveryGeneration;
        if (activeDiscoveryClient != null) activeDiscoveryClient.cancel();
        CloudClient client = new CloudClient();
        activeDiscoveryClient = client;
        stage.refresh.setEnabled(false);
        stage.refresh.setText(R.string.ai_model_discovery_loading);
        modelExecutor.execute(() -> {
            List<CloudClient.Model> models = null;
            boolean failed = false;
            try {
                models = client.models(config, stage.transcription);
            } catch (IOException | RuntimeException e) {
                failed = true;
            }
            List<CloudClient.Model> result = models;
            boolean requestFailed = failed;
            runOnUiThread(() -> {
                if (destroyed || generation != discoveryGeneration || isFinishing()) return;
                if (activeDiscoveryClient == client) activeDiscoveryClient = null;
                stage.refresh.setEnabled(stage.active);
                stage.refresh.setText(R.string.ai_discover_models);
                if (requestFailed) {
                    toast(R.string.ai_model_discovery_failed);
                } else if (result == null || result.isEmpty()) {
                    toast(R.string.ai_model_discovery_empty);
                } else {
                    showModelPicker(stage, result);
                }
            });
        });
    }

    private void showModelPicker(StageViews stage, List<CloudClient.Model> models) {
        List<String> labels = new ArrayList<>();
        for (CloudClient.Model model : models) {
            String name = model.name == null || model.name.trim().isEmpty() ? model.id : model.name;
            labels.add(name + "\n" + model.id);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ai_model)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < models.size()) stage.model.setText(models.get(which).id);
                })
                .setNegativeButton(R.string.ai_cancel, null)
                .show();
    }

    private void invalidateDiscovery() {
        discoveryGeneration++;
        if (activeDiscoveryClient != null) {
            activeDiscoveryClient.cancel();
            activeDiscoveryClient = null;
        }
        resetDiscoveryButton(transcription);
        resetDiscoveryButton(enhancement);
    }

    private void resetDiscoveryButton(StageViews stage) {
        if (stage == null) return;
        stage.refresh.setText(R.string.ai_discover_models);
        stage.refresh.setEnabled(stage.active);
    }

    private final class StageViews {
        final boolean transcription;
        final Provider[] providers;
        final MaterialCardView card;
        final LinearLayout body;
        final Spinner providerSpinner;
        final TextInputEditText endpoint;
        final TextInputEditText model;
        final TextInputEditText apiKey;
        final TextInputLayout endpointLayout;
        final TextInputLayout modelLayout;
        final TextInputLayout keyLayout;
        final Button refresh;
        final Button removeKey;
        final MaterialSwitch zdr;
        final TextView zdrNote;
        final Button setupLocal;
        Provider provider;
        String originalEndpoint;
        boolean endpointWasEdited;
        boolean active;

        StageViews(boolean transcription, ProviderConfig config) {
            this.transcription = transcription;
            providers = transcription
                    ? new Provider[]{Provider.LOCAL, Provider.GROQ, Provider.OPENAI, Provider.OPENROUTER, Provider.CUSTOM}
                    : new Provider[]{Provider.GROQ, Provider.OPENAI, Provider.OPENROUTER, Provider.CUSTOM};
            provider = config.provider;
            if (!contains(providers, provider)) provider = transcription ? Provider.GROQ : Provider.OPENROUTER;
            active = provider != Provider.LOCAL && (transcription || enhancementEnabled == null || enhancementEnabled.isChecked());

            card = new MaterialCardView(AiSettingsActivity.this);
            card.setUseCompatPadding(false);
            card.setRadius(dp(18));
            card.setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer));
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutline));
            body = cardBody();

            TextView providerLabel = label(R.string.ai_provider);
            body.addView(providerLabel, matchWrap());
            providerSpinner = new Spinner(AiSettingsActivity.this);
            ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(AiSettingsActivity.this,
                    android.R.layout.simple_spinner_item, providerLabels(providers));
            providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            providerSpinner.setAdapter(providerAdapter);
            providerSpinner.setSelection(indexOf(providers, provider));
            body.addView(providerSpinner, matchWrap());

            endpointLayout = new TextInputLayout(AiSettingsActivity.this);
            endpointLayout.setHint(getString(R.string.ai_endpoint));
            endpoint = new TextInputEditText(AiSettingsActivity.this);
            endpoint.setSingleLine(true);
            endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            endpoint.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            endpointLayout.addView(endpoint, matchWrap());
            body.addView(endpointLayout, inputParams());

            modelLayout = new TextInputLayout(AiSettingsActivity.this);
            modelLayout.setHint(getString(R.string.ai_model));
            model = new TextInputEditText(AiSettingsActivity.this);
            model.setSingleLine(true);
            model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            model.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            modelLayout.addView(model, matchWrap());
            body.addView(modelLayout, inputParams());

            keyLayout = new TextInputLayout(AiSettingsActivity.this);
            keyLayout.setHint(getString(R.string.ai_api_key));
            apiKey = new TextInputEditText(AiSettingsActivity.this);
            apiKey.setSingleLine(true);
            apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            apiKey.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            apiKey.setSaveEnabled(false);
            apiKey.setFreezesText(false);
            apiKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            keyLayout.addView(apiKey, matchWrap());
            body.addView(keyLayout, inputParams());

            LinearLayout actions = new LinearLayout(AiSettingsActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            refresh = new Button(AiSettingsActivity.this);
            refresh.setText(R.string.ai_discover_models);
            refresh.setAllCaps(false);
            refresh.setOnClickListener(v -> startModelDiscovery(this));
            actions.addView(refresh, new LinearLayout.LayoutParams(0, ViewGroupWidth.WRAP_CONTENT, 1f));
            removeKey = new Button(AiSettingsActivity.this);
            removeKey.setText(R.string.ai_remove_key);
            removeKey.setAllCaps(false);
            removeKey.setOnClickListener(v -> confirmRemoveKey());
            actions.addView(removeKey, new LinearLayout.LayoutParams(0, ViewGroupWidth.WRAP_CONTENT, 1f));
            body.addView(actions, matchWrap());

            zdr = new MaterialSwitch(AiSettingsActivity.this);
            zdr.setText(R.string.ai_zdr_label);
            zdr.setChecked(config.requireZdr);
            body.addView(zdr, matchWrap());
            zdrNote = smallText(R.string.ai_zdr_explanation);
            body.addView(zdrNote, matchWrap());

            setupLocal = new Button(AiSettingsActivity.this);
            setupLocal.setText(R.string.ai_local_setup);
            setupLocal.setAllCaps(false);
            setupLocal.setOnClickListener(v -> startActivity(new Intent(AiSettingsActivity.this, SetupActivity.class)));
            body.addView(setupLocal, matchWrap());

            endpoint.setText(config.endpoint == null ? "" : config.endpoint);
            model.setText(config.model == null ? "" : config.model);
            originalEndpoint = textOf(endpoint).trim();
            endpoint.addTextChangedListener(new TextWatcher() {
                private String lastValue = originalEndpoint;
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String value = s == null ? "" : s.toString().trim();
                    if (!value.equals(lastValue)) {
                        lastValue = value;
                        endpointWasEdited = !value.equals(originalEndpoint);
                        apiKey.setText("");
                        if (endpointWasEdited) {
                            invalidateDiscovery();
                            keyLayout.setHelperText(getString(R.string.ai_key_no_saved));
                            removeKey.setEnabled(false);
                        } else refreshKeyStatus();
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            card.addView(body);
            providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                private boolean first = true;
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    Provider selected = providers[position];
                    if (first) {
                        first = false;
                        applyProviderVisibility();
                        refreshKeyStatus();
                        return;
                    }
                    if (selected == provider) return;
                    invalidateDiscovery();
                    provider = selected;
                    active = provider != Provider.LOCAL
                            && (transcription || enhancementEnabled == null || enhancementEnabled.isChecked());
                    apiKey.setText("");
                    endpoint.setText(defaultEndpoint(provider, transcription));
                    model.setText(defaultModel(provider, transcription));
                    originalEndpoint = textOf(endpoint).trim();
                    endpointWasEdited = false;
                    applyProviderVisibility();
                    refreshKeyStatus();
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
            applyProviderVisibility();
            refreshKeyStatus();
        }

        ProviderConfig currentConfig() {
            return new ProviderConfig(provider, textOf(endpoint).trim(), textOf(model).trim(),
                    textOf(apiKey), zdr.isChecked());
        }

        ProviderConfig configForDiscovery() {
            if (provider == Provider.LOCAL) {
                toast(R.string.ai_model_discovery_failed);
                return null;
            }
            String endpointValue = textOf(endpoint).trim();
            String modelValue = textOf(model).trim();
            if (!AiSettings.isValidEndpoint(provider, endpointValue, transcription)) {
                endpointLayout.setError(getString(R.string.ai_endpoint_invalid));
                return null;
            }
            endpointLayout.setError(null);
            String keyValue = textOf(apiKey).trim();
            if (keyValue.isEmpty()) {
                try {
                    keyValue = secretStore.getKey(provider, stageName(), endpointValue);
                } catch (IOException e) {
                    toast(R.string.ai_secure_storage_error);
                    return null;
                }
            }
            if (keyValue.trim().isEmpty()) {
                keyLayout.setError(getString(R.string.ai_missing_api_key));
                return null;
            }
            keyLayout.setError(null);
            return new ProviderConfig(provider, endpointValue, modelValue, keyValue, zdr.isChecked());
        }

        void refreshKeyStatus() {
            if (provider == Provider.LOCAL) {
                active = false;
                return;
            }
            active = transcription || enhancementEnabled == null || enhancementEnabled.isChecked();
            String current = textOf(endpoint).trim();
            boolean found = false;
            try {
                found = !endpointWasEdited && secretStore.hasKey(provider, stageName(), current);
            } catch (IOException e) {
                keyLayout.setHelperText(getString(R.string.ai_secure_storage_error));
                removeKey.setEnabled(false);
                return;
            }
            keyLayout.setHelperText(found ? getString(R.string.ai_key_saved_hint) : getString(R.string.ai_key_no_saved));
            removeKey.setEnabled(found);
            if (found) keyLayout.setError(null);
        }

        void clearEnteredKey() {
            apiKey.setText("");
            endpointWasEdited = false;
            originalEndpoint = textOf(endpoint).trim();
        }

        private void applyProviderVisibility() {
            boolean local = provider == Provider.LOCAL;
            endpointLayout.setVisibility(local ? View.GONE : View.VISIBLE);
            modelLayout.setVisibility(local ? View.GONE : View.VISIBLE);
            keyLayout.setVisibility(local ? View.GONE : View.VISIBLE);
            removeKey.setVisibility(local ? View.GONE : View.VISIBLE);
            zdr.setVisibility(!local && provider == Provider.OPENROUTER ? View.VISIBLE : View.GONE);
            zdrNote.setVisibility(!local && provider == Provider.OPENROUTER ? View.VISIBLE : View.GONE);
            setupLocal.setVisibility(local ? View.VISIBLE : View.GONE);
            refresh.setVisibility(local || provider == Provider.CUSTOM ? View.GONE : View.VISIBLE);
            modelLayout.setHelperText(provider == Provider.CUSTOM
                    ? getString(R.string.ai_custom_models_manual) : null);
            refresh.setEnabled(active);
        }

        private void confirmRemoveKey() {
            String endpointValue = textOf(endpoint).trim();
            boolean exists;
            try {
                exists = secretStore.hasKey(provider, stageName(), endpointValue);
            } catch (IOException e) {
                toast(R.string.ai_key_remove_failed);
                return;
            }
            if (!exists) {
                toast(R.string.ai_key_no_saved);
                return;
            }
            new AlertDialog.Builder(AiSettingsActivity.this)
                    .setMessage(R.string.ai_key_removal_confirm)
                    .setNegativeButton(R.string.ai_cancel, null)
                    .setPositiveButton(R.string.ai_confirm, (dialog, which) -> {
                        try {
                            secretStore.removeKey(provider, stageName(), textOf(endpoint).trim());
                            apiKey.setText("");
                            refreshKeyStatus();
                            toast(R.string.ai_key_removed);
                        } catch (IOException e) {
                            toast(R.string.ai_key_remove_failed);
                        }
                    })
                    .show();
        }

        private String stageName() {
            return transcription ? "transcription" : "enhancement";
        }
    }

    private ProviderConfig defaultConfig(Provider provider, boolean transcription) {
        String endpoint = defaultEndpoint(provider, transcription);
        String model = defaultModel(provider, transcription);
        return new ProviderConfig(provider, endpoint, model, "", true);
    }

    private String defaultEndpoint(Provider provider, boolean transcription) {
        String value = transcription ? provider.transcriptionEndpoint() : provider.enhancementEndpoint();
        return value == null ? "" : value;
    }

    private String defaultModel(Provider provider, boolean transcription) {
        String value = transcription ? provider.defaultTranscriptionModel() : provider.defaultEnhancementModel();
        return value == null ? "" : value;
    }

    private String[] providerLabels(Provider[] providers) {
        String[] labels = new String[providers.length];
        for (int i = 0; i < providers.length; i++) labels[i] = providerLabel(providers[i]);
        return labels;
    }

    private String providerLabel(Provider provider) {
        switch (provider) {
            case LOCAL: return getString(R.string.ai_provider_local);
            case GROQ: return getString(R.string.ai_provider_groq);
            case OPENAI: return getString(R.string.ai_provider_openai);
            case OPENROUTER: return getString(R.string.ai_provider_openrouter);
            case CUSTOM:
            default: return getString(R.string.ai_provider_custom);
        }
    }

    private LinearLayout cardBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        return body;
    }

    private View sectionHeading(int titleId, int descriptionId) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(8), dp(4), dp(4));
        TextView title = label(titleId);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(title, matchWrap());
        TextView description = smallText(descriptionId);
        box.addView(description, matchWrap());
        return box;
    }

    private TextView label(int resource) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
        return view;
    }

    private TextView bodyText(int resource) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(14);
        view.setLineSpacing(dp(2), 1f);
        view.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        return view;
    }

    private TextView smallText(int resource) {
        TextView view = new TextView(this);
        if (resource != 0) view.setText(resource);
        view.setTextSize(12);
        view.setLineSpacing(dp(1), 1f);
        view.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        return view;
    }

    private TextInputEditText addTextInput(LinearLayout parent, int hintResource, String text,
                                           int inputType, int imeOptions, boolean multiLine) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(hintResource));
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(inputType | (multiLine ? InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : 0));
        input.setImeOptions(imeOptions);
        input.setText(text == null ? "" : text);
        input.setSaveEnabled(false);
        input.setFreezesText(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        layout.addView(input, matchWrap());
        parent.addView(layout, inputParams());
        return input;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(4);
        params.bottomMargin = dp(14);
        return params;
    }

    private LinearLayout.LayoutParams inputParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroupWidth.MATCH_PARENT, ViewGroupWidth.WRAP_CONTENT);
    }

    private int resolveColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        if (value.resourceId != 0) return ContextCompat.getColor(this, value.resourceId);
        return value.data;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean contains(Provider[] values, Provider target) {
        for (Provider value : values) if (value == target) return true;
        return false;
    }

    private static int indexOf(Provider[] values, Provider target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return 0;
    }

    private static String textOf(EditText view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private void toast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        invalidateDiscovery();
        modelExecutor.shutdownNow();
        if (transcription != null) transcription.clearEnteredKey();
        if (enhancement != null) enhancement.clearEnteredKey();
        super.onDestroy();
    }

    private static final class ViewGroupWidth {
        static final int MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT;
        static final int WRAP_CONTENT = LinearLayout.LayoutParams.WRAP_CONTENT;
    }
}
