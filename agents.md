# Mission: Universal Whisper Android Engine (ToneTunner Refactor)

## 1. Executive Summary
We are refactoring the `ToneTunner` Android app to replace its hardcoded, mobile-optimized Whisper Small architecture with a **Universal Engine** capable of running **Whisper Large v3 (1.5B params)**, FP16 variants, and future models.

**The constraint:** We cannot use the "Mobile Export" helper models (`Whisper_initializer.onnx`, `Whisper_cache_initializer.onnx`) because they are hardcoded for the Small architecture (80 mels, 12 layers) and incompatible with Large v3 (128 mels, 32 layers).

**The Solution:** We will delete these helper ONNX models and implement their logic **natively in Java**.

## 2. Technical Specifications & Constants

### A. Model Architectures (Dynamic Target)
The code must **not** hardcode these values. It must parse `config.json` to find them.

| Parameter | Key in `config.json` | Small (Current) | Large v3 (Target) |
| :--- | :--- | :--- | :--- |
| **Layers** | `num_hidden_layers` | 12 | **32** |
| **Heads** | `encoder_attention_heads` | 12 | **20** |
| **Mel Bins** | `num_mel_bins` | 80 | **128** |
| **Vocab Size**| `vocab_size` | 51865 | ~51866+ (V3) |

### B. Audio Signal Processing (The Math)
We must replicate OpenAI's `log_mel_spectrogram` function exactly in Java.
*   **Sample Rate:** 16,000 Hz
*   **Window Function:** Hann Window
*   **N_FFT:** 400
*   **Hop Length:** 160 (10ms)
*   **Filterbank:** Mel filters covering 0 Hz to 8000 Hz.
    *   **CRITICAL:** The filterbank matrix must be generated dynamically or loaded based on `num_mel_bins` (80 vs 128).
    *   **Normalization:** Slaney (normalize area of triangles).
*   **Log Compression:** `log10(x + 1e-10)` (Check OpenAI implementation for exact epsilon).
*   **Input Scaling:** Audio must be normalized to -1.0 to 1.0 range before processing.

## 3. Implementation Directives

### Step 1: `AudioFeatureExtractor.java` (New Class)
Replace `Whisper_initializer.onnx`.
*   **Function:** `extractFeatures(float[] pcmData)`
*   **Logic:**
    1.  Pad audio (if necessary) to match STFT centering.
    2.  Apply STFT (Java FFT library or custom implementation required).
    3.  Compute Magnitude Spectrogram (`|STFT|^2`).
    4.  Apply Mel Filterbank Matrix (Matrix Multiplication: `Mel_Matrix [n_mels x (n_fft/2 + 1)] @ Spectrogram`).
    5.  Apply Log10.
    6.  **Output:** Flattened `float[]` array representing tensor shape `[1, n_mels, 3000]`.

### Step 2: `Recognizer.java` (Logic Core)
*   **Remove Sessions:** `initSession`, `cacheInitSession`, `detokenizerSession`.
*   **Dynamic Initialization:**
    *   On startup, read `config.json`. Store `NUM_LAYERS`, `NUM_HEADS`, `NUM_MELS`.
*   **Manual Cache Generation:**
    *   Instead of `cacheInitSession.run()`, create input tensors in Java.
    *   **Shape:** `[batch_size, NUM_HEADS, 0, 64]`. (Note: Head dimension 64 is constant across Small/Large v1/v2, but check `d_model / n_head` to be safe).
    *   **Loop:** Iterate `NUM_LAYERS` times to create `past_key_values.i.decoder.key`, etc.
*   **Tokenization (Optional but Recommended):**
    *   If `Whisper_detokenizer.onnx` is incompatible, implement a basic BPE decoder in Java using `vocab.json` (Map<Int, String>).

### Step 3: `TensorUtils.java` (Precision)
*   **Float16 Support:**
    *   Add `createFloat16Tensor(OrtEnvironment env, float[] data, long[] shape)`.
    *   Logic: Convert `float` array to `ByteBuffer` containing `short` values (using IEEE 754 half-precision format).
    *   Use `Integer.floatToFloat16` (Available in Android API? Or usage of bit manipulation helper). *Note: `Float.floatToFloat16` is Android 13+ (API 33). If supporting older Android, need custom bit-shift util.*

## 4. The "Perfect" Prompt for Claude

**Role:** Expert Android/Java Engineer & DSP Mathematician.
**Task:** Refactor `ToneTunner` for Universal Whisper Support (Large v3, FP16).

**Input:**
*   Existing `Recognizer.java` (uses static ONNX helpers).
*   Goal: Run Whisper Large v3 (128 mels, 32 layers).

**Requirements:**

1.  **Code `AudioFeatureExtractor.java`:**
    *   Implement `extractFeatures(float[] audio, int n_mels)`.
    *   Include a compact, standalone **FFT** implementation (no heavy external deps if possible, or use JTransforms if standard).
    *   Include the **Mel Filterbank generation logic** (approximating `librosa.filters.mel(sr=16000, n_fft=400, n_mels=128)`). *Hardcoding the 128-mel matrix as a large static array is acceptable if generation is too complex in pure Java, but generation is preferred for universality.*

2.  **Refactor `Recognizer.java`:**
    *   Add `loadConfig(String path)` using `org.json`.
    *   Replace `cacheInitSession` with a loop:
        ```java
        for (int i = 0; i < NUM_LAYERS; i++) {
             // Create empty tensors [1, NUM_HEADS, 0, 64]
             inputs.put("past_key_values." + i + ".encoder.key", ...);
        }
        ```
    *   Ensure all tensor creation uses dimensions variables (`NUM_LAYERS`, `NUM_HEADS`) derived from config, NOT constants.

3.  **Update `TensorUtils.java`:**
    *   Add `floatToHalf` conversion for FP16 support.
    *   Ensure Little-Endian byte ordering for ONNX Runtime.

**Constraint:** The solution must work on Android API 28+ (Java 8+). Do not use APIs specific to Android 13 unless guarded.

**Deliverables:**
Complete source code for `AudioFeatureExtractor.java`, `Recognizer.java` (refactored), and `TensorUtils.java`.

***