ToneTunner is developed and maintained by [DevilVed](https://github.com/DevilVed).

ToneTunner is an open-source Android voice dictation app and voice keyboard. Use your own API keys for cloud transcription, optionally clean up the transcript with a separately selected enhancement model, or run the existing Whisper model locally.

**Verification status (21 October 2026):** The integrated cloud-dictation source at `3a04b5f` passed a complete GitHub Actions workflow ([run 39533439912](https://github.com/DevilVed/ToneTunner/actions/runs/39533439912)): 257 JVM tests in 32 suites with no failures, lint with zero errors, the native 16 KB alignment gate, API 28, API 34 and API 35 (16 KB page) emulator tests, and APK packaging. Physical-phone, live-provider and F-Droid checks have not been done yet. See the [engineering record](docs/2026-10-20-voiceink-android-engineering-record.md), [per-finding review response](docs/2026-10-21-detailed-review-response.md), [test inventory](docs/2026-10-20-verification-test-inventory.md), and [clean-clone build recipe and limits](docs/build-recipe.md). The old `bd0e80a` APK and its earlier test counts do not establish the repaired behavior.

## Configure your models

Open **AI settings** from the app or voice keyboard. The two sections are independent:

- **Transcription:** Groq, OpenAI, OpenRouter, an OpenAI-compatible HTTPS endpoint, or local Whisper. Cloud mode works without downloading ONNX models.
- **Enhancement:** optionally send the transcript to a separate Groq, OpenAI, OpenRouter, or compatible chat model. Choose a model, cleanup instructions, and vocabulary. Enhancement is off until enabled. Text pieces and provider responses are bounded; if a sufficiently long source piece comes back severely shortened, ToneTunner returns the complete raw transcript with a warning. This catches obvious truncation but cannot prove that a rewrite preserves meaning.

Each cloud stage has its own provider, model ID, full endpoint URL, and API key. Built-in providers supply defaults; supported providers can refresh their model catalogs. Custom endpoints require a manually entered model ID. For custom services, use the full HTTPS transcription or chat-completion URL, including its path.

Keys are entered on the Android device, encrypted using Android Keystore, and stored outside Android backup. A blank key field preserves the saved key for that exact provider, stage, and endpoint; **Remove saved key** deletes it. No shared developer key is bundled. API usage is charged by the selected provider, separately from this free application.

Saved-key validation and credential decryption run off the settings UI thread. The settings screen can show whether an encrypted key file exists, but that status check does not decrypt or prove that the key is valid. Unsaved settings edits survive Activity rotation in memory; an Android process kill can discard unsaved edits. Typed API keys are not copied into saved Activity state.

Select the cloud-processing disclosure and save before cloud dictation is available. OpenRouter's ZDR option defaults on separately for both stages. Direct providers' retention settings must be managed in their own accounts; ToneTunner does not claim to change them.

For local transcription, select **Local Whisper** and use **Install local model**. The existing RTranslator Whisper-Small ONNX set remains supported. Local transcription with cloud enhancement still sends the resulting text to the enhancement provider.

## Dictate across Android

Enable ToneTunner in Android's keyboard settings using **Enable voice keyboard** in the app, grant microphone permission, then select its voice keyboard in the intended text field. Tap the microphone to start and tap again to finish. Press-and-hold push-to-talk also works. You can switch back to your regular keyboard with the keyboard button.

Manual recording has **no 30-second cutoff** and stays active through thinking pauses. Audio is written to a private file with a fixed capture buffer. After you stop, it is transcribed sequentially in windows of at most 28 seconds and optionally enhanced in bounded text windows. Quiet boundaries are preferred; a hard cut carries 500 ms of audio context into the next window, with conservative text-overlap reconciliation. Audio staging does not grow with the complete recording; transcript text and model allocations still consume memory. Available storage, system microphone access, and provider quotas apply. The standalone recorder is manual. Automatic stop-on-silence is available in the voice keyboard and Android speech entry points. With keyboard automatic mode selected, focusing an eligible field can start capture without a new tap. **Automatic mode times out after 30 seconds without initial speech**; it has no fixed total-duration cap after speech begins. In noise, automatic recording may continue until manually stopped, subject to storage. The Android recognition service defaults to automatic mode; disable it in voice recognition settings to allow thinking pauses there too.

The display stays awake while foreground dictation records, transcribes, or enhances. Completion, errors, cancellation, or leaving the foreground release that policy. Leaving the app or hiding its voice keyboard cancels the active foreground operation. If a finished recording was already submitted, it was archived before processing and remains in Saved dictations for explicit retry or discard; live capture that has not been submitted is discarded when its foreground session ends.

The app also offers a standalone recorder, an Android `RecognitionService`, and a `RECOGNIZE_SPEECH` intent Activity. Integration with another keyboard's microphone depends on that keyboard; installing ToneTunner does not redirect every keyboard's voice service. A floating overlay is not included in this version.

Cancel stops pending work. A recording already submitted for processing remains in Saved dictations; cancelling live capture before submission discards that capture. Changing the active text field or keyboard invalidates pending insertion. Selection updates within the same editor do not destroy dictation. Dictation is blocked in password fields; private fields requesting no personalized learning allow only fully local processing. There is no automatic fallback to another cloud provider or weaker privacy policy.

Open **Saved dictations** in the main app to preview/copy completed text, retry remaining audio, or discard a saved item. The list shows newest-first entries, count, and total audio size. There is no automatic age- or size-based pruning, so retained recordings use storage until you act on them. A failed request preserves earlier completed windows and the full audio. Retry is explicit, uses the current AI settings, and resumes at the saved window; the failed request may already have been billed. If enhancement fails, ToneTunner returns the complete raw transcript with a warning. A microphone read interruption ends capture and attempts to transcribe the captured prefix with a warning. A storage write/flush failure salvages the physically written, PCM16-aligned prefix with a partial-capture warning; bytes rejected by a failing/full filesystem cannot be recovered.

Legacy Android language codes and ISO three-letter aliases are normalized; three-letter-only tags such as `yue` and `haw` are retained. Provider/model language support still varies. In Auto mode, the first usable reported language is pinned for later windows. Some APIs return text without language metadata; choose an explicit language to avoid independent detection on those models. The near-silence filter checks RMS in 20 ms PCM frames and keeps a whole inference window if any frame exceeds the fixed threshold; it does not average energy over the whole window, amplify quiet audio, or reject arbitrary noise. Very quiet speech may still be skipped and room noise may still be recognized. An explicit retry can submit a quiet recording because retry bypasses the first-pass filter.

Translation to English is available with supported transcription models. ToneTunner checks support before cloud consent/key validation and microphone capture in the app and voice keyboard. Groq Whisper Turbo, OpenRouter, custom cloud routes, and the default OpenAI mini model are not supported translation routes; use Groq `whisper-large-v3`, OpenAI `whisper-1`, or local Whisper instead. Unsupported combinations report an error rather than silently transcribing the wrong task. Android's standard `RECOGNIZE_SPEECH` Activity always transcribes to honor the caller's speech-recognition contract.

## Data handling

Cloud transcription sends recorded audio to the chosen provider. Enhancement sends the transcript, cleanup instructions, and vocabulary to its provider; it does not read or upload screen contents, surrounding app text, or the clipboard. Local Whisper with enhancement disabled processes speech on the phone.

Finished recordings are moved into private no-backup recovery storage before processing, and completed-window progress is checkpointed there. **Processing failure, interruption, or cancellation retains the audio and completed text until explicit retry/discard; successful result delivery deletes that saved item.** Both retained PCM and transcript metadata are plaintext inside app-private storage, not application-encrypted. Saved items consume storage until removed. If primary recovery storage is unavailable, a second private root and recognizable retained spool support later recovery; physical storage failure cannot guarantee persistence. Cancelling a live capture before submission deletes its temporary audio. Stale disposable spools are cleaned before a new capture; retained recovery audio is excluded from that sweep. API keys and dictated content are not written to diagnostic logs. Provider retention, usage metadata, account controls, connectivity, and charges remain governed by the provider. ZDR is a routing/retention policy, not a claim that cloud data never leaves the phone.

## Development and verification

The [full engineering record](docs/2026-10-20-voiceink-android-engineering-record.md) explains the architecture, alternatives, failure history, recovery behavior, and evidence limits. The [per-finding review response](docs/2026-10-21-detailed-review-response.md) records decisions for every DeepSeek review item. The [test inventory](docs/2026-10-20-verification-test-inventory.md) is the historical `bd0e80a` report inventory; later recovery and folder-review run results are described separately in the engineering record. The [clean-clone build recipe](docs/build-recipe.md) documents native preparation and the F-Droid/network-isolation limits.

Build on GitHub Actions; no local Android SDK is required. The current workflow is configured for API 28, API 34, and an API 35 16 KB emulator after JVM/lint checks, then gates APK packaging on successful verification. Reports are uploaded even if verification fails. The 11eda74 green run predates the later folder-review tests; the subsequent tests-first run was red, and the integrated fixes have not yet completed a full green workflow. Development uses Gradle 8.14.5 and JDK 17 in CI.

The [native dependency build](tools/native/README.md) rebuilds ONNX Runtime Extensions' JNI libraries from pinned upstream source with 16 KB linker alignment. Its published Android AAR still contains unaligned libraries. The build preserves the official Java classes, records provenance, and includes the upstream MIT license. Final APKs receive ELF and ZIP alignment checks. The ELF checker inspects 64-bit LOAD segments; ZIP alignment and ABI-presence checks cover packaged payload placement, not 32-bit ELF LOAD alignment or physical ARM execution. Caller-supplied source/AAR paths avoid the wrapper's Git/Maven fetches, but upstream CMake dependencies may still fetch unless separately prepared. A fully network-isolated build, `fdroid lint`, and an isolated F-Droid server build have not been verified, so F-Droid readiness is not established.

Tests exercise request construction, privacy filters, provider failures, cancellation, malformed/truncated results, credential configuration, editor eligibility, audio ownership, and long recording segmentation. The [long-recording design and reference implementations](docs/superpowers/specs/2026-10-20-cloud-dictation.md#long-recording-requirement-user-update) explain the file spool and bounded inference algorithm. Physical-device microphone/keyboard testing and real-provider transcription quality testing are separate from automated verification.

# License

ToneTunner is free software licensed under the GNU GPLv3 (see [LICENSE](LICENSE)).
Copyright © 2025-2026 DevilVed. See [NOTICE](NOTICE) for full copyright and attribution details.

## Credits

ToneTunner began as a fork of [whisperIMEplus](https://github.com/woheller69/whisperIMEplus) by woheller69 (GPLv3) and is now developed independently by DevilVed.

- whisperIMEplus is based on [whisperIME](https://github.com/woheller69/whisperIME) by woheller69, published under MIT license
- It uses code and the Whisper ONNX models from [RTranslator](https://github.com/niedev/RTranslator), published under Apache-2.0 license
- It uses code from [Whisper-Android project](https://github.com/vilassn/whisper_android), published under MIT license
- It uses [OpenAI Whisper](https://github.com/openai/whisper) published under MIT license. Details on Whisper are found [here](https://arxiv.org/abs/2212.04356).
- It uses [Android VAD](https://github.com/gkonovalov/android-vad), which is published under MIT license
- It uses [ONNX Runtime and ONNX Runtime Extensions](https://github.com/microsoft/onnxruntime-extensions), published under MIT license; the upstream Extensions license is also included in the APK's license assets
- It uses [Opencc4j](https://github.com/houbb/opencc4j), for Chinese conversions, published under Apache-2.0 license
- It uses [FreeDroidWarn](https://github.com/DevilVed/FreeDroidWarn), DevilVed's fork of woheller69's library, published under Apache-2.0 license
- The optional local Whisper model is downloaded from [HuggingFace](https://huggingface.co/DocWolle/whisperOnnx) and is published under MIT license
