# Exact verification test inventory — 20 October 2026

> **Current integrated verification (21 October 2026):** [Actions run 39533439912](https://github.com/DevilVed/ToneTunner/actions/runs/39533439912) on application commit `3a04b5f` passed every job: the native 16 KB extensions gate, **257 JVM/Robolectric tests in 32 suites with 0 failures, 0 errors and 0 skipped**, Android lint with 0 errors and 20 warnings, the API 28, API 34 and API 35 (16 KB page) emulator jobs, and verified APK packaging. Counts were exported from that run's downloaded JUnit XML. The per-suite table is below; the rest of this document is the historical `bd0e80a` inventory and is kept for provenance only.

### Current JVM / Robolectric suites at `3a04b5f`

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `com.devilved.tonetunner.AiSettingsCredentialThreadTest` | 3 | 0 | 0 | 0 |
| `com.devilved.tonetunner.AndroidManifestPermissionsTest` | 1 | 0 | 0 | 0 |
| `com.devilved.tonetunner.CloudOnboardingTest` | 5 | 0 | 0 | 0 |
| `com.devilved.tonetunner.EntryPointHoldReleaseValidationTest` | 3 | 0 | 0 | 0 |
| `com.devilved.tonetunner.EntryPointTranslationPreflightTest` | 13 | 0 | 0 | 0 |
| `com.devilved.tonetunner.RecordingScreenAwakeTest` | 10 | 0 | 0 | 0 |
| `com.devilved.tonetunner.RecoveryUiTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperInputMethodServiceLifecycleTest` | 15 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperInputMethodServiceScreenAwakeTest` | 6 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperRecognitionServiceContractTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperRecognitionServiceDeliveryTest` | 5 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperRecognizeActivityRequestContractTest` | 3 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.InputPolicyTest` | 5 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.LocalModelGateTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.OperationGateTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.PcmRecordingTest` | 17 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecordBufferTest` | 5 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecorderFailureTest` | 6 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecorderOwnershipTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecorderVadTest` | 1 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecordingGestureTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecoveryStoreTest` | 15 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.TextWindowsTest` | 5 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.TranscriptJoinerTest` | 27 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.WhisperCloudWindowTest` | 1 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.WhisperFailureRecoveryTest` | 23 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.WhisperOwnershipTest` | 8 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.AiSettingsTest` | 9 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.AudioWavTest` | 3 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.CloudClientTest` | 42 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.ProviderTest` | 1 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.SecretStoreTest` | 9 | 0 | 0 | 0 |

`ZipSlipSecurityTest.java` remains a standalone `main()` harness and is not counted.

### Historical inventory

The counts below cover only `bd0e80a`. The recovery checkpoint later passed 193 tests at `11eda74`, and the folder-review tests-first run reported 226 tests/26 expected failures at `01bd79c`.

This inventory documents the historical successful [Actions run 39444393039](https://github.com/DevilVed/ToneTunner/actions/runs/39444393039), application/build commit `bd0e80a393040ff19fb4fd3373830fbb4c94d1b2`. It was exported from downloaded report XML and statically read native test definitions. Generating this document does not execute or rerun any tests. Later documentation-only commits do not change that provenance.

See the [full engineering record](2026-10-20-voiceink-android-engineering-record.md) for decisions, fixtures, limitations, and interpretation of these results. Test method names describe intended checks; a passing fixture is not a live-provider or physical-device quality result.

## JVM / Robolectric suites

Reported totals: **121 tests in 21 suites; 0 failures, 0 errors, 0 skipped**.

The existing `ZipSlipSecurityTest.java` is a standalone `main()` harness, not an annotated JUnit suite. It does not appear in these final XML reports and is not counted as executed by this verification run.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `com.devilved.tonetunner.CloudOnboardingTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.RecordingScreenAwakeTest` | 10 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperInputMethodServiceLifecycleTest` | 6 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperInputMethodServiceScreenAwakeTest` | 6 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperRecognitionServiceContractTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.WhisperRecognizeActivityRequestContractTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.InputPolicyTest` | 5 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.LocalModelGateTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.OperationGateTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.PcmRecordingTest` | 10 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecordBufferTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecorderOwnershipTest` | 2 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.RecordingGestureTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.TextWindowsTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.TranscriptJoinerTest` | 4 | 0 | 0 | 0 |
| `com.devilved.tonetunner.asr.WhisperOwnershipTest` | 8 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.AiSettingsTest` | 9 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.AudioWavTest` | 3 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.CloudClientTest` | 25 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.ProviderTest` | 1 | 0 | 0 | 0 |
| `com.devilved.tonetunner.cloud.SecretStoreTest` | 8 | 0 | 0 | 0 |

### com.devilved.tonetunner.CloudOnboardingTest

- `mainScreenOpensWithoutStartingLocalModelSetup` — passed
- `settingsProtectsBothCredentialInputsFromScreenshotsAndStateSaving` — passed

### com.devilved.tonetunner.RecordingScreenAwakeTest

- `mainActivityReleasesScreenWhenPausedDuringProcessing` — passed
- `recognizeActivityKeepsScreenOnThroughProcessingAndReleasesAfterSuccess` — passed
- `mainActivityReleasesScreenOnProcessingError` — passed
- `recognizeActivityReleasesScreenOnRecorderError` — passed
- `recognizeActivityReleasesScreenWhenProcessingIsCancelled` — passed
- `mainActivityReleasesScreenOnRecorderError` — passed
- `recognizeActivityReleasesScreenWhenPausedDuringProcessing` — passed
- `recognizeActivityReleasesScreenOnProcessingError` — passed
- `mainActivityReleasesScreenWhenProcessingIsCancelled` — passed
- `mainActivityKeepsScreenOnThroughProcessingAndReleasesAfterSuccess` — passed

### com.devilved.tonetunner.WhisperInputMethodServiceLifecycleTest

- `noPersonalizedLearningEditorNeverReceivesCloudResult` — passed
- `resultFromFinishedEditorCannotCommitToEitherOldOrReplacementConnection` — passed
- `resultForCurrentEditorCommitsToItsInputConnection` — passed
- `cancelButtonRejectsAResultAlreadyHoldingTheListener` — passed
- `selectionChangeCancellationRejectsAResultAlreadyHoldingTheListener` — passed
- `passwordEditorNeverReceivesAResult` — passed

### com.devilved.tonetunner.WhisperInputMethodServiceScreenAwakeTest

- `finishingInputViewReleasesInputViewAwakePolicyDuringProcessing` — passed
- `cancelButtonReleasesInputViewAwakePolicyDuringProcessing` — passed
- `recorderErrorReleasesInputViewAwakePolicy` — passed
- `manualRecordingKeepsInputViewAwakeThroughProcessingAndEnhancement` — passed
- `successfulTranscriptionReleasesInputViewAwakePolicy` — passed
- `transcriptionErrorReleasesInputViewAwakePolicy` — passed

### com.devilved.tonetunner.WhisperRecognitionServiceContractTest

- `serviceSilenceStopDefaultsOnAndCanBeDisabled` — passed
- `staleCallbackCannotControlReplacementRequest` — passed

### com.devilved.tonetunner.WhisperRecognizeActivityRequestContractTest

- `speechActivityUsesStandardLaunchModeToPreserveEachCallerResultRoute` — passed
- `separateRecognitionRequestsKeepSanitizedLanguagesAndResultsIndependent` — passed

### com.devilved.tonetunner.asr.InputPolicyTest

- `normalTextCanUseCloud` — passed
- `emailAndMultilineAreNotMistakenForPasswords` — passed
- `noPersonalizedLearningAllowsOnlyEntirelyLocalProcessing` — passed
- `allPasswordVariationsRejectLocalAndCloud` — passed
- `nullEditorIsNotAnInputDestination` — passed

### com.devilved.tonetunner.asr.LocalModelGateTest

- `replacementWaitsUntilNativeCleanupReturnsItsLease` — passed
- `duplicateCleanupDoesNotAllowTwoModelsInMemory` — passed

### com.devilved.tonetunner.asr.OperationGateTest

- `cancelledResultCannotCompleteNewOperation` — passed
- `cancellationInvalidatesAlreadyQueuedDelivery` — passed
- `terminalCallbackCanOnlyBeDeliveredOnce` — passed
- `secondStartIsRejectedUntilCompletion` — passed

### com.devilved.tonetunner.asr.PcmRecordingTest

- `allSilenceStillProducesBoundedWindowsAndKeepsEveryByte` — passed
- `absenceOfQuietRunFallsBackToMaximumWindow` — passed
- `longRecordingIsReadAsBoundedWindowsWithEveryBytePreserved` — passed
- `createPreservesEveryLiveRecordingInThisProcess` — passed
- `readFailureClosesHandlesAndRemovesTemporaryAudio` — passed
- `createRemovesOnlyStaleFilesWithThePcmSpoolName` — passed
- `closeDeletesBackingFileAndIsIdempotent` — passed
- `choosesMidpointOfLatestQualifyingQuietRunAndPreservesAudio` — passed
- `handlesTinyInputAndRejectsPartialPcm16Writes` — passed
- `readingRequiresFinishAndFinishedRecordingCannotBeWritten` — passed

### com.devilved.tonetunner.asr.RecordBufferTest

- `silenceDoesNotDivideByZero` — passed
- `audioCanBeClaimedByOnlyOneOperation` — passed
- `absentAndOddTailAreSafe` — passed
- `littleEndianSamplesPreserveSignsAndNormalize` — passed

### com.devilved.tonetunner.asr.RecorderOwnershipTest

- `recorderOwnersCanOnlyClaimTheirOwnCompletedFiles` — passed
- `releaseDeletesOnlyItsUnclaimedFileAndPreservesTransferredFile` — passed

### com.devilved.tonetunner.asr.RecordingGestureTest

- `shortTapStartsAndStaysRecording` — passed
- `cancelledTouchCannotStopALaterRecording` — passed
- `nextTapStopsWithoutRestartOnRelease` — passed
- `holdingForMoreThanThirtySecondsStillOnlyStopsOnRelease` — passed

### com.devilved.tonetunner.asr.TextWindowsTest

- `splitDoesNotCutASurrogatePair` — passed
- `noWhitespaceScriptsStillMakeBoundedProgress` — passed
- `longTextKeepsEveryCharacterInOrder` — passed
- `shortDictationIsNotSplit` — passed

### com.devilved.tonetunner.asr.TranscriptJoinerTest

- `latinWordsNeedASeparator` — passed
- `repeatedWordsArePreserved` — passed
- `cjkWindowsDoNotInventSpaces` — passed
- `punctuationAttachesToPreviousWord` — passed

### com.devilved.tonetunner.asr.WhisperOwnershipTest

- `operationReusesOneLocalBackendForAllSpoolWindowsThenReleasesIt` — passed
- `settingsFailureClosesOwnedRecordingAndReportsRecoverableError` — passed
- `busyStartClosesOnlyTheRejectedRequestInput` — passed
- `stopClosesLocalInputAndInvalidatesQueuedCallbacks` — passed
- `failedLocalBackendClosesAndLaterOperationGetsFreshBackend` — passed
- `destroyedFacadeClosesSuppliedRecordingWithoutCallbacks` — passed
- `twoFacadesSerializeLocalBackendsAndSecondCompletesAfterFirstReleasesLease` — passed
- `secondFacadeWaitsForDelayedNativeCleanupAfterFirstIsCancelled` — passed

### com.devilved.tonetunner.cloud.AiSettingsTest

- `newInstallDoesNotAuthorizeCloudUploadsOrEnhancement` — passed
- `corruptedProviderPreferenceFallsBackWithoutEnablingEnhancement` — passed
- `localProviderCannotActAsAnEnhancementModel` — passed
- `customTlsRouteAndPortCanBeSavedByTheSameRulesAsTransport` — passed
- `localOnlyCanBeSavedWithoutKeyOrCloudConsent` — passed
- `cloudCannotBeEnabledWithoutConsent` — passed
- `cleartextCustomDestinationIsRejectedBeforeCredentialStorage` — passed
- `cloudConsentDoesNotBypassMissingCredential` — passed
- `localTranscriptionWithCloudEnhancementStillRequiresConsent` — passed

### com.devilved.tonetunner.cloud.AudioWavTest

- `rejectsAudioLongerThanOneThirtySecondRequestChunk` — passed
- `wrapsPcm16MonoInAValidWaveHeader` — passed
- `rejectsNullOrIncompletePcmSamples` — passed

### com.devilved.tonetunner.cloud.CloudClientTest

- `cancellationAbortsAnInFlightCall` — passed
- `enhancementKeepsInvariantInstructionsAndOpenRouterPrivacyRouting` — passed
- `transcriptionUsesMultipartAndReturnsTranscript` — passed
- `acceptsAnArrayOfEnhancementTextBlocks` — passed
- `reportsAnEmptyTranscriptWithTypedNoSpeechError` — passed
- `directProvidersUseMaxCompletionTokensWithoutTemperature` — passed
- `rejectsEnhancementContentOverTheOutputLimit` — passed
- `rejectsAResponseThatExceedsTheReadLimit` — passed
- `directModelCatalogsKeepTranscriptionAndChatModelsSeparate` — passed
- `rejectsUnsupportedEnhancementContentBlockAfterText` — passed
- `reportsEnhancementRefusalsWithoutReturningProviderText` — passed
- `capsEnhancementResponseBeforeParsing` — passed
- `providerErrorBodyAndSecretNeverAppearInSafeError` — passed
- `rejectsNonObjectEnhancementContentBlockAfterText` — passed
- `reportsMalformedEnhancementResponsesWithoutReturningBody` — passed
- `doesNotFollowProviderRedirects` — passed
- `customProviderKeepsManualModelAndDoesNotRequireModelDiscovery` — passed
- `rejectsInsecureOrCredentialBearingCustomEndpoints` — passed
- `rejectsAHostMismatchBeforeSendingTheApiKey` — passed
- `detectsTruncatedEnhancementAndDoesNotReturnPartialText` — passed
- `openRouterTranscriptionUsesJsonAndRequestsBothPrivacyFilters` — passed
- `reportsUnsupportedTranslationBeforeUploadingAudio` — passed
- `customProviderAllowsACompatibleHttpsPathAndTlsPort` — passed
- `missingTranscriptTextRemainsAMalformedResponseError` — passed
- `openRouterModelDiscoverySeparatesTranscriptionFromTextChat` — passed

### com.devilved.tonetunner.cloud.ProviderTest

- `presetsExposeFullApiEndpointsAndUsableDefaults` — passed

### com.devilved.tonetunner.cloud.SecretStoreTest

- `movingCiphertextToAnotherIdentityFailsGcmAuthentication` — passed
- `providerStageAndEndpointEachHaveIndependentCredentialIdentity` — passed
- `removingOneDestinationLeavesOtherCredentialsIntact` — passed
- `unavailableKeySourceFailsSafelyOnSaveAndLoad` — passed
- `noPlaintextCredentialAppearsInPreferencesOrStoredFiles` — passed
- `concurrentWritesKeepCredentialsBoundToTheirOwnIdentities` — passed
- `corruptRecordReturnsOnlyTheSafeStorageError` — passed
- `persistsAesGcmCiphertextAndDecryptsItAfterReopeningStore` — passed

## Native artifact-validation fixtures

The composite native setup ran the following six Python unittest fixtures in GitHub Actions. The final Actions log records six tests passing. The names below are read from the checked-in definitions in [`tools/native/test_check_alignment.py`](../tools/native/test_check_alignment.py); this documentation export did not import or execute the module.

- `test_accepts_16kb_and_larger_power_of_two_alignment`
- `test_rejects_a_later_unaligned_load_segment`
- `test_rejects_incongruent_load_offset`
- `test_rejects_truncated_and_empty_program_headers`
- `test_checks_every_64bit_library_in_an_archive`
- `test_rejects_misaligned_apk_zip_payload_even_when_elf_is_aligned`

## Connected Android tests

The same six methods ran in each matrix environment. API 28 uses 4 KB pages; API 35 uses the 16 KB image, and the page-size test asserts the runtime value. Durations are fixture times reported by the Android runner, not Whisper inference or provider-latency benchmarks. The Android reports are downloadable from the final Actions run.

### API 28

Reported: 6 tests, 0 failures, 0 errors, 0 skipped.

| Method | Result | Reported seconds |
|---|---|---:|
| `productionKeystorePersistsAndSeparatesCloudCredentials` | passed | 0.196 |
| `speechIntentReturnsItsTranscriptToTheActualCallingActivity` | passed | 2.774 |
| `nativeOnnxInferenceAndExtensionsLoadOnThisAndroidRuntime` | passed | 0.626 |
| `manualMicrophoneRecordingSurvivesThirtySecondsUntilExplicitStop` | passed | 33.585 |
| `runtimeUsesTheExpectedPageSize` | passed | 0.128 |
| `nativeVadAcceptsARealPcmFrame` | passed | 0.148 |

### API 35

Reported: 6 tests, 0 failures, 0 errors, 0 skipped.

| Method | Result | Reported seconds |
|---|---|---:|
| `productionKeystorePersistsAndSeparatesCloudCredentials` | passed | 0.12 |
| `speechIntentReturnsItsTranscriptToTheActualCallingActivity` | passed | 3.818 |
| `nativeOnnxInferenceAndExtensionsLoadOnThisAndroidRuntime` | passed | 0.263 |
| `manualMicrophoneRecordingSurvivesThirtySecondsUntilExplicitStop` | passed | 33.994 |
| `runtimeUsesTheExpectedPageSize` | passed | 0.013 |
| `nativeVadAcceptsARealPcmFrame` | passed | 0.154 |

## Lint and packaged-artifact checks

- Android lint: zero errors and 38 warnings. These are separate from JUnit test counts.
- Rebuilt/cached Extensions AAR: four 64-bit libraries passed the alignment validator.
- Final APK: ten 64-bit native libraries passed ELF/ZIP alignment checks in Actions.
- Downloaded APK metadata: 20 native libraries over four ABIs; no native debug sections; Extensions MIT license asset present.
- APK SHA-256: `7955053f832a1a26e9feb666334119f05267126db62db52cc69b3041b70cd931`.
- No paid inference, full Whisper-weight accuracy benchmark, physical-phone/OEM/Bluetooth test, signed-release result, or completed CodeQL result is implied by this inventory.
