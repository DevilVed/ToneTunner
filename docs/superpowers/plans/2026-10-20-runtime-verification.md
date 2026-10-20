# Android runtime verification plan

> **For agentic workers:** Use Superpowers subagent-driven-development, tests first, and independent review. All compilation and executable tests run in GitHub Actions.

**Goal:** Verify the complete existing dictation workflow on Android, including modern native library compatibility and transitions between recording, processing, cancellation, and editors.

**Scope:** Continue the cloud-dictation specification. Preserve the existing app identity, GPL/NOTICE/credits, local Whisper option, and no-duration-limit recording requirement. This adds runtime evidence and repairs demonstrated defects; it does not redefine success as a passing helper suite.

## Work and ownership

- [ ] IME worker: add Robolectric tests for same-editor delivery, field changes, selection changes, and cancellation. Use the real service listener and InputConnection behavior. Report unreachable/adversarial cases separately from reachable defects. New test files only until the initial Actions result is inspected.
- [ ] Native compatibility worker: inspect the delivered APK and research primary upstream evidence for compatible ONNX, extensions, and VAD artifacts. Report exact failing shared libraries and minimal supported replacements. Read-only research.
- [ ] Primary: add Android instrumentation tests for production Keystore encryption/configuration, native CPU inference/extensions/VAD loading, and a manual AudioRecord capture beyond 30 seconds. Use emulator audio and synthetic data; never use developer credentials or paid APIs.
- [ ] Primary: make Actions run JVM tests/lint and Android API 28 plus API 35/16 KB emulator tests. Deliver an APK only after all gates pass. Upload all verification reports on failure.
- [ ] Primary: inspect initial failures, repair demonstrated problems, and rerun the complete verification suite. Validate packaged ELF/ZIP alignment rather than relying on version labels or lint suppression.
- [ ] Independent reviewer: review the final production changes, tests, and verification scope. Record actual results and remaining physical hardware/live-provider limits.

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
