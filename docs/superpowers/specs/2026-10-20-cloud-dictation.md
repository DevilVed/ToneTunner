# ToneTunner cloud dictation

The user has asked to build a VoiceInk-like Android app with user-supplied API keys, cloud transcription, and a separate enhancement model section. This iteration implements that flow across the existing voice keyboard, standalone recorder, and Android recognition entry points. It keeps the local Whisper engine as an optional transcription provider. A floating overlay is a later feature.

## Behavior

- New installations start with a cloud provider selection; cloud mode never requires ONNX downloads. Groq, OpenAI, OpenRouter, and HTTPS OpenAI-compatible endpoints are supported.
- A dedicated AI settings screen contains separate transcription and enhancement sections. Each has a provider, endpoint, model ID, masked API key, and model discovery where supported. Enhancement can be disabled independently, and includes editable cleanup instructions and vocabulary.
- API keys belong to the person using the Android app. No developer key is embedded, read from the development machine, or provisioned. Verification uses synthetic fixtures rather than paid inference.
- Keys are encrypted using Android Keystore and stored outside backup; they do not appear in ordinary preferences, logs, saved view state, screenshots, or error messages. Existing backup exclusions remain.
- Cloud audio is uploaded only after explicit recording or the user's existing opt-in automatic recording mode. Selecting cloud processing is accompanied by a clear disclosure. Surrounding app text, clipboard, and screen contents are not uploaded.
- OpenRouter ZDR filtering is enabled by default and applies independently to both requests. Unsupported or unavailable privacy guarantees produce an error, never a weaker automatic fallback. Direct providers' account policies are disclosed without claiming the app can enforce them.
- Processing states distinguish transcription and enhancement. Authentication, rate-limit, connection, unsupported translation, empty response, and truncated enhancement errors are recoverable and do not expose provider response bodies.
- Cancellation aborts network work and prevents late callbacks. Editor changes invalidate keyboard requests. Password fields reject dictation; no-personalized-learning fields do not upload to cloud. Each operation owns its temporary audio and deletes it on completion, failure, or cancellation.
- Preserve current language, Chinese conversion, append, translation (where supported), keyboard switching, and recognition intent/service behavior.
- Preserve DevilVed identity, applicationId org.DevilVed.whisperplus, Java package com.devilved.tonetunner, GPLv3/NOTICE, credits, Apache headers, and .jules files.

## Interfaces and ownership

`cloud.Provider`: LOCAL, GROQ, OPENAI, OPENROUTER, CUSTOM; provider endpoint and model defaults.

`cloud.ProviderConfig(Provider provider, String endpoint, String model, String apiKey, boolean requireZdr)`: immutable public fields with those names.

`cloud.CloudClient`: blocking `Transcript transcribe(byte[] pcm, ProviderConfig config, String language, boolean translate)`, `String enhance(String text, ProviderConfig config, String prompt, String vocabulary)`, `List<Model> models(ProviderConfig config, boolean transcription)`, and `void cancel()`. All network methods throw IOException with safe messages. Transcript has public final text/language; Model has public final id/name. One client per active operation; callbacks are controlled by the facade. The transport uses OkHttp with redirects and automatic retry disabled.

`cloud.AiSettings(Context)`: getters getTranscriptionConfig(), getEnhancementConfig(), isCloudTranscription(), isEnhancementEnabled(), getEnhancementPrompt(), getVocabulary(), getSummary(). Settings are snapshotted before an operation starts.

`asr.Whisper` remains the shared facade used by all entry points. It chooses local/cloud ASR, applies optional enhancement, cancels safely, and dispatches callbacks on the main thread. UI lifecycle guards protect result delivery.

## Verification

Unit tests cover WAV framing, request payloads/privacy filters, response errors and truncation, endpoint validation, cancellation/stale editor guards, and configuration. Android build and unit tests run in GitHub Actions because the development Mac has no Android SDK. Physical phone and live provider quality testing remain explicitly separate from automated verification.

## Long recording requirement (user update)

Remove the 30-second recording cap and countdown. Manual dictation continues until the user stops it, including thinking pauses. Automatic stop-on-silence remains an explicit optional mode. Audio is spooled to private temporary disk storage with a fixed capture buffer; it is never accumulated in a growing ByteArrayOutputStream. After stopping, process one pause-aligned window at a time (maximum 28 seconds for the current local model) and join results in order. Provider requests also stay bounded. Enhancement processes bounded text pieces. Cancellation/error closes resources and removes temporary audio. Storage exhaustion is an explicit error, not a silently truncated successful recording.

This is a batch dictation pipeline: recording stays responsive even when a local model would transcribe slower than speech; no inference queue can grow during capture. Audio RAM is O(window size), disk is O(recording duration), model memory is constant per sequential inference, and transcript text grows with dictated words.

Keep the display awake for the whole visible foreground dictation session, including transcription and enhancement after capture stops. Otherwise idle screen-off enters the existing Activity/IME cancellation paths and can discard a long request before processing finishes. Release the display policy on success, failure, cancellation, and foreground teardown; do not introduce background recording or wake locks.

Primary reference implementations inspected on 2026-10-20:

- [VoiceInk CoreAudioRecorder](https://github.com/Beingpax/VoiceInk/blob/main/VoiceInk/Infrastructure/Audio/CoreAudioRecorder.swift) uses preallocated input buffers and writes converted PCM to an audio file. Adapt the bounded-capture/file-spool principle, not Apple APIs.
- [VoiceInk's local Whisper adapter](https://github.com/Beingpax/VoiceInk/blob/main/VoiceInk/Infrastructure/Providers/Transcription/Whisper/WhisperTranscriptionService.swift) currently reads the entire file into memory. That part is not suitable for this requirement and is deliberately not reproduced.
- [whisper.cpp stream example](https://github.com/ggml-org/whisper.cpp/blob/master/examples/stream/stream.cpp) bounds inference windows and retains a limited audio context. Its example can drop audio when inference falls behind; this app instead spools the full recording and decodes sequentially after stop.
- [faster-whisper VAD](https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/vad.py) limits speech segments and seeks silence boundaries for long input. Our energy-based boundary choice retains all input frames rather than filtering speech or treating a pause as stop.

The local ONNX recognizer and its token/cache tensors still handle only one bounded window at a time. No model changes or removal of native-resource closure are needed.
