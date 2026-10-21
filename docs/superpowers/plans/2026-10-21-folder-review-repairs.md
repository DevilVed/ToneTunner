# Follow-up repairs from the detailed folder review

The user asked to address `review/deepseek_cr.md`, reviewed at `37744fa`. This is a continuation of PR #133, not a release or a replacement of the earlier recovery contracts. Build and executable verification stay entirely in GitHub Actions. Preserve the existing `/review/` ignore rule, local review file, `.DS_Store` change, identity, licensing, and `.jules` history.

## Verified requirements and decisions

- Keep failed recordings until explicit retry/discard or accepted delivery. The review's automatic pruning, no-match deletion, or storage quotas must not erase accepted recordings; the user expressly selected retention. Add chronological ordering and visible storage use instead.
- Keep manual recording untimed. Automatic mode retains its initial-speech timeout and post-speech VAD; document noisy-room and auto-start behavior. Do not silently reintroduce a total recording cap.
- Replace whole-window silence averaging with a bounded short-frame floor so brief soft speech inside a long pause is not diluted out. Keep absolute PCM amplitude and quiet-noise regression controls.
- Add bounded code-point overlap handling for unspaced scripts. Keep the conservative Latin path and document ambiguity/repetition limits rather than claim word alignment.
- A local `[(und)]` decoder sentinel fails its window and keeps the audio/checkpoint; it is not dictated text.
- Catch destination RuntimeExceptions at delivery, release the claim, keep the completed item, and use only safe diagnostics.
- Move archive/settings/Keystore preparation off the main thread, without allowing cancellation or executor shutdown to drop an accepted, not-yet-archived recording. Captured input must be owned by queued archival until it is durable.
- Preserve a physically written, PCM-aligned prefix after storage write/flush failure. Never replay a buffered partial flush during cleanup. Publish an explicit partial-capture warning, rather than claim the intended whole recording succeeded.
- Add safe request-configuration errors, a separate bounded model-catalog response limit, conservative severe-shortening detection for enhancement, and translation preflight before recording.
- Survive an IME restart only for the same editor/connection/privacy identity. Signal service readiness after the microphone starts, and signal speech-start only when detected.
- Review the clean-clone/native preparation and release ABI path. Preserve a source-based reproducible native build; do not commit a binary AAR or claim F-Droid approval. Public distribution/version bump/signing remains separate.

## Work and evidence order

1. Independently inspect the review's claims. Write targeted regression tests for valid runtime issues before changing production behavior.
2. Run those tests in Actions, separating behavioral failures from fixture/setup failures. Preserve the green checkpoint of the earlier repair where available.
3. Implement in scoped file groups: audio/segmentation, cloud transport, Android entry points/settings, native build/release preparation, and the root-owned shared facade/recovery metadata.
4. Review the assembled ownership/cancellation paths and every review ID, including recommendations intentionally rejected because they conflict with the user's choices or existing evidence.
5. Run JVM/lint, native validators, API 28/4 KB, API 34/4 KB, and API 35/16 KB connected tests, final debug and unsigned release assembly/alignment in Actions. Generate exact inventory from downloaded reports.
6. Update README, engineering record, a per-finding review response, and PR #133 with the actual final commit, run, artifact, and remaining physical-device/F-Droid acceptance limits.

## Initial triage exceptions

- C1 correctly identifies an absent ready-to-run F-Droid recipe, but the stronger claim that the native artifact cannot be built from public source is inaccurate: the repository already contains the pinned public-source rebuild and Linux instructions. Improve the portable build path and state the remaining server acceptance honestly.
- C3's arm64-only release configuration predates this migration. Broaden the experimental release configuration and verify its package rather than describe it as a newly introduced loss of support.
- I4's automatic deletion conflicts with the selected retention behavior and can erase false-negative speech. Keep no-match audio recoverable.
- I12's proposed total-duration cap conflicts with the accepted untimed behavior. Document VAD's limits and provide visible stopping/cancellation controls.
- M5's `*` branch is reachable at a split Markdown opening marker; add a regression rather than remove working behavior.
- M6 is a bounded metadata/performance tradeoff; avoid introducing an unverified journal solely to eliminate theoretical quadratic text I/O. Record the real cap/limits and keep source audio on checkpoint failure.
- M7's suggested non-atomic checkpoint replacement weakens recovery guarantees. Use Android AtomicFile with a shared metadata lock, fsync, bounded readback, and backup recovery; do not replace atomic writes with destructive overwrite.
- M10 is an Android speech-intent transcription contract, not automatically the standalone app's translation preference. Document the distinction.
- M12 misreads the earlier record: the pre-existing tracked `.DS_Store` modification was excluded from these commits, not removed from baseline tracking. Keep unrelated local changes untouched.
- The orphan listener-method test was deliberate tests-first compatibility and failed on the missing behavior in the recorded RED run. Add final `@Override` annotations and exercise the production acknowledgement in instrumentation; do not describe that observed failure as a passing test.

## Evidence ledger

- First-review checkpoint: [39524551595](https://github.com/DevilVed/ToneTunner/actions/runs/39524551595), `11eda74`: complete success, 193 JVM tests in 26 suites, zero failures/errors/skips, lint zero errors/39 warnings, native gate and both Android runtime jobs passed, verified APK packaged. This is deliberately preserved as a checkpoint rather than substituted for the added folder-review coverage.
- Tests-only `554b1ee`, then static fixture corrections `01bd79c`: [39525899701](https://github.com/DevilVed/ToneTunner/actions/runs/39525899701), 226 tests in 29 suites, 26 failures, zero errors/skips. The failures reproduce UI-thread credential/archive work, short-speech dilution, local sentinel delivery, throwing destination, physical spool deletion on write/flush failure, random recovery ordering, CJK/Thai overlap, fullwidth boundaries, unsupported translation preflight, service readiness/speech timing, IME restart cancellation, stale foreground status, manifest/launch flags, catalog response limits, enhancement severe shortening, actionable HTTP errors, and translation-capability visibility. Controls for private/replacement editors, local translation, exact response bounds, cancellation retention, and split Markdown behavior pass. The named public-method reflection failure is an intentional API-contract RED; the others reach their behavior assertions.

Pending: integrated production repairs, final full cloud verification, exact test inventory, and per-finding disposition. The new API 35 bound-service test is gated behind JVM success and has not yet executed in this red run.

Update, 21 October: the production repairs, [per-finding disposition](../../2026-10-21-detailed-review-response.md) and exact [test inventory](../../2026-10-20-verification-test-inventory.md) are complete. [Run 39533439912](https://github.com/DevilVed/ToneTunner/actions/runs/39533439912) on `3a04b5f` passed all jobs, including the API 35 bound-service test (257 JVM tests, 0 failures).

- Integrated repairs `85e9ba1`: run [39528190498](https://github.com/DevilVed/ToneTunner/actions/runs/39528190498) stopped at the native gate: 12 checks, one fixture failure. The positive archive contained all ABIs but the negative assertion expected a missing-ABI error from that same archive. `943e0e6` constructs a separate archive missing x86 and requires all four ABIs. No Android test/build ran in the failed workflow.
