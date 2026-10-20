ToneTunner is developed and maintained by [DevilVed](https://github.com/DevilVed).

ToneTunner is an open-source Android voice dictation app and voice keyboard. Use your own API keys for cloud transcription, optionally clean up the transcript with a separately selected enhancement model, or run the existing Whisper model locally.

## Configure your models

Open **AI settings** from the app or voice keyboard. The two sections are independent:

- **Transcription:** Groq, OpenAI, OpenRouter, an OpenAI-compatible HTTPS endpoint, or local Whisper. Cloud mode works without downloading ONNX models.
- **Enhancement:** optionally send the transcript to a separate Groq, OpenAI, OpenRouter, or compatible chat model. Choose a model, cleanup instructions, and vocabulary. Enhancement is off until enabled.

Each cloud stage has its own provider, model ID, full endpoint URL, and API key. Built-in providers supply defaults; supported providers can refresh their model catalogs. Custom endpoints require a manually entered model ID. For custom services, use the full HTTPS transcription or chat-completion URL, including its path.

Keys are entered on the Android device, encrypted using Android Keystore, and stored outside Android backup. A blank key field preserves the saved key for that exact provider, stage, and endpoint; **Remove saved key** deletes it. No shared developer key is bundled. API usage is charged by the selected provider, separately from this free application.

Select the cloud-processing disclosure and save before cloud dictation is available. OpenRouter's ZDR option defaults on separately for both stages. Direct providers' retention settings must be managed in their own accounts; ToneTunner does not claim to change them.

For local transcription, select **Local Whisper** and use **Install local model**. The existing RTranslator Whisper-Small ONNX set remains supported. Local transcription with cloud enhancement still sends the resulting text to the enhancement provider.

## Dictate across Android

Enable ToneTunner in Android's keyboard settings using **Enable voice keyboard** in the app, grant microphone permission, then select its voice keyboard in the intended text field. Tap the microphone to start and tap again to finish. Press-and-hold push-to-talk also works. You can switch back to your regular keyboard with the keyboard button.

Manual recording has **no 30-second cutoff** and stays active through thinking pauses. Audio is written to a private temporary file with a fixed capture buffer. After you stop, it is read in pause-aligned windows of at most 28 seconds, transcribed sequentially, and optionally enhanced in bounded text windows. Local model memory does not grow with the complete audio recording. Available storage, system microphone access, and provider quotas still apply. Automatic stop-on-silence is optional in the app and voice keyboard. The Android recognition service preserves silence-stop by default for calling apps; disable it in voice recognition settings to allow thinking pauses there too.

The app also offers a standalone recorder, an Android `RecognitionService`, and a `RECOGNIZE_SPEECH` intent Activity. Integration with another keyboard's microphone depends on that keyboard; installing ToneTunner does not redirect every keyboard's voice service. A floating overlay is not included in this version.

Cancel stops pending work. Changing the active text field or keyboard invalidates the pending insertion. Dictation is blocked in password fields; private fields requesting no personalized learning allow only fully local processing. There is no automatic fallback to another cloud provider or weaker privacy policy.

Translation to English is available with supported transcription models. Groq Whisper Turbo and OpenRouter's transcription path do not offer this translation endpoint; select a supported direct model or local Whisper instead. Unsupported combinations report an error rather than silently transcribing the wrong task.

## Data handling

Cloud transcription sends recorded audio to the chosen provider. Enhancement sends the transcript, cleanup instructions, and vocabulary to its provider; it does not read or upload screen contents, surrounding app text, or the clipboard. Local Whisper with enhancement disabled processes speech on the phone.

Temporary audio is deleted on completion, cancellation, or failure. If Android terminates the process, leftover audio files are cleaned before the next recording starts. API keys and dictated content are not written to diagnostic logs. Provider retention, usage metadata, account controls, connectivity, and charges remain governed by the provider. ZDR is a routing/retention policy, not a claim that cloud data never leaves the phone.

## Development and verification

Build on GitHub Actions; no local Android SDK is required. The Android build workflow runs `testDebugUnitTest` and `lintDebug` before assembling the APK, and uploads test/lint reports even if verification fails. Debug APK artifacts are available on a successful workflow run. Development uses Gradle 8.14.5 and JDK 17 in CI.

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
- It uses [Opencc4j](https://github.com/houbb/opencc4j), for Chinese conversions, published under Apache-2.0 license
- It uses [FreeDroidWarn](https://github.com/DevilVed/FreeDroidWarn), DevilVed's fork of woheller69's library, published under Apache-2.0 license
- At first start you need to download the Whisper model from [HuggingFace](https://huggingface.co/DocWolle/whisperOnnx), which is published under MIT license
