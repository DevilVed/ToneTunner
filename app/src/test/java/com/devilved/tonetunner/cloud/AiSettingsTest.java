package com.devilved.tonetunner.cloud;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises configuration and consent without using a real credential or contacting a provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiSettingsTest {
    private AiSettings settings;

    @Before public void reset() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
        settings = new AiSettings(context);
    }

    @Test public void newInstallDefaultsToLocalAndDoesNotAuthorizeCloudUploadsOrEnhancement() {
        assertFalse(settings.isCloudTranscription());
        assertEquals(Provider.LOCAL, settings.getTranscriptionConfigWithoutKey().provider);
        assertEquals("", settings.getTranscriptionConfig().apiKey);
        assertFalse(settings.isEnhancementEnabled());
        assertFalse(settings.isCloudConsentGranted());
    }

    @Test public void explicitlySavedCloudProviderChoiceRemainsCloud() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
                .putString("transcription.provider", Provider.GROQ.name())
                .putBoolean("cloud.consent", true)
                .commit();

        AiSettings reloaded = new AiSettings(context);

        assertTrue(reloaded.isCloudTranscription());
        assertEquals(Provider.GROQ, reloaded.getTranscriptionConfigWithoutKey().provider);
        assertTrue(reloaded.isCloudConsentGranted());
    }

    @Test public void localOnlyCanBeSavedWithoutKeyOrCloudConsent() throws Exception {
        settings.saveSettings(local(), false, enhancement(), "Faithful cleanup", "ToneTunner", false);
        AiSettings reloaded = new AiSettings(RuntimeEnvironment.getApplication());
        assertFalse(reloaded.isCloudTranscription());
        assertFalse(reloaded.isEnhancementEnabled());
        assertFalse(reloaded.isCloudConsentGranted());
        assertEquals("Faithful cleanup", reloaded.getEnhancementPrompt());
        assertEquals("ToneTunner", reloaded.getVocabulary());
        assertEquals("", reloaded.getTranscriptionConfig().apiKey);
    }

    @Test public void cloudCannotBeEnabledWithoutConsent() {
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                cloud(), false, enhancement(), "", "", false));
        assertFalse(settings.isCloudConsentGranted());
    }

    @Test public void cloudConsentDoesNotBypassMissingCredential() {
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                cloud(), false, enhancement(), "", "", true));
        assertFalse(settings.isCloudConsentGranted());
    }

    @Test public void localTranscriptionWithCloudEnhancementStillRequiresConsent() {
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                local(), true, enhancement(), "", "", false));
    }

    @Test public void localProviderCannotActAsAnEnhancementModel() {
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                local(), true, local(), "", "", true));
    }

    @Test public void cleartextCustomDestinationIsRejectedBeforeCredentialStorage() {
        ProviderConfig unsafe = new ProviderConfig(Provider.CUSTOM,
                "http://example.invalid/audio/transcriptions", "fixture-model", "fixture-key", false);
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                unsafe, false, enhancement(), "", "", true));
        assertFalse(settings.isCloudConsentGranted());
    }

    @Test public void corruptedProviderPreferenceFallsBackWithoutEnablingEnhancement() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().putString("transcription.provider", "UNKNOWN")
                .putString("enhancement.provider", "LOCAL").putBoolean("enhancement.enabled", true).commit();
        assertEquals(Provider.LOCAL, settings.getTranscriptionConfigWithoutKey().provider);
        assertFalse(settings.isEnhancementEnabled());
    }

    @Test public void customTlsRouteAndPortCanBeSavedByTheSameRulesAsTransport() {
        assertTrue(AiSettings.isValidEndpoint(Provider.CUSTOM,
                "https://example.invalid:8443/compat/v9/custom-chat", false));
        assertFalse(AiSettings.isValidEndpoint(Provider.CUSTOM,
                "https://example.invalid:8443/compat?token=secret", false));
        assertFalse(AiSettings.isValidEndpoint(Provider.OPENAI,
                "https://api.openai.com:8443/v1/chat/completions", false));
    }

    private static ProviderConfig local() {
        return new ProviderConfig(Provider.LOCAL, "", "", "", false);
    }
    private static ProviderConfig cloud() {
        return new ProviderConfig(Provider.GROQ, Provider.GROQ.transcriptionEndpoint(),
                Provider.GROQ.defaultTranscriptionModel(), "", false);
    }
    private static ProviderConfig enhancement() {
        return new ProviderConfig(Provider.OPENROUTER, Provider.OPENROUTER.enhancementEndpoint(),
                Provider.OPENROUTER.defaultEnhancementModel(), "", true);
    }
}
