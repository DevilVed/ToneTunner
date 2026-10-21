package com.devilved.tonetunner.cloud;

import android.content.Context;
import android.content.ContextWrapper;
import com.devilved.tonetunner.R;
import org.junit.Rule;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

/** Exercises configuration and consent without using a real credential or contacting a provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiSettingsTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private AiSettings settings;
    private SecretStore testSecretStore;

    @Before public void reset() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
        settings = new AiSettings(context);

        Context storageContext = new TestStorageContext(context, temporaryFolder.newFolder("no-backup"));
        testSecretStore = new SecretStore(storageContext,
                () -> new SecretKeySpec(new byte[32], "AES"));
        Field secretStore = AiSettings.class.getDeclaredField("secretStore");
        secretStore.setAccessible(true);
        secretStore.set(settings, testSecretStore);
    }

    @Test public void newInstallDefaultsToLocalAndDoesNotAuthorizeCloudUploadsOrEnhancement() {
        assertFalse(settings.isCloudTranscription());
        assertEquals(Provider.LOCAL, settings.getTranscriptionConfigWithoutKey().provider);
        assertEquals("", settings.getTranscriptionConfig().apiKey);
        assertFalse(settings.isEnhancementEnabled());
        assertFalse(settings.isCloudConsentGranted());
    }

    @Test public void cleanupOptionsDefaultToBuiltInRulesAndNoReplacements() {
        assertTrue(settings.useBuiltInCleanupRules());
        assertEquals("", settings.getReplacements());
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

    @Test public void expandedSaveSettingsPersistsCleanupModeAndReplacements() throws Exception {
        settings.saveSettings(local(), false, enhancement(), "Raw system instructions", "ToneTunner",
                false, false, "Tunner => Toner");

        AiSettings reloaded = new AiSettings(RuntimeEnvironment.getApplication());

        assertFalse(reloaded.useBuiltInCleanupRules());
        assertEquals("Tunner => Toner", reloaded.getReplacements());
    }

    @Test public void legacySaveSettingsOverloadRetainsBuiltInModeAndEmptyReplacements() throws Exception {
        settings.saveSettings(local(), false, enhancement(), "Faithful cleanup", "ToneTunner", false);

        AiSettings reloaded = new AiSettings(RuntimeEnvironment.getApplication());

        assertTrue(reloaded.useBuiltInCleanupRules());
        assertEquals("", reloaded.getReplacements());
    }

    @Test public void settingsRejectPromptVocabularyAndReplacementTextOver16000Characters() {
        String tooLong = new String(new char[16001]).replace('\0', 'x');

        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                local(), false, enhancement(), tooLong, "", false, true, ""));
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                local(), false, enhancement(), "", tooLong, false, true, ""));
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                local(), false, enhancement(), "", "", false, true, tooLong));
    }

    @Test public void advancedSystemModeRequiresPromptWhenEnhancementIsEnabled() {
        assertThrows(IllegalArgumentException.class, () -> settings.saveSettings(
                local(), true, enhancement(), " \n ", "", true, false, ""));
    }

    @Test public void legacyOpenRouterTranscriptionDefaultsToRequiredZdrAndFailsCredentialPreflight() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().putString("transcription.provider", Provider.OPENROUTER.name())
                .putBoolean("cloud.consent", true).commit();

        assertTrue(settings.getTranscriptionConfigWithoutKey().requireZdr);

        assertEquals(R.string.ai_openrouter_transcription_zdr_unsupported,
                settings.validateCloudCredentials());
    }

    @Test public void saveRejectsOpenRouterTranscriptionBeforeStoringKeyOrPreferences() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String endpoint = Provider.OPENROUTER.transcriptionEndpoint();
        ProviderConfig openRouter = new ProviderConfig(Provider.OPENROUTER,
                endpoint, Provider.OPENROUTER.defaultTranscriptionModel(), "synthetic-test-key", true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> settings.saveSettings(openRouter, false, enhancement(), "", "", true));

        assertEquals(context.getString(
                R.string.ai_openrouter_transcription_zdr_unsupported),
                error.getMessage());
        assertEquals("An invalid choice must not replace the previously saved provider.",
                Provider.LOCAL, settings.getTranscriptionConfigWithoutKey().provider);
        assertFalse(settings.isCloudConsentGranted());
        assertFalse(testSecretStore.hasKey(Provider.OPENROUTER, "transcription", endpoint));
    }

    @Test public void saveReportsUnsupportedZdrBeforeMissingApiKey() {
        Context context = RuntimeEnvironment.getApplication();
        ProviderConfig openRouterWithoutKey = new ProviderConfig(Provider.OPENROUTER,
                Provider.OPENROUTER.transcriptionEndpoint(),
                Provider.OPENROUTER.defaultTranscriptionModel(), "", true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> settings.saveSettings(openRouterWithoutKey, false, enhancement(), "", "", true));

        assertEquals(context.getString(R.string.ai_openrouter_transcription_zdr_unsupported),
                error.getMessage());
        assertEquals(Provider.LOCAL, settings.getTranscriptionConfigWithoutKey().provider);
        assertFalse(settings.isCloudConsentGranted());
    }

    @Test public void explicitOpenRouterTranscriptionZdrOptOutSavesAndPreservesEnhancementChoice()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String endpoint = Provider.OPENROUTER.transcriptionEndpoint();
        ProviderConfig openRouter = new ProviderConfig(Provider.OPENROUTER, endpoint,
                Provider.OPENROUTER.defaultTranscriptionModel(), "synthetic-test-key", false);

        settings.saveSettings(openRouter, false, enhancement(), "", "", true);

        AiSettings reloaded = new AiSettings(context);
        assertEquals(Provider.OPENROUTER, reloaded.getTranscriptionConfigWithoutKey().provider);
        assertFalse(reloaded.getTranscriptionConfigWithoutKey().requireZdr);
        assertTrue(reloaded.getEnhancementConfigWithoutKey().requireZdr);
        assertTrue(reloaded.isCloudConsentGranted());
        assertTrue(testSecretStore.hasKey(Provider.OPENROUTER, "transcription", endpoint));
    }

    @Test public void openRouterTranscriptionCanOptOutOfUnsupportedZdrRequirement() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().putString("transcription.provider", Provider.OPENROUTER.name())
                .putBoolean("transcription.require_zdr", false).putBoolean("cloud.consent", true).commit();

        assertNotEquals(R.string.ai_openrouter_transcription_zdr_unsupported,
                settings.validateCloudCredentials());
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

    private static final class TestStorageContext extends ContextWrapper {
        private final File noBackupDirectory;

        TestStorageContext(Context base, File noBackupDirectory) {
            super(base);
            this.noBackupDirectory = noBackupDirectory;
        }

        @Override public Context getApplicationContext() {
            return this;
        }

        @Override public File getNoBackupFilesDir() {
            return noBackupDirectory;
        }
    }
}
