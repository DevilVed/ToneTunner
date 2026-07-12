# ToneTunner App Summary (Repo-Based)

## Scope and Method
This summary is based only on repository evidence in the current codebase. Where key operational details are not present in the repo, they are explicitly marked as **Not found in repo**.

## What it is
ToneTunner is an Android voice input app that uses local Whisper ONNX inference for speech transcription and optional translation to English. It operates as a standalone app, an Input Method Editor (IME), and an Android `RecognitionService` endpoint.

## Who it is for
Primary persona: Android users who want offline speech-to-text and voice input integration (keyboard IME and system voice input) with multilingual support and optional translation.

## What it does
- Records microphone audio at 16 kHz mono PCM via `AudioRecord` with optional WebRTC VAD-based auto-stop.
- Runs on-device Whisper inference through ONNX Runtime sessions (`initializer`, `encoder`, `cache initializer`, `decoder`, `detokenizer`).
- Provides a standalone transcription UI (`MainActivity`) with language selection, copy-to-clipboard, and optional text-to-speech.
- Provides IME voice typing (`WhisperInputMethodService`) that commits recognized text directly to the active input connection.
- Exposes Android speech intent support through `WhisperRecognizeActivity` (`android.speech.action.RECOGNIZE_SPEECH`).
- Exposes system voice input through `WhisperRecognitionService` (`android.speech.RecognitionService`) and dedicated settings UI.
- Supports long audio handling by chunking into 30-second windows with overlap and merged text output.

## How it works (compact architecture)
### Top-level components
- UI layer:
- `MainActivity` (standalone app mode)
- `SetupActivity` (model download link + ZIP install)
- `WhisperRecognizeActivity` (speech intent popup)
- `WhisperRecognitionServiceSettingsActivity` (service settings)
- Input/service layer:
- `WhisperInputMethodService` (IME)
- `WhisperRecognitionService` (system speech service)
- ASR orchestration:
- `Recorder` (audio capture + optional VAD)
- `RecordBuffer` (raw byte buffer + float conversion)
- `Whisper` (threaded orchestration, chunking, listener callbacks)
- Inference engine:
- `Recognizer` (ONNX session setup and iterative decode loop)
- `TensorUtils` (tensor conversion, including FP16 helper)
- Emerging universal-model support classes:
- `ModelManager`, `ModelConfig`, `AudioFeatureExtractor`, `BPETokenizer`

### Data flow
1. UI/service triggers recording (`Recorder.start()`).
2. `Recorder` captures PCM16 audio, optionally uses VAD to stop automatically, and stores bytes in `RecordBuffer`.
3. `Whisper.start()` signals a worker loop; it reads normalized float samples from `RecordBuffer`.
4. If audio exceeds the 30-second Whisper window, `AudioChunker` splits it and `ChunkMerger` merges chunk outputs.
5. `Whisper` calls `Recognizer.recognize(...)` with language and action (`TRANSCRIBE` or `TRANSLATE`).
6. `Recognizer` executes ONNX sessions in sequence: initializer -> encoder -> cache init -> iterative decoder with KV cache -> detokenizer.
7. Result text and language are returned via callbacks to the caller (`MainActivity`, IME, or RecognitionService), which then displays text, commits to input, or returns Android recognition results.

### Current engine status in repo
- Dynamic/universal model classes exist (`ModelManager`, `ModelConfig`, native `AudioFeatureExtractor`, `BPETokenizer`, FP16 tensor helper).
- Runtime path currently used by `Whisper.loadModel()` still constructs the legacy `Recognizer(Context, boolean, InitListener)` and relies on helper ONNX files (`Whisper_initializer.onnx`, `Whisper_cache_initializer.onnx`, `Whisper_detokenizer.onnx`).
- Model selection/preferences integration is marked TODO in `ModelManager`.

## How to run (minimal getting started)
1. Build/install the Android app from this Gradle project (Android app module `:app`).
2. Launch the app once; if model files are missing, setup flow opens automatically.
3. In setup, use "Download Model" (opens Hugging Face link) and "Install model" to import a ZIP into app external files.
4. Return to main screen and hold the record button to transcribe, or enable ToneTunner as IME/system voice input in Android settings.

## Operational notes and constraints
- `minSdk` is 34 and `targetSdk` is 35 in `app/build.gradle`.
- App uses ONNX Runtime Android (`1.19.0`) and ONNX Runtime Extensions.
- App includes JTransforms dependency for native Java FFT path.
- Recording permission (`RECORD_AUDIO`) is required in all speech entry points.

## Not found in repo
- End-user production install guide beyond in-app setup (store install steps are not documented as a step-by-step runbook).
- Automated test suites for ASR/inference correctness (no explicit unit/instrumentation test coverage found in repo files listed).
- Backend or cloud service dependencies for inference (no server component found; inference appears local).
- Benchmarks for latency, WER, or device compatibility matrix.

## Evidence Map (key files)
- App overview and usage claims: `README.md`
- Android app config/dependencies: `app/build.gradle`, `settings.gradle`
- App components and exported entry points: `app/src/main/AndroidManifest.xml`
- Setup/install workflow: `app/src/main/java/com/whisperonnx/SetupActivity.java`
- Standalone mode workflow: `app/src/main/java/com/whisperonnx/MainActivity.java`
- IME workflow: `app/src/main/java/com/whisperonnx/WhisperInputMethodService.java`
- Recognition service workflow: `app/src/main/java/com/whisperonnx/WhisperRecognitionService.java`
- Speech intent activity: `app/src/main/java/com/whisperonnx/WhisperRecognizeActivity.java`
- Audio capture and buffering: `app/src/main/java/com/whisperonnx/asr/Recorder.java`, `app/src/main/java/com/whisperonnx/asr/RecordBuffer.java`
- ASR orchestration + chunking: `app/src/main/java/com/whisperonnx/asr/Whisper.java`, `app/src/main/java/com/whisperonnx/asr/AudioChunker.java`, `app/src/main/java/com/whisperonnx/asr/ChunkMerger.java`
- ONNX inference/decode path: `app/src/main/java/com/whisperonnx/voice_translation/neural_networks/voice/Recognizer.java`
- Universal-engine support classes present: `app/src/main/java/com/whisperonnx/asr/ModelManager.java`, `app/src/main/java/com/whisperonnx/asr/ModelConfig.java`, `app/src/main/java/com/whisperonnx/asr/AudioFeatureExtractor.java`, `app/src/main/java/com/whisperonnx/asr/BPETokenizer.java`, `app/src/main/java/com/whisperonnx/voice_translation/neural_networks/voice/TensorUtils.java`
