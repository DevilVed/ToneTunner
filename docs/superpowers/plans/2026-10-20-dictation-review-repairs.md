# Dictation review repairs implementation plan

> **For agentic workers:** Use Superpowers systematic-debugging, test-driven-development, scoped parallel work, and verification-before-completion. All executable tests/compilation/builds run in GitHub Actions, never on the Mac.

## Accepted contracts

The review identified destructive terminal cleanup, enhancement failure discarding valid raw text, unbounded initial silence in automatic mode, late microphone read failure losing captured audio, per-window peak amplification, incomplete language normalization, joining/paragraph bugs, and cancellation on host-driven selection changes. Verify each against source and regressions before claiming a fix. The user explicitly chose to retain failed dictations privately until retry/discard.

- Enhancement failure returns the complete raw transcript with a visible warning; cancellation still cancels.
- Transcription failure preserves the full audio and completed transcript/checkpoint in private no-backup recovery storage. The main app exposes preview/copy, explicit resume, and discard. No automatic billable retries. Resume starts at the failed window, keeping completed text and pinning detected language after the first useful result. Successful recovery deletes its saved audio; failed/cancelled recovery retains it.
- Automatic mode has a bounded initial-speech timeout independent of manual recording duration; no-speech maps to the appropriate Android speech error. A late microphone read failure finalizes captured audio with a warning and closes the microphone; an empty failure reports an audio error.
- Local PCM conversion preserves absolute amplitude. Quiet-window handling must avoid treating nonzero low-level room noise as full-scale speech. Boundary context must be bounded and document heuristic limits; no claims of word accuracy without speech benchmarks.
- Legacy language aliases normalize; valid 2/3-letter tags survive. Opening punctuation and enhancement paragraphs retain intended separators.
- Host selection notifications inside the same editor do not destroy dictation. Explicit cancellation/editor replacement/private-field checks still invalidate delivery.

## Work and evidence order

1. Root investigates shared pipeline/cleanup and writes pipeline failure + editor-selection regressions. Parallel workers investigate/write recorder, language/joining, and signal/boundary regressions in separate files.
2. Commit tests without production changes; run existing Android Actions workflow. Download/read reports and distinguish assertion failures from test compilation/setup failures.
3. Implement fixes only after their missing behavior has failed in cloud. Add private recovery storage/position APIs and a main-screen recovery flow with integration tests; test incremental additions in cloud.
4. Review assembled cancellation, checkpointing, partial failures, retention, language, and native/lifecycle behavior independently. Run complete JVM/lint + both Android runtime gates + final APK alignment/package gates.
5. Correct README and engineering record sections 7–14 and evidence provenance. Preserve historical build evidence while clearly marking superseded destructive/error/normalization claims. Update exact test inventory from new reports. Push to draft PR #133; no public release.

## File responsibilities

- `asr/Whisper.java`: partial progress, enhancement fallback, recovery/resume ownership, typed errors, first-language pinning.
- `asr/PcmRecording.java`: bounded positional reads/rewind and durable-spool handoff; never delete retained recovery audio on transient inference failure.
- New `asr/RecoveryStore.java`: durable private recording/checkpoint metadata, explicit removal, safe bounded text reads and atomic writes.
- `MainActivity.java`, main layout/strings: saved dictation listing/preview/copy/resume/discard with foreground-session handling.
- `Recorder.java`: initial-silence deadline, read-failure salvage, microphone cleanup and typed timeout.
- `RecordBuffer.java`, signal/window helpers: preserve amplitude, conservative quiet-window handling and bounded boundary context.
- `CloudClient.java`, `TranscriptJoiner.java`, `TextWindows.java`: language/punctuation/paragraph correctness.
- `WhisperRecognitionService.java`: no-match/initial-speech timeout mapping.
- `WhisperInputMethodService.java`: preserve work on selection updates while enforcing editor ownership.

## Verification ledger

Pending: red run, repair commits, final full cloud run, review findings, documentation corrections and exact artifact provenance will be recorded here.
