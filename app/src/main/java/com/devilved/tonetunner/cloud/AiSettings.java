package com.devilved.tonetunner.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.devilved.tonetunner.R;

import java.io.IOException;

/** Persisted AI provider choices. API keys are delegated to {@link SecretStore}, never preferences. */
public final class AiSettings {
    private static final String PREFERENCES = "ai_settings";

    private static final String TRANS_PROVIDER = "transcription.provider";
    private static final String TRANS_ENDPOINT = "transcription.endpoint";
    private static final String TRANS_MODEL = "transcription.model";
    private static final String TRANS_ZDR = "transcription.require_zdr";

    private static final String ENH_ENABLED = "enhancement.enabled";
    private static final String ENH_PROVIDER = "enhancement.provider";
    private static final String ENH_ENDPOINT = "enhancement.endpoint";
    private static final String ENH_MODEL = "enhancement.model";
    private static final String ENH_ZDR = "enhancement.require_zdr";
    private static final String ENH_PROMPT = "enhancement.prompt";
    private static final String ENH_VOCABULARY = "enhancement.vocabulary";
    private static final String CLOUD_CONSENT = "cloud.consent";

    private final Context appContext;
    private final SharedPreferences preferences;
    private final SecretStore secretStore;

    public AiSettings(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        secretStore = new SecretStore(appContext);
    }

    public ProviderConfig getTranscriptionConfig() {
        Provider provider = readProvider(TRANS_PROVIDER, Provider.GROQ);
        String endpoint = readEndpoint(TRANS_ENDPOINT, provider, true);
        String model = readModel(TRANS_MODEL, provider, true);
        String apiKey = readKey(provider, "transcription", endpoint);
        boolean requireZdr = preferences.getBoolean(TRANS_ZDR, true);
        return new ProviderConfig(provider, endpoint, model, apiKey, requireZdr);
    }

    public ProviderConfig getEnhancementConfig() {
        Provider provider = readProvider(ENH_PROVIDER, Provider.OPENROUTER);
        String endpoint = readEndpoint(ENH_ENDPOINT, provider, false);
        String model = readModel(ENH_MODEL, provider, false);
        String apiKey = readKey(provider, "enhancement", endpoint);
        boolean requireZdr = preferences.getBoolean(ENH_ZDR, true);
        return new ProviderConfig(provider, endpoint, model, apiKey, requireZdr);
    }

    public boolean isCloudTranscription() {
        return readProvider(TRANS_PROVIDER, Provider.GROQ) != Provider.LOCAL;
    }

    public boolean isEnhancementEnabled() {
        return preferences.getBoolean(ENH_ENABLED, false)
                && readProvider(ENH_PROVIDER, Provider.OPENROUTER) != Provider.LOCAL;
    }

    public String getEnhancementPrompt() {
        return preferences.getString(ENH_PROMPT, appContext.getString(R.string.ai_prompt_default));
    }

    public String getVocabulary() {
        return preferences.getString(ENH_VOCABULARY, "");
    }

    /** A short, key-free summary suitable for the app's main settings screen. */
    public String getSummary() {
        try {
            ProviderConfig transcription = getTranscriptionConfig();
            String transcriptionSummary;
            if (transcription.provider == Provider.LOCAL) {
                transcriptionSummary = appContext.getString(R.string.ai_local_transcription_summary);
            } else {
                transcriptionSummary = appContext.getString(R.string.ai_default_transcription_summary,
                        providerLabel(transcription.provider), transcription.model);
                if (transcription.apiKey == null || transcription.apiKey.trim().isEmpty()) {
                    transcriptionSummary += " · " + appContext.getString(R.string.ai_key_required_summary);
                }
            }

            String enhancementSummary;
            if (isEnhancementEnabled()) {
                ProviderConfig enhancement = getEnhancementConfig();
                enhancementSummary = appContext.getString(R.string.ai_enhancement_summary,
                        providerLabel(enhancement.provider), enhancement.model);
                if (enhancement.apiKey == null || enhancement.apiKey.trim().isEmpty()) {
                    enhancementSummary += " · " + appContext.getString(R.string.ai_key_required_summary);
                }
            } else {
                enhancementSummary = appContext.getString(R.string.ai_enhancement_off_summary);
            }
            return transcriptionSummary + "\n" + enhancementSummary;
        } catch (RuntimeException e) {
            return appContext.getString(R.string.ai_key_unavailable_summary);
        }
    }

    public boolean isCloudConsentGranted() {
        return preferences.getBoolean(CLOUD_CONSENT, false);
    }

