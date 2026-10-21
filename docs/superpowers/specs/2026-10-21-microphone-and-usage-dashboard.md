# Microphone settings and usage dashboard: design

Date: 21 October 2026. Branch: `agents/mic-and-dashboard` (from `origin/beta-test-v1` at `791e861`).

## 1. Intent

DevilVed asked for two features:

1. **A microphone option in Settings.** By default ToneTunner must record from the phone's built-in microphone only, never from a Bluetooth headset or another microphone. Noise cancellation and microphone level should be pushed "as much as possible, to the maximum".
2. **A usage dashboard** with graphs for token counts, time spent, API prices, inference, cache hits and the other metrics a dictation app can measure.

The work must start from the reference macOS application in `inspiration folder/` (VoiceInk), take its algorithms, and adapt them to Android in Java (the language of the whole app).

### Why the built-in microphone keeps losing today

`Recorder.recordAudio()` calls `AudioManager.startBluetoothSco()` and `setBluetoothScoOn(true)` on **every** recording. Whenever a Bluetooth headset is connected, Android routes the capture through the headset's narrow-band SCO link. This is the behaviour the user wants removed by default.

## 2. What the reference application does (research)

### 2.1 Microphone routing (`Infrastructure/Audio/Devices/AudioDeviceManager*.swift`)

- Three input modes: `systemDefault`, `custom` (one saved device) and `prioritized` (an ordered list).
- `resolveCurrentRecordingDevice()`:
  1. Build the **preferred candidates** for the mode (system default; the saved device by UID/model UID; or the prioritized list sorted by priority, keeping only connected devices).
  2. Append the **fallback candidates**: every built-in (`kAudioDeviceTransportTypeBuiltIn`) microphone first, then all other inputs. When the laptop lid is closed the order flips, because the internal mic is muffled.
  3. Remove duplicates and the excluded (failed) device, and return the first usable one. It also reports whether it fell back.
- On a device disappearing mid-recording it switches to the next resolved device and notifies the user ("Using: <device>").
- It **does not modify the system default input** and does not apply gain or noise processing. Its `CoreAudioRecorder` only meters audio: per-buffer RMS and peak in dBFS, `20·log10(max(x, 1e-6))`.

### 2.2 Dashboard (`Features/Dashboard/**`, `Infrastructure/Persistence/Statistics/**`)

- One **`SessionMetric`** row per completed transcription: timestamp, word count, audio duration, transcription model and processing duration, **speed factor = audio duration / processing duration**, enhancement model and duration, and an **estimated** enhancement token count.
- Token estimate (`EstimatedTokenCounter`): `max(1, (characters + 3) / 4)` over the system prompt, user message and enhanced output. It never reads real provider usage and has no prices or cache metrics.
- Periods: Today, last 7 days, last 30 days, this year, all time. Weeks start on Monday.
- **Time saved** = `max(words / 40 wpm × 60 − audio duration, 0)` seconds.
- **Peak hours**: words and sessions per hour of day; the best **two-hour** window (wrapping at midnight) by words, tie-broken by sessions.
- Per-model cards: sessions, average processing time, average speed factor, total audio per transcription model, estimated tokens per enhancement model.
- Daily productivity (words per day) and an activity calendar.

## 3. Android adaptation

### 3.1 Microphone selection

| VoiceInk (Core Audio) | ToneTunner (Android) |
|---|---|
| `AudioDeviceID`, transport type | `AudioDeviceInfo` from `AudioManager.getDevices(GET_DEVICES_INPUTS)`; `getType()` |
| built-in = `kAudioDeviceTransportTypeBuiltIn` | `TYPE_BUILTIN_MIC` |
| Bluetooth headset | `TYPE_BLUETOOTH_SCO`, `TYPE_BLE_HEADSET` (API 31+) |
| wired/USB | `TYPE_WIRED_HEADSET`, `TYPE_USB_HEADSET`, `TYPE_USB_DEVICE`, `TYPE_USB_ACCESSORY` |
| record from chosen device | `AudioRecord.setPreferredDevice(device)` |
| no system default change | Bluetooth SCO is started **only** when the Bluetooth mode resolved to a Bluetooth device. On API 31+ `setCommunicationDevice()` / `clearCommunicationDevice()` replaces the deprecated `startBluetoothSco()`. |
| lid closed → external first | no Android equivalent; not ported |

