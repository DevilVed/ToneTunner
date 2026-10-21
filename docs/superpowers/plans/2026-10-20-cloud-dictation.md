# Cloud dictation implementation plan

This is the original implementation checklist. Its completion described the first `bd0e80a` build, whose uncovered regressions were subsequently confirmed. The current contracts are in the [updated specification](../specs/2026-10-20-cloud-dictation.md), and the [review repair plan](2026-10-20-dictation-review-repairs.md) records the additional tests and repairs. The older evidence below is retained as history, not current acceptance of destructive cleanup or selection cancellation.

> **For agentic workers:** Use focused implementation and independent review; respect the file ownership below.

**Goal:** Deliver user-configurable cloud speech transcription and optional text enhancement in ToneTunner.

**Architecture:** A shared dictation facade selects local or HTTPS cloud transcription and independently enhances the transcript. A dedicated settings Activity manages provider configuration and encrypted keys. Existing Android entry points retain their text-delivery APIs.

**Tech stack:** Java 11, Android SDK 35/minimum 28, OkHttp 4.12.0, Android Keystore AES-GCM, JUnit 4.

**Spec:** ../specs/2026-10-20-cloud-dictation.md

## Global constraints

- Preserve DevilVed identity, applicationId org.DevilVed.whisperplus, Java package com.devilved.tonetunner, licensing, credits, and .jules.
- No real API keys or paid inference during implementation. Ignore the unrelated existing .DS_Store change.
- Do not require local ONNX files for cloud mode. Do not silently change providers or weaken ZDR.
- No local Android SDK installation. Build via the existing GitHub Actions workflow.

## Review focus

- Late recording/network callbacks after cancel or editor switch must never insert text into another field.
- API keys, dictated content, and provider error bodies must never enter logs, saved state, or crash messages.
- Enhancement truncation/refusal/empty output must not be inserted as successful dictation.
- Switching provider or endpoint must not accidentally reuse credentials for another destination.
- Local model setup and existing recognition entry points must remain usable.

## Tasks

- [x] 1. Cloud transport: Provider, ProviderConfig, CloudClient, AudioWav and JVM fixture tests. Owner: cloud agent. Only cloud files excluding AiSettings/SecretStore; app/build.gradle dependency additions.
- [x] 2. Settings: AiSettings, SecretStore, AiSettingsActivity and dedicated settings layout/strings. Owner: settings agent. Follow the spec's public contracts; no edits to existing Activities or manifest.
- [x] 3. Shared pipeline: adapt Whisper facade, snapshot/clear audio, retain local ASR and cancellation, apply enhancement, remove transcript logging. Owner: primary agent.
- [x] 4. Android integration: settings access, permission/setup flow, progress/errors, keyboard session guards and sensitive fields; recognition lifecycle. Owner: primary agent.
- [x] 5. Verification: meaningful unit tests, XML/resource validation, independent whole-change review; resolve findings. Update README and workflow to run tests, build debug APK in Actions, and report test/build evidence and device-only limits.

## Progress and review record

- Cloud and settings implementations integrated; independent static review confirmed current OpenRouter STT JSON privacy parameters, key isolation, bounded transport, cancellation, and response closure.
- Fixed settings/transport disagreement for custom HTTPS ports and nonstandard compatible routes; added a validator fixture.
- Enhancement has explicit provider-compatible output budgets and rejects truncation, refusal, empty output, and unsupported mixed content. Provider fixtures cover these response shapes.
- User added unlimited manual dictation. Capture now spools PCM to private disk; sequential pause-aware windows cap audio RAM and local inference sizes. Both Activities and IME remove their countdown, and tap-to-toggle complements push-to-talk.
- Added gate/session eligibility, audio ownership, Unicode text window, gesture, configuration/consent, spool, and secure-storage crypto/corruption tests. The suite contains 97 test methods, including fake-backend local lifecycle tests and Robolectric recording/facade ownership checks.
- Pipeline review identified callback ownership, recognition-service silence compatibility, local engine failure reuse, overlapping native model lifetimes, cross-entrypoint audio ownership, and CJK/punctuation joins. Implemented corrections and targeted tests; a fresh static re-review found no remaining high-confidence blocker before Actions verification.
- Local model memory is protected by a process-wide lease that is returned only after native session cleanup. A model is reused across chunks within one operation, then closed at the end of that operation so an idle facade cannot block another entrypoint. This adds model initialization cost to later local dictations. Capture files belong to the recorder that created them; cancellation cannot claim another entrypoint's audio.
- New capture removes crashed-process spool leftovers while preserving all live recordings. Recording, cancellation, and terminal paths delete their owned files.
- Public OpenRouter catalogs verified without keys or audio: 26 transcription entries with the expected modality metadata; text catalog below the response cap. Executable provider tests continue to use local HTTP fixtures in CI.
- No local build/compile/test commands. XML/resource-reference and diff checks only on Mac. Executable tests, lint and APK assembly ran in GitHub Actions, in that order.

## Cloud verification evidence

- Verified application commit: `6dc4e44377e834f675a9b22a0820dbb918e11502` on `agents/voiceink4android`.
- [Successful GitHub Actions run](https://github.com/DevilVed/ToneTunner/actions/runs/39425136179): `testDebugUnitTest lintDebug` passed before `assembleDebug` ran and passed.
- Downloaded JUnit XML reports: **97 tests in 17 suites; zero failures, errors or skipped tests**.
- Lint SARIF: **zero errors, 44 warnings**. Warnings include legacy native library 16 KB alignment, dependency/toolchain updates, and layout/style recommendations. Physical-device/native compatibility remains to be validated before release.
- APK artifact: `ToneTunner-agents-voiceink4android-6dc4e44-debug`; size 102,452,991 bytes; SHA-256 `82b608a0b9d1d36ba40f1771d21233711ef176f0cdfd5e90321680dbc7ce95d7`.
- Earlier cloud compiler runs caught a stale Activity helper call and a test-only crypto import; corrected both, then ran the full verification suite successfully.
- Verification uses fixture HTTP responses, real JVM crypto with an injected test key source, Robolectric UI/lifecycle checks, and fake local transcribers. It does not demonstrate live-provider quality, physical microphone/Bluetooth behavior, actual ONNX inference performance, or complete platform IME/RecognitionService integration on a phone.

## Final Android acceptance

The initial 97-test result above is historical. The [completed runtime verification record](2026-10-20-runtime-verification.md#final-acceptance) supersedes it with successful [Actions run 39444393039](https://github.com/DevilVed/ToneTunner/actions/runs/39444393039), application/build commit `bd0e80a`: **121 JVM tests**, **six artifact-validator tests**, **six connected Android tests on each API 28 and API 35/16 KB emulator**, zero test failures/errors/skips, lint zero errors/38 warnings, and validated APK assembly. This includes real Android Keystore/native inference/VAD, Activity-result routing, and microphone capture beyond 30 seconds. Foreground capture, transcription, and enhancement keep the display awake until completion/error/cancel/backgrounding. All build/test work ran in Actions; physical phone and live-provider quality testing remain separate.

The [final debug APK artifact](https://github.com/DevilVed/ToneTunner/actions/runs/39444393039/artifacts/12277090921) is 172,099,534 bytes; SHA-256 `7955053f832a1a26e9feb666334119f05267126db62db52cc69b3041b70cd931`. It contains all four ABIs and is intended for experimental phone testing on this branch.
