# Cloud dictation implementation plan

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
- [ ] 5. Verification: meaningful unit tests, XML/resource validation, independent whole-change review; resolve findings. Update README and workflow to run tests, build debug APK in Actions, and report test/build evidence and device-only limits.

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
- No local build/compile/test commands. XML/resource-reference and diff checks only on Mac. Executable tests, lint and APK assembly will run in GitHub Actions, in that order.
