# Android runtime verification plan

> **For agentic workers:** Use Superpowers subagent-driven-development, tests first, and independent review. All compilation and executable tests run in GitHub Actions.

**Goal:** Verify the complete existing dictation workflow on Android, including modern native library compatibility and transitions between recording, processing, cancellation, and editors.

**Scope:** Continue the cloud-dictation specification. Preserve the existing app identity, GPL/NOTICE/credits, local Whisper option, and no-duration-limit recording requirement. This adds runtime evidence and repairs demonstrated defects; it does not redefine success as a passing helper suite.

## Work and ownership

- [x] IME worker: add Robolectric tests for same-editor delivery, field changes, selection changes, and cancellation. Use the real service listener and InputConnection behavior. Report unreachable/adversarial cases separately from reachable defects. New test files only until the initial Actions result is inspected.
- [x] Native compatibility worker: inspect the delivered APK and research primary upstream evidence for compatible ONNX, extensions, and VAD artifacts. Report exact failing shared libraries and minimal supported replacements. Read-only research.
- [x] Primary: add Android instrumentation tests for production Keystore encryption/configuration, native CPU inference/extensions/VAD loading, and a manual AudioRecord capture beyond 30 seconds. Use emulator audio and synthetic data; never use developer credentials or paid APIs.
- [x] Primary: make Actions run JVM tests/lint and Android API 28 plus API 35/16 KB emulator tests. Deliver an APK only after all gates pass. Upload all verification reports on failure.
- [ ] Primary: inspect initial failures, repair demonstrated problems, and rerun the complete verification suite. Validate packaged ELF/ZIP alignment rather than relying on version labels or lint suppression.
- [ ] Independent reviewer: review the final production changes, tests, and verification scope. Record actual results and remaining physical hardware/live-provider limits.
- [ ] Foreground dictation: verify and repair display timeout behavior. Activities must keep the display awake during capture, transcription, and enhancement, and release that policy on success/cancel/error/backgrounding. The visible voice keyboard needs the corresponding view policy; do not add background recording permissions.

## Acceptance evidence

- A successful full JVM suite with explicit test counts and zero failures/errors/skips.
- Connected Android tests on the minimum supported API and a modern 16 KB environment; runtime page size recorded and asserted.
- Production Android Keystore ciphertext survives reopening and is bound to provider/stage/endpoint.
- Native ONNX CPU inference, extensions loading, and VAD run without a linker error in the modern environment.
- Manual recording remains active for more than 30 seconds, creates a multi-window spool, stops explicitly, and deletes owned temporary audio.
- Current editor accepts its result; finishing/changing/cancelling an editor cannot cause stale insertion.
- APK native alignment validation passes, followed by successful artifact assembly/upload from the verified branch.

## Limits

Emulator fixtures do not prove Bluetooth hardware routing, OEM keyboard behavior, real Whisper weight quality/performance, or a cloud provider's live retention policy. Keep those distinctions explicit in the result; do not claim physical-device verification from emulator tests.

## Evidence and decisions

- Initial regression run [39431452697](https://github.com/DevilVed/ToneTunner/actions/runs/39431452697), commit `49e4fc1`: 105 JVM tests ran, with exactly three expected failures (cancelled IME listener delivery, selection-change cancellation, and recognition Activity launch mode). Lint also rejected nine native alignment errors. No APK was delivered.
- AOSP confirms `singleInstance` forces a new task and cancels the caller's activity-result route. The intended fix is standard per-request Activity instances, with a real Android caller-result instrumentation test.
- The IME callback now also checks its owning Whisper instance. Cancellation invalidates that owner even if a callback retained the previous listener. The lower-level operation gate continues protecting normal asynchronous delivery.
- Published AAR bytes were inspected, not just version labels: ONNX Runtime Android 1.27.0 has 16 KB-aligned arm64 libraries; Extensions Android 0.13.0 still has 4 KB-aligned libraries and remains Maven Central's latest published Android artifact. WebRTC 2.0.10's upstream release documents the alignment fix.
- Build Extensions' JNI libraries from the pinned upstream 0.13.0 source with explicit 16 KB shared/module linker flags and NDK 27. Preserve the checksum-verified official Java/resources package and its API, add MIT/provenance, and reject mismatched package layouts. Share the rebuilt AAR across all verification/package jobs. No unaligned fallback or lint suppression.
- Native build run `39433594177` reached C++ compilation and demonstrated Dlib's dependency on the generic `std::char_traits<unsigned int>` template removed by NDK 28/LLVM 19. Pin NDK 27.2, enable flexible page sizes and static C++ linkage, and retain the full artifact/runtime gates. The six native-validator tests passed in CI.
- These repairs are awaiting the next complete Actions run and independent patch review; this document does not claim runtime acceptance yet.
- Independent static review found no blocker in the native AAR build/package paths or real Activity-result instrumentation boundary. Native source compilation and runtime acceptance remain unproven until Actions completes.
- A worker added `RecordingScreenAwakeTest` for actual Activity window flags across existing capture/stop/cancel/pause/error callbacks. It is queued for its initial red CI run after the live native build completes, so an in-flight rebuild is not needlessly cancelled.
- `WhisperInputMethodServiceScreenAwakeTest` enters the real record-button path with local processing selected and a recorder shadow that suppresses microphone/thread effects. It proves the service accepts capture before asserting the view policy, and drives the installed listener plus cancel/hide callbacks. These tests also await their initial red Actions run; no display-policy production change has been made yet.
- Run [39434614698](https://github.com/DevilVed/ToneTunner/actions/runs/39434614698), commit `b04b0b2`: the native build completed for all four ABIs and the 64-bit artifact alignment check passed. Downloaded AAR SHA-256: `261b08d3da0a432ec40a2beffba79bcc1d5aa2168c123faba320decc79797587`. The full JVM suite passed: **105 tests in 19 suites, zero failures/errors/skips**; lint reported **zero errors and 38 warnings**. Downloaded Android reports show **six tests passing on each API 28 and API 35/16 KB emulator**, with zero failures/errors/skips. Display-policy regression tests are the remaining known work and have not been run yet.
- Display-policy red run [39439137335](https://github.com/DevilVed/ToneTunner/actions/runs/39439137335), commit `db9e413`: **113 tests, six expected assertion failures, zero errors/skips**. Both Activity capture assertions failed, and all four IME tests accepted a recording request before failing on the missing awake view policy. All remaining tests passed. No production display-policy change preceded this run.
- Run [39439954232](https://github.com/DevilVed/ToneTunner/actions/runs/39439954232), commit `2fe45ed`: the capture-policy fix passed **113 JVM tests in 21 suites, zero failures/errors/skips** and lint with **zero errors, 38 warnings**. Independent review accepted the capture transitions, then confirmed another concrete boundary: long sequential processing can outlast idle-screen timeout, which leads to `onPause` cancellation and loss of the pending request. Extend the awake policy through foreground transcription/enhancement, with terminal-state regression tests first. No background execution is introduced.
- Processing-policy red run [39441333114](https://github.com/DevilVed/ToneTunner/actions/runs/39441333114), commit `b62a4e1`: **121 tests, 13 expected assertion failures, zero errors/skips**. Eight Activity cases and five IME cases reproduced the missing awake policy while transcription/enhancement was pending. Other tests passed. These tests hold inference pending at the `Whisper.start` boundary while driving the real installed UI listeners, and assert terminal cleanup after success/error/cancel/pause/hide. Android documents [screen-off as an Activity lifecycle transition](https://developer.android.com/guide/components/activities/activity-lifecycle) and the [foreground screen-on policy](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).