    /**
     * Saves settings after checking the active cloud stages. A blank key preserves the key already
     * stored for the same provider/stage/full-endpoint identity.
     */
    public synchronized void saveSettings(ProviderConfig transcription,
                                          boolean enhancementEnabled,
                                          ProviderConfig enhancement,
                                          String prompt,
                                          String vocabulary,
                                          boolean cloudConsent) throws IOException {
        if (transcription == null || enhancement == null || transcription.provider == null
                || enhancement.provider == null) {
            throw new IllegalArgumentException("Choose a provider for each stage.");
        }
        if (enhancementEnabled && enhancement.provider == Provider.LOCAL) {
            throw new IllegalArgumentException("Choose a cloud provider for enhancement.");
        }

        boolean cloudEnabled = transcription.provider != Provider.LOCAL
                || (enhancementEnabled && enhancement.provider != Provider.LOCAL);
        if (cloudEnabled && !cloudConsent) {
            throw new IllegalArgumentException(appContext.getString(R.string.ai_cloud_consent_required));
        }

        validateStage(transcription, "transcription", transcription.provider != Provider.LOCAL);
        if (enhancementEnabled) validateStage(enhancement, "enhancement", true);

        persistEnteredKey(transcription, "transcription");
        persistEnteredKey(enhancement, "enhancement");

        SharedPreferences.Editor editor = preferences.edit()
                .putString(TRANS_PROVIDER, transcription.provider.name())
                .putString(TRANS_ENDPOINT, safeString(transcription.endpoint))
                .putString(TRANS_MODEL, safeString(transcription.model))
                .putBoolean(TRANS_ZDR, transcription.requireZdr)
                .putBoolean(ENH_ENABLED, enhancementEnabled)
                .putString(ENH_PROVIDER, enhancement.provider.name())
                .putString(ENH_ENDPOINT, safeString(enhancement.endpoint))
                .putString(ENH_MODEL, safeString(enhancement.model))
                .putBoolean(ENH_ZDR, enhancement.requireZdr)
                .putString(ENH_PROMPT, safeString(prompt))
                .putString(ENH_VOCABULARY, safeString(vocabulary))
                .putBoolean(CLOUD_CONSENT, cloudConsent);
        if (!editor.commit()) {
            throw new IOException(appContext.getString(R.string.ai_settings_save_error));
        }
    }

    private void validateStage(ProviderConfig config, String stage, boolean active) throws IOException {
        if (!active || config.provider == Provider.LOCAL) return;
        if (!isValidEndpoint(config.provider, config.endpoint, "transcription".equals(stage))) {
            throw new IllegalArgumentException(appContext.getString(R.string.ai_endpoint_invalid));
        }
        if (safeString(config.model).trim().isEmpty()) {
            throw new IllegalArgumentException(appContext.getString(R.string.ai_model_required));
        }
        String entered = safeString(config.apiKey).trim();
        if (entered.isEmpty()) {
            String stored = secretStore.getKey(config.provider, stage, safeString(config.endpoint));
            if (stored.trim().isEmpty()) {
                throw new IllegalArgumentException(appContext.getString(R.string.ai_missing_api_key));
            }
        }
    }

    private void persistEnteredKey(ProviderConfig config, String stage) throws IOException {
        String entered = safeString(config.apiKey).trim();
        if (!entered.isEmpty() && config.provider != Provider.LOCAL) {
            secretStore.putKey(config.provider, stage, safeString(config.endpoint), entered);
        }
    }

    private String readKey(Provider provider, String stage, String endpoint) {
        if (provider == Provider.LOCAL) return "";
        try {
            return secretStore.getKey(provider, stage, endpoint);
        } catch (IOException e) {
            throw new IllegalStateException(appContext.getString(R.string.ai_secure_storage_error));
        }
    }

    private Provider readProvider(String key, Provider fallback) {
        String value = preferences.getString(key, fallback.name());
        try {
            return Provider.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }

    private String readEndpoint(String key, Provider provider, boolean transcription) {
        String defaultEndpoint = endpointFor(provider, transcription);
        return preferences.getString(key, defaultEndpoint == null ? "" : defaultEndpoint);
    }

    private static String endpointFor(Provider provider, boolean transcription) {
        if (provider == null) return "";
        String endpoint = transcription ? provider.transcriptionEndpoint() : provider.enhancementEndpoint();
        return endpoint == null ? "" : endpoint;
    }

    private String readModel(String key, Provider provider, boolean transcription) {
        if (provider == null) return "";
        String model = transcription ? provider.defaultTranscriptionModel() : provider.defaultEnhancementModel();
        return preferences.getString(key, model == null ? "" : model);
    }

    private String providerLabel(Provider provider) {
        int resource;
        switch (provider) {
            case LOCAL:
                resource = R.string.ai_provider_local;
                break;
            case GROQ:
                resource = R.string.ai_provider_groq;
                break;
            case OPENAI:
                resource = R.string.ai_provider_openai;
                break;
            case OPENROUTER:
                resource = R.string.ai_provider_openrouter;
                break;
            case CUSTOM:
            default:
                resource = R.string.ai_provider_custom;
                break;
        }
        return appContext.getString(resource);
    }

    public static boolean isValidEndpoint(Provider provider, String endpoint, boolean transcription) {
        if (provider == null || provider == Provider.LOCAL || endpoint == null || endpoint.trim().isEmpty()) {
            return false;
        }
        Uri uri = Uri.parse(endpoint.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (provider != Provider.CUSTOM && uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getPort() > 65535 || uri.getPort() == 0) return false;

        String expectedHost = canonicalHost(provider);
        if (expectedHost != null && !expectedHost.equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getEncodedPath();
        if (provider == Provider.CUSTOM) return path != null && path.length() > 1;
        String requiredSuffix = transcription ? "/audio/transcriptions" : "/chat/completions";
        return path != null && path.endsWith(requiredSuffix);
    }

    private static String canonicalHost(Provider provider) {
        switch (provider) {
            case GROQ: return "api.groq.com";
            case OPENAI: return "api.openai.com";
            case OPENROUTER: return "openrouter.ai";
            case CUSTOM:
            case LOCAL:
            default: return null;
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }
}
