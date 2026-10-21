# Cleanup and transcript history implementation plan

> **For agentic workers:** Use scoped parallel implementation and independent branch review. Execute tests and Android builds in GitHub Actions.

**Goal:** Make cleanup instructions explicit system prompts and retain every recognized transcript for later copy/paste.

**Architecture:** Separate prompt composition, literal dictionary corrections, durable text history, and existing audio recovery. Save recognition progress before enhancement and the final result before delivery.

**Tech stack:** Java 11, Android SDK 35 / minSdk 28, SQLite, existing OkHttp and Android Keystore, Gradle 8.14.5 in Actions.

**Spec:** [Engineering study](../../2026-10-21-voiceink-cleanup-history-engineering.md).

## Global constraints

- PR base: `beta-test-v1`; starting commit `791e861`.
- Preserve DevilVed identity, `org.DevilVed.whisperplus`, licensing, credits, and `.jules` history.
- No local Android builds or executable tests, provider charges, secrets in output, or reference-clone files in commits.
- Keep audio recovery and successful text history independent; no automatic text expiry.

## Review focus

- Cancellation and failed delivery preserve completed recognized text.
- Enhancement failure never turns an error message or partial output into the copy target.
- Replacement collisions, Unicode boundaries, dollar signs/backslashes, and retries do not corrupt words.
- Reopened history paginates and copies full stored text, with independent deletion.
- Unenforceable transcription ZDR fails before network I/O; existing local users remain local.

## Task 1: Record the engineering design before implementation

- [x] Inspect the merged commit history and pin the cloned VoiceInk source.
- [x] Write the engineering study, including exact system/user roles, history lifecycle, differences, alternatives, and limitations.
- [x] Commit the study before product implementation.

## Task 2: Durable text history

**Files:** new `asr/TranscriptHistoryStore.java`, new `TranscriptHistoryActivity.java`, main screen/layout/manifest and tests.

**Interfaces:** `TranscriptHistoryStore(Context)`, `void saveRaw(String id, String rawText, String language) throws IOException`, `void saveResult(String id, String rawText, String text, String language, String warning) throws IOException`, `Entry get(String id) throws IOException`, `List<Summary> list(int limit, int offset) throws IOException`, `void delete(String id) throws IOException`. Entry fields: `id`, `rawText`, `text`, `language`, `warning`, `createdAt`, `updatedAt`. Summaries contain bounded previews and metadata only. `text` falls back to raw for incomplete entries. Empty raw text is ignored. Stable IDs and creation timestamps survive upserts. Store body strings in bounded Unicode-safe child-table chunks and update atomically to avoid CursorWindow limits. Implement store AutoCloseable or close all handles per operation. Keyboard private-editor history flag is snapshotted per operation; root owns that integration.

- [x] Add a regression on existing Whisper delivery proving successful text must remain saved independently of recovery.
- [x] Add store tests for reopening, raw/final separation, stable-ID retry, ordering/pagination, Unicode, deletion and safe errors.
- [x] Implement SQLite storage in `noBackupFilesDir` and a paged, non-exported history Activity with explicit Copy result, Copy raw and confirmed Delete.
- [x] Connect a Transcript history control from the main screen; keep Saved dictations as recovery.
- [x] Root integrates saveRaw at each recognition checkpoint and saveResult before delivery. History failure reports an error while preserving recovery.

## Task 3: Cleanup instructions and exact dictionary corrections

**Files:** new focused prompt/dictionary helpers, `CloudClient.java`, `Whisper.java`, settings and tests.

**Interfaces:** `AiSettings.useBuiltInCleanupRules()`, `AiSettings.getReplacements()`, overload `CloudClient.enhance(String text, ProviderConfig config, String prompt, String vocabulary, boolean useBuiltInRules)`; existing four-argument call delegates to built-in mode. Existing saveSettings overload remains compatible; expanded overload appends `(boolean useBuiltInRules, String replacements)` after its current arguments. `DictionaryRules.apply(String text, String vocabulary, String replacements)` and `DictionaryRules.validate(String vocabulary, String replacements)` are pure Java; invalid rules fail settings validation. `EnhancementPrompt.system(String prompt, String vocabulary, boolean useBuiltInRules)` builds the system string.

- [x] Record failing request assertions for clear task instructions, structured vocabulary and old-mode compatibility.
- [x] Implement an original cleanup template, optional raw system mode, JSON-array vocabulary and transcript data isolation.
- [x] Implement literal longest-first single-pass corrections, exact canonical vocabulary casing, Unicode word boundaries, malformed-rule validation and no cascades.
- [x] Apply explicit corrections once before enhancement and canonical vocabulary casing to final output; retain original ASR text in history/recovery. Disable severe-shortening guard only for explicit raw-system mode.
- [x] Settings expose wrapper choice and a separate replacement field usable without enhancement, preserve old preferences, reject empty advanced prompts and over-limit inputs on Save.

## Task 4: Focused settings/privacy repairs

**Files:** `AiSettings.java`, `AiSettingsActivity.java`, `CloudClient.java`, strings and tests.

- [x] Add failing regressions for the local default, content-capture exclusion, ZDR preflight and safe discovery error categories.
- [x] Keep absent provider choice local, add explicit Content Capture exclusion on supported API levels, and retain existing encrypted store.
- [x] Reject OpenRouter transcription with required ZDR before upload; preserve chat privacy routing and explain transcription limitation in UI.
- [x] Separate credential-read failures from model discovery network/provider failures.

## Task 5: Cloud verification, review, and pull request

- [x] Push regression checkpoint and inspect the failing Actions assertions before production repairs.
- [x] Run the full Actions JVM/lint/emulator/APK gates on the integrated revision; fix observed failures.
- [x] Obtain a fresh source review of the complete branch, implement material corrections, and verify again when changed.
- [x] Update engineering record and README with actual evidence and remaining limits.
- [x] Create and attach a PR targeting `beta-test-v1` with a usable verified APK link when available.

## Execution record

The user authorized research, engineering documentation first, implementation, and a PR in this session. The document-first milestone is complete. Work uses scoped parallel agents with root-owned integration and no repeated approval gates for the authorized work.

Application/test revision `f7b408e` passed Android build 39579565565 (attempt 2) and Code checks 39579565551. The engineering study records the 299 JVM tests, four runtime matrices, intentional API 28 skip, API 35 startup rerun, reviewed fixes and verified APK. The final documentation commit does not modify application or test sources.

## Follow-up: pagination review comment

- [x] Inspect the reported insert-while-away flow and add idle-resume and in-flight-load regressions.
- [x] Observe the idle-resume regression fail in Actions before pushing the production correction.
- [x] Refresh from page zero on resume, coalesce refreshes during I/O, and discard stale page results.
- [ ] Verify the complete JVM/lint/emulator/APK gates and record the tested revision before resolving the review thread.
