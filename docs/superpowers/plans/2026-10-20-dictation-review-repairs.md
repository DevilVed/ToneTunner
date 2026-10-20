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
- Result callbacks acknowledge acceptance: a rejected editor commit, stale destination, or disconnected recognition client keeps the completed saved dictation. This acknowledgement establishes framework/UI acceptance, not a guarantee that another application later persists it.

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

- Tests-only `b24594e`, [run 39454569990](https://github.com/DevilVed/ToneTunner/actions/runs/39454569990): stopped at test compilation because `Files.readString` is absent from the Android compile API. Replaced with `Files.readAllBytes` plus UTF-8 decoding. This is setup-error evidence, not a behavioral RED.
- Tests-only `def7cfa`, [run 39455119668](https://github.com/DevilVed/ToneTunner/actions/runs/39455119668): 145 tests in 23 suites, 23 failures, zero errors/skips. Twenty-two assertions reproduced the reviewed defects; the automatic-silence fixture failed during Robolectric instrumentation of the obfuscated VAD class, before reaching capture. Replaced that fixture with partial microphone reads and an explicit automatic-mode flag, keeping native VAD out of the test.
- Corrected tests-only `5815220`, [run 39456006799](https://github.com/DevilVed/ToneTunner/actions/runs/39456006799): 145 tests in 23 suites, 23 failures, zero errors/skips. Automatic silence now reaches the recorder and fails on “VAD mode must terminate a capture with no initial speech”; manual capture past the same deadline and empty-read controls pass. Late-read salvage also fails on the intended DONE-versus-ERROR assertion. This is the observed RED gate for the production repairs.
- Independent static integration review found further cleanup races: cancellation during processing and cancellation before queued success delivery could delete an unsaved spool; storage preparation failure could also delete it. The repair therefore archives every finished recording before processing, checkpoints each completed window, keeps retained audio through cancellation, and deletes only after delivery or explicit discard. A delivery claim blocks a second retry between worker completion and callback delivery. Primary-store failure gets a separate private fallback root; an emergency orphan file remains recoverable when metadata/storage setup is unavailable. These behaviors receive new pipeline/store/UI regressions.

- Repair `8aaa591`, [run 39458939215](https://github.com/DevilVed/ToneTunner/actions/runs/39458939215): 183 tests in 25 suites, five failures, zero errors/skips. One new negative case exposed a thrown `AudioRecord.read()` exception (negative return values already passed); the recorder now salvages that prefix too. Two multipart tests incorrectly assumed no part headers between Content-Disposition and data; their parser now handles OkHttp's Content-Length. Two recovery-dialog tests needed to drain AppCompat's posted button handlers under the paused test looper. These are identified fixture corrections, not relaxed behavioral assertions.
- Follow-up `b20fe1c`, [run 39460337903](https://github.com/DevilVed/ToneTunner/actions/runs/39460337903): 186 tests in 25 suites, three failures, zero errors/skips. All five previous cases pass. New assertions reproduce rejected result delivery incorrectly deleting a saved dictation, plus positive OpenAI and Groq translation calls throwing on a null language value. The corresponding repairs add explicit delivery acknowledgement across all four Android entry points and omit language with an empty normalized value for translation.

- Repair `5c125bc`, [run 39460901923](https://github.com/DevilVed/ToneTunner/actions/runs/39460901923): 193 tests in 26 suites, two failures, zero errors/skips. All three previously failing behavior checks and the five new real IME acknowledgement cases pass. The two new service-callback fixtures stopped before exercising delivery because reflection selected API 28's synthetic access constructor with an extra `RecognitionService$1` marker. The fixture now chooses the shortest actual constructor instead of relying on unspecified reflection order; production behavior is unchanged by this correction.

Pending: final complete cloud verification, updated exact test inventory, and final artifact provenance. Active capture cancelled before submission (including leaving the foreground) remains disposable; this work retains finished recordings once handed to processing and does not add background microphone recording.
