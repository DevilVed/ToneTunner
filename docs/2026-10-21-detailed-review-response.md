# Detailed disposition of the DeepSeek review

This document responds to every finding in the local, ignored `review/deepseek_cr.md`, which reviewed commit `37744fa` on 20 October 2026. The source changed after that snapshot. These dispositions describe the integrated working-tree direction as of 21 October and explain which recommendations were implemented, corrected, or declined based on the product requirements.

## Verification boundary

**Current result:** [Actions run 39533439912](https://github.com/DevilVed/ToneTunner/actions/runs/39533439912) on `3a04b5f` passed every job: the native 16 KB extensions gate, 257 JVM/Robolectric tests in 32 suites (0 failures, 0 errors, 0 skipped), lint with 0 errors and 20 warnings, the API 28, API 34 and API 35 (16 KB page) emulator jobs, and verified APK packaging. This is the first green workflow on the integrated repairs; see the [test inventory](2026-10-20-verification-test-inventory.md) for per-suite counts and [post-integration repairs](#post-integration-repairs-21-october) for what changed after the earlier dispositions were written. Physical-device, live-provider and F-Droid checks remain outside that evidence.

This section originally read as follows. The last complete green workflow is run [39524551595](https://github.com/DevilVed/ToneTunner/actions/runs/39524551595) on `11eda74`: 193 JVM tests in 26 suites, zero failures/errors/skips, lint with zero errors and 39 warnings, the native gate, API 28 and API 35 emulator jobs, and APK packaging passed. That run predates the folder-review repairs.

The tests-first folder-review run [39525899701](https://github.com/DevilVed/ToneTunner/actions/runs/39525899701) on `01bd79c` was deliberately red: 226 tests in 29 suites, 26 failures, zero errors/skips. It demonstrated the expected missing behaviors before their production repairs. It is not evidence that the current repairs pass. No local Android compile, build, or test was run for this response. The integrated source still needs a full GitHub Actions run on its final SHA. The active Android-entry-point work also needs a fresh source review after integration. Physical Android 14/15 microphone-policy, physical-device, provider-quality, and F-Droid server checks remain separate acceptance work.

Test names below point to regression tests in the current source tree. They identify intended coverage; until a workflow on the integrated final SHA passes, they must not be read as passing evidence.

## Critical findings

### C1 — F-Droid source-build path

**Disposition: partly addressed; F-Droid submission remains open.** The review correctly identified that the application build needs an aligned ONNX Runtime Extensions AAR that is intentionally gitignored. Its stronger conclusion that the native dependency cannot be rebuilt from public source was too broad: the repository already had a pinned source rebuild script. This repair adds a portable `tools/native/prepare_extensions.py` entry point, caller-supplied source/AAR inputs, pinned-source and base-AAR hash checks, clean-clone instructions, and a build recipe document. It keeps the generated AAR out of Git.

The [metadata example](../fdroid/metadata-example.yml) is disabled and has not passed `fdroid lint` or an isolated F-Droid server build. The current preparation command also downloads the pinned public source/base AAR when those inputs are not supplied. Therefore ToneTunner can prepare the native dependency from public source, but the repository does not yet have an accepted, tested F-Droid recipe that makes a clean F-Droid server build reproducible. Supplying those inputs avoids the wrapper’s Git/Maven downloads; upstream CMake dependency downloads still require prepared sources/caches for network isolation, which has not been verified. The native preparation tests mock compilation and include `test_offline_base_aar_skips_network_and_preserves_java_and_license_entries`, `test_offline_base_aar_checksum_mismatch_fails_before_native_compilation`, and `test_missing_source_fetches_the_pinned_commit_and_recursive_submodules`; workflow acceptance is pending.

### C2 — Verification statements and evidence

**Disposition: corrected in the documentation; final verification is pending.** The response and engineering record distinguish the historical green checkpoint from the red tests-first review run and do not promote either to proof for the current source. The source now has additional behavior and tests after `11eda74`, so the green checkpoint does not establish those changes. The current final-SHA workflow remains the required evidence. No test can replace that evidence-chain check.

The review's §8.2 “orphan listener method” concern was a tests-first RED, not a false-green pass: the test exposed that `onResultDelivery` was absent from the listener contract at that point. The current fixture marks its `onResultDelivery` implementation with `@Override`, and `AndroidRuntimeTest.speechIntentReturnsItsTranscriptToTheActualCallingActivity` invokes that production callback path before checking the caller's Android result. This corrects the test wiring; the current integrated workflow still has to run these tests.

### C3 — ABI coverage

**Disposition: premise corrected and experimental release packaging broadened; artifact verification pending.** The arm64-only release filter predated this dictation migration, so it was not a newly introduced loss of Android support by this change. The experimental release configuration now packages `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`; CI checks that all four ABI directories are present and runs APK `zipalign` checks.

The native ELF checker still verifies LOAD-segment alignment only for the 64-bit `arm64-v8a` and `x86_64` libraries. The 32-bit libraries are packaged but do not have equivalent ELF LOAD-segment checks, and there is no physical 32-bit-device verification. The all-ABI unsigned release build and its APK checks need to pass on the integrated final SHA before asserting that the artifact is verified.

### C4 — Destination exceptions during result delivery

**Disposition: addressed in source; final workflow result pending.** The main-thread result callback now catches destination `RuntimeException`s, emits only a safe diagnostic, releases the delivery claim, and leaves the completed audio/transcript available for retry or copy. The callback exception text is not logged because it may contain dictated content. `WhisperFailureRecoveryTest.throwingDestinationDoesNotCrashDeliveryOrDiscardCompletedText` and `throwingProgressDestinationDoesNotCrashTheMainLooper` exercise these paths. A passing current-SHA run is still needed.

### C5 — Foreground cancellation status

**Disposition: addressed in source and verified in run 39533439912.** When `MainActivity.onPause()` cancels an active foreground session, it replaces the obsolete “Recording…”/“Transcribing…” state while releasing the screen-awake policy. The replacement status now tells the user what happened to the audio instead of showing “Ready”: once the capture was handed to transcription it says the recording is in Saved dictations; before that it says the capture was interrupted. The keyboard and the recognition Activity give the same distinction for Cancel and pause. The test `RecordingScreenAwakeTest.mainActivityReleasesScreenWhenPausedDuringProcessing` checks both the screen policy and that the stale active status is gone. Leaving the foreground still cancels that live session by design; a completed processing recording remains available in Saved dictations.

## Important findings

### I1 — Overlap at hard cuts in unspaced scripts

**Disposition: addressed with a bounded heuristic; ambiguity remains.** `TranscriptJoiner` now compares Unicode code-point suffix/prefix overlaps for unspaced scripts, including Han, Hiragana, Katakana, and Thai, while retaining the conservative word-based Latin path. It requires at least two unspaced letters before removing a match and keeps ambiguous short text rather than assuming it is duplicate audio. Coverage includes `overlappingHardCutWindowsRemoveCjkCharacterOverlap`, `overlappingHardCutWindowsRemoveJapaneseCharacterOverlap`, `overlappingHardCutWindowsRemoveThaiCharacterOverlap`, `overlappingHardCutWindowsKeepOnlyOneRepeatedJapaneseFullStop`, and `overlappingHardCutWindowsDoNotAssumeASingleCjkCharacterIsOverlap`.

This is textual edge matching, not phonetic alignment. Repeated phrases that happen to be spoken at the boundary can still be mistaken for overlap, and differently transcribed boundary words may remain duplicated. Long-form real-speech quality has not been measured.

### I2 — Brief quiet speech diluted by a whole-window average

**Disposition: addressed with a short-frame near-silence floor; threshold tuning remains open.** First-pass filtering now checks bounded 20 ms frames instead of averaging energy over the full window. Any frame above the fixed RMS floor preserves the whole inference window, so a short utterance is not averaged away by surrounding silence. `WhisperFailureRecoveryTest.shortSoftSpeechInsideALongPauseIsNotDilutedOutOfTheWindow` and `veryQuietNonzeroRoomNoiseDoesNotReachTheModel` cover the positive and noise-control cases.

The threshold is still an amplitude heuristic, not a speech classifier. Speech below that frame threshold may still be skipped; noise above it may still reach the model. Explicit retry continues to bypass the first-pass quiet-window filter so the user can replay retained quiet audio deliberately.

### I3 — Storage failure deleting the captured prefix

**Disposition: addressed in the spool/recovery path; hard-device failure remains.** A failed write or flush now preserves the physically written PCM16-aligned prefix, avoids replaying buffered bytes during cleanup, and attaches a partial-capture warning. If no complete sample can be retained in the spool, recovery can keep a recognizable orphan when the filesystem still permits the rename. Tests include `PcmRecordingTest.writeFailurePreservesThePhysicalPcmPrefixWrittenBeforeDiskFull`, `flushFailurePreservesThePhysicalPcmPrefixWithoutReplayingBufferedBytes`, and `failureWithoutAWholeSampleCanStillBeRetainedAsARecognizableOrphan`.

This salvages bytes the filesystem actually wrote; it cannot reconstruct data rejected by a full or failing device. If the filesystem cannot write or rename any fallback file, no software path can guarantee preservation. The current integrated workflow must confirm the recorder-level warning/delivery path as well as these storage fixtures.

### I4 — No-speech retention, chronology, and storage limits

**Disposition: automatic deletion and storage caps declined; chronology and storage visibility addressed.** The request explicitly requires failed or uncertain audio to remain until the user retries or discards it. Automatically deleting `ERROR_NO_MATCH` captures could destroy a quiet utterance that the first pass failed to recognize. Automatic pruning or a byte/count cap would violate that retention choice, so no-match audio is kept and there is no automatic storage cap.

Saved entries now carry creation time and list newest first (`RecoveryStoreTest.savedDictationsAreListedByCreationTimeRatherThanRandomId`). The Saved dictations UI reports entry count and total audio size so users can inspect accumulated storage and remove chosen items. The remaining limit is deliberate: retained entries consume finite device storage until the user acts, and users should be told that clearly. A full device can still prevent a later capture or recovery write.

### I5 — Enhancement silently shortening the transcript

**Disposition: partially guarded; meaning preservation cannot be inferred from length.** For source pieces of at least 80 code points, cloud enhancement now rejects output shorter than half the input and falls back to the raw transcript with a warning. `CloudClientTest.rejectsSevereEnhancementShorteningForAtLeastEightyCodePoints` covers the severe-shortening threshold.

This catches an obvious summary-like truncation, not semantic loss. A model may rewrite a transcript substantially without crossing the threshold, while legitimate concise edits can also be shorter. Output remains bounded by the existing response limits, but there is no claim of semantic equivalence or token-level containment.

### I6 — Main-thread setup and stop work

**Disposition: addressed in the current entry-point/facade source; integrated verification pending.** Recording archive and `Whisper` settings/Keystore preparation now run on the worker before inference, with a visible preparing/validating state. Settings screens no longer decrypt saved credentials synchronously during open/save, and cloud credential checks run asynchronously before cloud capture. Entry-point stop actions dispatch `Recorder.stop()` away from the main thread. Accepted recordings remain queued for archive if inference is immediately cancelled or the facade is unloaded.

Coverage includes `WhisperFailureRecoveryTest.startDoesNotOpenPrivateStorageOrAiSettingsOnTheMainThread`, `unloadingImmediatelyAfterSubmissionStillArchivesTheAcceptedRecording`, and `AiSettingsCredentialThreadTest.openingSettingsDoesNotReadSavedKeysOnMainThread` / `savingSettingsDoesNotReadExistingKeysOnMainThread`. The foreground checks, error-state transitions, and every entry point still require final Robolectric/Android workflow confirmation. The UI can still perform lightweight local validation and status changes synchronously; the expensive storage/Keystore operations are the intended off-main-thread boundary.

### I7 — Opaque HTTP request errors

**Disposition: addressed in source.** HTTP 400, 404, and 422 now produce a safe, actionable message asking the user to check the model name, endpoint, and account access. Provider response bodies and keys remain excluded from the user-facing error. `CloudClientTest.mapsBadRequestNotFoundAndUnprocessableEntityToActionableSafeErrors` exercises these statuses and verifies that private response text is not echoed. Verification on the final integrated SHA remains pending.

### I8 — Local decoder `[(und)]` marker entering output

**Disposition: addressed in source.** The local-only `[(und)]` decoder sentinel is now treated as a failed window, so it is not appended to the transcript or sent to enhancement. The completed checkpoint and full audio are retained for explicit retry. `WhisperFailureRecoveryTest.localDecoderFailureMarkerIsRetainedAsAFailedWindowRatherThanDeliveredAsText` checks that no result is delivered and that recovery begins at the failed window.

The check applies to this exact engine sentinel. Other malformed but non-empty model output is not semantically validated here; final workflow acceptance is pending.

### I9 — Model-catalog response limit

**Disposition: addressed with a separate bounded catalog cap.** Model discovery now has an 8 MiB response limit while inference requests remain at 1 MiB. This leaves headroom beyond the reviewed 785 KB OpenRouter chat catalog and permits catalogs above the inference cap while keeping discovery memory bounded. `CloudClientTest.acceptsModelCatalogsLargerThanTheInferenceResponseLimit` and `rejectsModelCatalogsLargerThanEightMiBWithinTheCatalogBound` cover the lower and upper bounds.

Catalogs larger than 8 MiB still fail safely; pagination or provider-specific filters may be needed if a catalog grows past the chosen bound. Current source behavior awaits final workflow verification.

### I10 — Translation preflight

**Disposition: implemented for app and voice-keyboard translation; standard speech-intent behavior remains transcription by contract.** The supported-model check is public to the entry points and happens before cloud credentials/consent or microphone capture. Local translation remains allowed. `EntryPointTranslationPreflightTest.mainActivityRejectsUnsupportedTranslationBeforeCloudConsentOrKeyChecks`, `inputMethodRejectsUnsupportedTranslationBeforeCloudConsentOrKeyChecks`, and `mainActivityAllowsLocalModelTranslationWithoutCloudConsentOrKey` exercise these decisions. The core API cases are covered by `CloudClientTest.openAiWhisperTranslationUsesTranslationEndpointAndOmitsSourceLanguage` and `groqWhisperTranslationUsesTranslationEndpointAndOmitsSourceLanguage`.

The standard `RECOGNIZE_SPEECH` Activity returns a speech-recognition result for its caller and therefore always transcribes. It does not inherit the standalone app's translation checkbox. This distinction is intentional and should not be reported as an unsupported translation request being accepted after a long recording. The active Android entry-point changes still need final integrated verification.

### I11 — IME host restart cancelling the current editor

**Disposition: addressed with editor and connection identity checks; OEM behavior remains untested.** An IME restart preserves pending work only when it is marked as a restart, editor metadata matches, and the current `InputConnection` object is the same. A genuinely different/finished editor, connection, or privacy state invalidates delivery. Tests include `restartingSameEditorWithSameConnectionPreservesPendingResult`, `restartingDifferentEditorInvalidatesPendingResult`, `sameEditorRestartThatBecomesPrivateInvalidatesPendingResult`, and `hostSelectionUpdateWithinSameEditorPreservesDictation`.

An OEM that replaces the connection during what it considers a same-field restart will be handled conservatively by cancellation, rather than risking insertion into the wrong field. If processing has already archived the recording, the result remains recoverable; a live capture cancelled before submission follows the documented rule and is discarded. There is no device/OEM matrix verification yet.

### I12 — Automatic-mode duration and start behavior

**Disposition: proposed hard cap declined; behavior and user controls documented.** Manual recording intentionally has no duration cap so users can keep a dictation open through thinking pauses. Automatic mode keeps its initial-silence timeout and selected VAD stop-after-silence behavior; no total-duration cap is added after speech starts. In a noisy environment, VAD may not observe enough silence and automatic recording may continue until the user stops it, subject to available storage. Automatic-start-on-field-focus remains part of the selected mode and is documented; visible controls and cancellation remain available.

`RecorderFailureTest.autoStopEndsAfterThirtySecondsOfInitialSilence` and `manualCaptureRemainsActivePastTheAutoModeInitialSilenceDeadline` cover the initial timeout/manual-mode distinction. The timeout is checked after microphone reads, so a platform read that itself blocks cannot be interrupted by this guard. Noise robustness and long-duration disk behavior are not established by emulator fixtures.

### I13 — RecognitionService callbacks and Android microphone policy

**Disposition: callback protocol repaired in source; platform permission acceptance remains open.** The service now emits `readyForSpeech()` after recorder startup confirms microphone readiness and emits `beginningOfSpeech()` only after the recorder reports detected speech. Stop work is dispatched off the caller thread. `WhisperRecognitionServiceDeliveryTest.readyForSpeechWaitsUntilRecorderConfirmsMicrophoneIsReady` and `beginningOfSpeechWaitsForRecorderSpeechDetection` cover the callback timing.

The instrumented test `AndroidRuntimeTest.boundRecognitionServiceStartsMicrophoneAndWaitsForSpeechOnModernAndroid` is configured to exercise a bound service on API 34 and API 35 emulators, but the folder-review RED workflow stopped before that gated instrumentation job ran. It does not establish physical Android 14/15 microphone/foreground policy. Thus the framework callback fix does not close the platform lifecycle question; the API 34/35 test jobs and a physical-device check still need execution. The test is explicitly skipped on API 28.

### I14 — Manifest permissions and recovery privacy

**Disposition: partly addressed; plaintext recovery is documented by design.** `QUERY_ALL_PACKAGES` and `RECEIVE_BOOT_COMPLETED` have been removed because the app has no matching package-enumeration or boot-receiver feature. `AndroidManifestPermissionsTest.manifestDoesNotRequestPackageEnumerationOrBootReceiverPermission` covers those removals. `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` remain alongside microphone-typed service declarations; whether those declarations and permissions are still needed for supported service behavior should be revisited with the pending Android 14/15 check.

Recovery PCM and transcript metadata remain plaintext inside app-private, no-backup storage. This is disclosed in README and the engineering record. Encrypting that data is deferred: it adds a separate storage/lifecycle design and is not implied by the existing Keystore protection of API keys. This disclosure is not a claim that app-private storage protects data on a rooted or compromised device.

## Minor findings

### M1 — Detected-language display

**Disposition: addressed in source at `ea6fc60`.** An earlier version of this response marked M1 as addressed, but `MainActivity.handleResult` still displayed the raw language value, so `auto` appeared as a language. The screen now passes the value through `CloudClient.normalizeDetectedLanguage` and shows a language only when the provider reported one. `CloudOnboardingTest.mainScreenDoesNotDisplayAutoAsADetectedLanguage` covers the UI case. Language aliases still depend on provider metadata quality.

### M2 — Recognition Activity launch flag

**Disposition: addressed in source.** `clearTaskOnLaunch` was removed from the speech-recognition Activity so `NEW_TASK` callers are not unexpectedly stripped of their result route. `WhisperRecognizeActivityRequestContractTest.speechActivityUsesStandardLaunchModeToPreserveEachCallerResultRoute` covers the request/result launch contract.

### M3 — Settings rotation losing edits

**Disposition: addressed for configuration changes in source.** Unsaved settings drafts now survive Activity recreation in an in-memory nonconfiguration instance; typed API keys are not placed in saved Bundle state. `CloudOnboardingTest.unsavedCredentialAndPromptDraftSurviveConfigurationChangeInMemoryOnly` and `typedApiKeyIsNeverIncludedInSavedActivityState` cover these boundaries.

This is intentionally in-memory across configuration change, not durable storage across process death. A process kill can still discard unsaved edits.

### M4 — Fullwidth CJK sentence boundaries

**Disposition: addressed in source.** `TextWindows` now recognizes fullwidth exclamation, question, and semicolon punctuation when selecting enhancement split boundaries. `TextWindowsTest.fullwidthSentencePunctuationIsPreferredAsASplitBoundary` covers the case. Enhancement remains piecewise and has no cross-piece model context.

### M5 — Asterisk branch in overlap joiner

**Disposition: review claim corrected; branch retained.** The branch is reachable for an opening Markdown emphasis marker split at the end of a text window, so deleting it would regress joining. `TranscriptJoinerTest.openingMarkdownAsteriskAtWindowEndJoinsEmphasisWithoutASpace` preserves that behavior. No code removal is warranted.

### M6 — Metadata rewrite cost

**Disposition: bounded tradeoff retained.** Recovery checkpoints continue to use one atomic JSON metadata snapshot, rewriting the transcript as progress advances. A journal would add recovery and migration states without evidence that it is necessary at expected dictation sizes. Metadata is capped at 8 MiB; a checkpoint that cannot be encoded/saved keeps the prior complete metadata and the source audio for retry. The tradeoff remains: repeated full-text serialization has quadratic worst-case I/O as the transcript grows, up to the metadata cap. Storage and very long transcript stress performance have not been benchmarked.

### M7 — Atomic metadata replacement

**Disposition: addressed with Android `AtomicFile`; non-atomic fallback declined.** Metadata writes use `AtomicFile`, synchronize a static lock across store instances, sync the file descriptor, and read back the committed bytes before treating the new checkpoint as current. This preserves backup recovery on Android versions where `ATOMIC_MOVE` is not available and avoids destructive overwrite. `RecoveryStoreTest.interruptedAtomicCheckpointRestoresThePreviousCompleteMetadata` covers backup recovery; existing retry/storage-failure tests cover preserving an older snapshot. No weaker non-atomic replacement is used.

### M8 — No-recording error category

**Disposition: addressed in source.** The no-recording path reports a client error rather than falling through to a generic server error. Empty/no-speech recognition is separately reported as `ERROR_NO_MATCH`. `WhisperFailureRecoveryTest.absentRecordingReportsClientErrorWithoutOpeningAModel` covers absent input, and `noSpeechHasANoMatchErrorInsteadOfAGenericServerError` covers an actual empty recognition result; final integrated workflow verification remains pending.

### M9 — Credential reads on the settings UI thread

**Disposition: addressed in source; final workflow result pending.** Saved-key status checks, credential validation, model discovery lookups, and settings saves use the settings executor. `AiSettingsCredentialThreadTest.openingSettingsDoesNotReadSavedKeysOnMainThread` and `savingSettingsDoesNotReadExistingKeysOnMainThread` guard the main-thread boundary. These tests do not establish provider availability or real Keystore performance on every device.

### M10 — Translation preference in the standard recognition Activity

**Disposition: recommendation declined as a contract mismatch.** The `RECOGNIZE_SPEECH` Activity implements Android's recognition-intent contract: it returns what was recognized to the calling application. The separate ToneTunner translation preference belongs to ToneTunner's own recorder/keyboard flows and must not silently change a third-party caller's requested speech-recognition semantics. It always transcribes; local/app translation remains available where explicitly selected in ToneTunner. `WhisperRecognizeActivityRequestContractTest.separateRecognitionRequestsKeepSanitizedLanguagesAndResultsIndependent` covers independent intent result routes and language inputs; it is not presented as a test of translation-preference handling.

### M11 — Main Activity permission grant flow

**Disposition: addressed in source with focused permission-flow regressions.** `MainActivity` remembers that recording was requested while microphone permission was missing and calls `startRecording()` after a grant; denial clears the pending request. The behavior is visible in `onRequestPermissionsResult`. `EntryPointTranslationPreflightTest.mainActivityResumesRecordingAfterMicrophonePermissionGrant` and `mainActivityDoesNotStartRecordingAfterMicrophonePermissionDenial` exercise that request/result/start sequence with a microphone shadow. Long-hold release/cancellation before a grant is additionally checked so a granted permission cannot start an abandoned push-to-talk capture. Integrated cloud execution remains pending.

### M12 — Tracked `.DS_Store`

**Disposition: unrelated pre-existing user change preserved.** `.DS_Store` is tracked and appears modified in the shared working tree. That modification predates this response and is excluded from the repair/documentation commits; this task does not reset, edit, stage, or commit it. The review's hygiene observation can be addressed separately without overwriting the user's local file. The engineering record records this exclusion.

### M13 — Build-tools version mismatch

**Disposition: addressed in source.** Both Android workflows now install and use pinned Android build-tools `35.0.0` for APK alignment/signing checks. The release workflow no longer selects the runner's latest installed build-tools version. Workflow execution on the final integrated SHA remains pending.

### M14 — 32-bit native alignment claim

**Disposition: documentation corrected; 32-bit ELF verification remains open.** The native README now distinguishes checks: the Python ELF checker verifies LOAD segments in 64-bit `arm64-v8a` and `x86_64` libraries; Android `zipalign` checks packaged APK payload alignment, and CI can require all four ABI directories. This does not establish 32-bit ELF LOAD-segment alignment. No 32-bit physical-device verification is claimed.

## Remaining acceptance work

The current source includes the dispositions above, but they do not substitute for final verification. Before describing the migration as accepted, run the complete Android workflow on the integrated final SHA, inspect its exact JVM/lint/native/API 28/API 34/API 35 and unsigned all-ABI APK results, then update the test inventory and README evidence to that SHA. Separately, run `fdroid lint` and an isolated F-Droid server build before describing F-Droid as supported. Keep physical Android 14/15 `RecognitionService` and IME microphone checks, physical 32-bit testing if that ABI is advertised, real-provider behavior, and long-recording speech-quality assessment as explicit remaining limits.


## Additional test-audit and integration findings

The review’s §8.2 items are also tracked: production delivery acknowledgement is exercised instead of the legacy void callback; actual API 28 framework constructor selection fixes the two setup failures; CJK/Thai overlap, ENOSPC prefix salvage, safe 400 mapping and >1 MiB catalog fixtures have been added. `RecorderVadTest.automaticModeContinuesPastThirtySecondsOfSpeechThenStopsAfterVadSilence` drives the real recorder’s speech-start/stop branch with a fake classifier and microphone source: it checks 36 seconds of simulated speech followed by VAD silence, a single speech-start event, final audio ownership, and cleanup before DONE. It verifies the recorder branch, not the real classifier’s accuracy or silence hysteresis. The fake suppresses native initialization; the pinned [upstream VadWebRTC source](https://raw.githubusercontent.com/gkonovalov/android-vad/2.0.10/webrtc/src/main/java/com/konovalov/vad/webrtc/VadWebRTC.kt) implements its own speech/silence hysteresis and native library loading, which remain separate from the recorder fixture.

The pre-existing `ZipSlipSecurityTest` remains a standalone harness containing a copied path helper; converting it mechanically to annotated methods would count a mirrored helper rather than test the actual extraction path. It remains explicitly excluded from the executed JUnit inventory. The app’s model-download/extraction path and a repository-wide security scan are outside this dictation repair; no security-audit completion is implied by the harness.

Independent integration review found two asynchronous UI races beyond the original IDs. Releasing a long push-to-talk hold during validation or the permission prompt must cancel the pending capture; a short tap retains toggle intent. Tests dispatch real touch events while a latch blocks credential validation so the microphone cannot open after release. Settings saves and key removals now share a process-wide serial queue so a save accepted by an old Activity cannot finish after a newer edit following rotation; destroying the old Activity cancels discovery/status work and UI callbacks, while accepted mutation order remains intact. `AiSettingsCredentialThreadTest.acceptedSavesSurviveRotationAndCommitInUserSubmissionOrder` covers this ordering with the first credential operation held pending.


A further service boundary was corrected during integration: a disconnected recognition caller can throw from the optional final RMS callback immediately after recording stops. The service now transfers the completed capture to `Whisper` before that callback. Cancellation therefore drains its queued archive instead of closing a recorder-owned published spool. `WhisperRecognitionServiceDeliveryTest.completedCaptureIsArchivedWhenClientDisconnectsOnFinalRmsCallback` checks a real PCM spool and real recovery store after the actual Whisper worker terminates.

Native source preparation now also rejects modified/untracked source and uninitialized, mismatched, or dirty recursive submodules before compilation; Git-ignored build outputs are allowed. Rebuilt and cached Extensions AARs must contain exactly both expected JNI libraries in each of four ABIs, so another dependency’s ABI folder cannot hide an incomplete Extensions package. These are source/provenance and package-integrity checks, separate from ELF alignment and device execution.

## Post-integration repairs (21 October)

A line-by-line review of the integrated source, plus the first CI runs that actually compiled it, found the following. Each fix is in a named commit and is covered by run 39533439912.

- **M1 was not implemented** (see above). Fixed in `ea6fc60`; `CloudOnboardingTest.mainScreenDoesNotDisplayAutoAsADetectedLanguage` now passes.
- **Cancel and pause messages hid saved audio.** After a capture was submitted, Cancel said only “Dictation cancelled”, although `Whisper` archives every accepted capture before work starts. The main screen, keyboard and recognition Activity now say the recording is in Saved dictations (`ea6fc60`).
- **Stale keyboard status.** When the text field closed before delivery, the keyboard kept showing “Transcribing…”. It now clears the progress bar and explains where the text went (`ea6fc60`).
- **Recognition Activity record button stayed disabled** after a cancel that followed stop/transcription (`ea6fc60`).
- **Misleading retry and discard errors.** Retrying a saved dictation that had been discarded reported a generic storage failure; it now reports that the saved dictation is unavailable. A discard after the Activity's executor shut down no longer throws (`ea6fc60`).
- **Empty saved dictations.** A storage failure before one full PCM16 sample was written created a Saved dictations entry with no audio. That capture is now closed instead (`ea6fc60`).
- **Sub-0.1 s cloud windows.** When a silent window ended at the 28 s bound, no overlap was replayed and the remainder could be a few milliseconds long. OpenAI rejects audio under 0.1 s, which failed the whole dictation, and every explicit retry failed the same way. Such windows cannot hold a spoken word and are no longer uploaded. `WhisperCloudWindowTest.subTenthSecondTailAfterASilentHardCutWindowIsNotUploaded` reproduces it (`3a04b5f`).
- **“1 saved dictations”.** The Saved dictations summary now uses a plural resource (`3a04b5f`).
- **CodeQL code scanning failed the PR check** with 29 new alerts once the PR was opened against `beta-test-v1`. Seventeen were `java/toctou-race-condition`: a synchronized state check followed by a second synchronized call on the same object without holding its lock. None changed what is delivered, because every side effect already re-checked under `Whisper`'s monitor and `gate.complete()` on the main thread decides delivery. The locking is now explicit: worker-side checks use a synchronized `isCurrent(token)` that shares `stop()`'s monitor, and `RecoveryStore.save` and the failed-archive path hold the recording's own monitor across check and use. Also fixed: an array bound CodeQL could not prove, a wrapper stream in `SecretStore` that was never closed, six impossible null checks and an unused parameter. Three deprecation notes remain (`startBluetoothSco`/`stopBluetoothSco` and `getLastCustomNonConfigurationInstance`); replacing them changes Bluetooth routing and settings rotation and needs phone testing. CodeQL passes at `0213369` ([run 39537618731](https://github.com/DevilVed/ToneTunner/actions/runs/39537618731)).
- **Three tests that had never run** failed on first compilation (run 39531710536): `RecorderVadTest` crashed Robolectric's bytecode instrumenter on the Kotlin VAD class, so `Recorder` now reaches the native VAD through a package-private `SpeechClassifier` interface; `EntryPointTranslationPreflightTest` loaded the native VAD library because its recorder shadow did not cover `initVad`; and `WhisperOwnershipTest` still expected `stop()` to delete a submitted capture, which contradicts the archive-before-work design, so it now asserts that the capture is in Saved dictations (`5c25c1d`).

## Phone testing on Android 16 (21 October)

The first install on a physical Android 16 phone showed two problems the green workflow had not caught.

- **AI settings crashed as soon as it opened** on Android 15 and newer. `AiSettingsActivity` called `ThemeUtils.setStatusBarAppearance()` before `setContentView()`; on API 35+ that calls `Window.getInsetsController()`, which `PhoneWindow` implements as `mDecor.getWindowInsetsController()`, and there is no decor yet. The new emulator test `AiSettingsScreenTest` reproduced the exact `NullPointerException` on API 35 (run 39542785984) while API 34 passed. Robolectric runs as API 28, where the helper does nothing, and no emulator test had opened the screen. Fixed in `c520747`: the call moved after `setContentView()`, and `ThemeUtils` now returns if the window has no decor. The same commit makes the code-built settings page fit system windows, because Android 15+ draws targetSdk 35 apps edge to edge.
- **There was no settings screen.** Apart from AI settings, options were scattered or reachable only through Android's settings. `b629a43` adds a Settings screen on the main screen that links to AI providers, the local model (with installed status), hands-free mode, the voice keyboard (with enabled status), voice input for other apps, and app info. The local-model screen now returns to Settings instead of stacking a second main screen.

CI now also runs the emulator suite on Android 16 (API 36). Run 39543504900 on `b629a43` passed all four emulators; on the API 36 image all 10 instrumented tests ran, including opening AI settings from the main screen, in dark mode, and through Settings.