Setting `micInput` (list, default **`builtin`**):

- **Phone microphone (default)**: preferred = built-in mics; Bluetooth SCO is never started.
- **Bluetooth headset**: preferred = Bluetooth inputs. If none is connected, fall back to the built-in mic.
- **Wired / USB headset**: preferred = wired/USB inputs, fallback built-in.
- **Automatic (Android decides)**: no preferred device, no SCO; Android's normal routing.

Resolution is the reference algorithm: `preferred + fallback(built-in first, then the rest)`, deduplicated, first match wins, and the result records whether it fell back. Among several built-in microphones (bottom/top/back), the one whose address is `bottom` (the voice mic on most phones) wins, otherwise the first listed. The decision is pure Java (`MicRouter.resolve`) so it can be unit tested without Android. After `startRecording()` the actually routed device (`getRoutedDevice()`) is recorded for the dashboard.

### 3.2 Noise reduction (setting `micNoiseReduction`, default **maximum**)

- **Off**: `VOICE_RECOGNITION` source, no effects (today's audio path, minus forced SCO).
- **Standard**: `VOICE_RECOGNITION` source + the platform `NoiseSuppressor` effect when `NoiseSuppressor.isAvailable()` + a 2nd-order 80 Hz Butterworth high-pass filter (removes handling noise, wind rumble and DC offset; speech energy starts ~100 Hz).
- **Maximum (default, as requested)**: `VOICE_COMMUNICATION` source, which turns on the phone's call-grade multi-microphone noise cancellation, plus `NoiseSuppressor` and the 80 Hz high-pass. This is the strongest noise cancellation an app can get on Android without root. *Trade-off, documented in the UI:* call processing can occasionally soften quiet consonants; if accuracy drops on a particular phone, choose Standard.

Neither choice changes the global audio mode, so other apps' audio is unaffected.

### 3.3 Microphone boost (setting `micBoost`, default **maximum**)

"Maximum power" cannot mean a fixed large gain: clipped audio destroys transcription accuracy. The boost is a software **automatic gain control with a peak limiter**, built on the reference's dBFS metering:

1. Per frame, measure RMS and peak (`20·log10`).
2. Track a noise floor (fast fall, slow rise ~3 dB/s).
3. Only frames at least 6 dB above the floor (speech-like) move the gain toward `target − rms`, clamped to `[0, maxGain]`. Gain falls fast (attack) and rises at most 40 dB/s, only on speech frames, so silence and background noise never pump the gain up while a quiet speaker still reaches the target within about 1.5 s.
4. The applied gain is capped so `peak × gain ≤ −1 dBFS`; changes are interpolated across the frame (no clicks), and a final hard clamp guarantees no 16-bit overflow.

| Boost | Target speech RMS | Max gain |
|---|---|---|
| Off | — | 0 dB |
| Medium | −24 dBFS | +12 dB |
| Maximum (default) | −18 dBFS | +30 dB |

The VAD (auto-stop) keeps reading the **raw** frame, so the auto-stop behaviour does not change. The processed frame is what is saved and transcribed.

### 3.4 Usage metrics

One row per dictation attempt in a private SQLite database (`usage.db`, excluded from backup like all app data). No transcript text is stored, only counts.

Recorded fields: timestamp, status (completed / failed), words, characters, audio ms, total processing ms, transcription ms, local model load ms, enhancement ms, request count, transcription provider + model, enhancement provider + model, billed audio seconds, transcription input/output/audio tokens, enhancement prompt/cached/completion/reasoning tokens, provider-reported cost (USD) when present, microphone type used, whether it fell back, average/peak input level, clipping ratio and average boost.

Where the numbers come from:

- **Real usage first.** `CloudClient` now parses the `usage` object of every successful response: chat `prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens`, OpenRouter `cost`; transcription `usage.input_tokens/output_tokens/input_token_details.audio_tokens` (gpt-4o transcribe models), `usage.seconds` (whisper-1) and `duration` (verbose JSON).
- **Estimate otherwise.** If a chat provider returns no usage, tokens fall back to the reference estimate `(chars + 3) / 4` and are flagged as estimated.
- **Cost**: the provider-reported cost when present (OpenRouter); otherwise usage × the price table. Groq transcription bills at least 10 s per request.
- **Failed dictations** are recorded too, so cloud requests that were paid for before a failure still count.

Built-in prices (USD; verified 21 Oct 2026 from Groq's model documentation and OpenAI's pricing page; users can edit them from the dashboard):

| Provider / model | Rates |
|---|---|
| Groq `whisper-large-v3-turbo` | $0.04 / audio hour (10 s minimum per request) |
| Groq `whisper-large-v3` | $0.111 / audio hour (10 s minimum) |
| Groq `openai/gpt-oss-20b` | $0.075 in, $0.0375 cached, $0.30 out per 1M tokens |
| Groq `openai/gpt-oss-120b` | $0.15 in, $0.075 cached, $0.60 out per 1M tokens |
| OpenAI `gpt-4o-mini-transcribe` | $1.25 in, $5.00 out per 1M tokens (≈ $0.003/min) |
| OpenAI `gpt-4o-transcribe` | $2.50 in, $10.00 out per 1M tokens (≈ $0.006/min) |
| OpenAI `whisper-1` | $0.006 / minute ($0.36 / hour) |
| OpenAI `gpt-4o-mini` | $0.15 in, $0.075 cached, $0.60 out |
| OpenAI `gpt-4.1-mini` | $0.40 in, $0.10 cached, $1.60 out |
| OpenAI `gpt-4.1-nano` | $0.10 in, $0.025 cached, $0.40 out |
| OpenAI `gpt-5-mini` | $0.25 in, $0.025 cached, $2.00 out |
| OpenAI `gpt-5-nano` | $0.05 in, $0.005 cached, $0.40 out |
| OpenRouter (any model) | the cost returned in each response |
| Local Whisper | $0 |

Groq's cached rate is its documented 50 % prompt-caching discount. A token-priced transcription model with no usage in the response is estimated at its per-minute rate.

### 3.5 Dashboard screen

Opened from **Settings → Usage dashboard**. Period chips: Today, 7 days, 30 days, This year, All time (the reference periods). Cards, top to bottom:

1. **Summary tiles**: dictations, words, audio recorded, time saved, API cost, tokens.
2. **Words**: bars per day (per hour for Today).
3. **API cost**: bars per day, total, cost per dictation, cost per audio hour.
4. **Tokens and cache**: stacked bars per day (cached input / uncached input / output), cache hit rate = cached ÷ prompt tokens.
5. **Speed and inference**: average processing time, speed factor (× real time), local model load time, cloud request latency, average words per minute spoken.
6. **Peak hours**: 24 hourly bars with the best two-hour window highlighted.
7. **Models**: per transcription and enhancement model: sessions, audio, tokens, cost, average latency.
8. **Microphone**: device used (phone / Bluetooth / wired / other), fallbacks, average speech level, peak level, clipping, average boost.
9. **Reliability**: completed vs failed dictations.
10. **Prices**: rates in use; tap a model to edit its rates; reset to built-in.
11. **Clear usage data** (with confirmation).

Charts are custom `View`s drawn on `Canvas` (no new dependency): one bar-chart view that supports stacked series and a highlighted range, with a content description summarising the values for screen readers.

## 4. Non-goals

- No per-device priority list UI like the reference's prioritized mode; four clear choices cover the request.
- No change to the global audio mode, no system-wide default device change.
- No transcript text in the metrics database.
- No live price download; prices are built in and editable.
- No mid-recording device switching UI: Android re-routes `AudioRecord` itself when the preferred device disconnects; the routed device is recorded.

## 5. Builder's calls (my decisions)

- Defaults: built-in mic, Maximum noise reduction, Maximum boost (the user's request); Off/Standard/Medium remain available.
- Medium/maximum boost targets and limits (table in 3.3).
- Failed dictations count toward cost and reliability, not toward words or time saved.
- Prices apply retroactively when edited (usage is stored raw; cost is computed when the dashboard loads), except provider-reported costs, which are stored as charged.
