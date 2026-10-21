# Microphone settings and usage dashboard: engineering record

Date: 21 October 2026. Branch `agents/mic-and-dashboard`, created from `origin/beta-test-v1` at `791e861` in its own worktree. Design: [specification](superpowers/specs/2026-10-21-microphone-and-usage-dashboard.md).

This record covers what was asked for, what the reference application ([VoiceInk](https://github.com/Beingpax/VoiceInk), checked out in `inspiration folder/`) does, how that was adapted to Android, what was tested, and what is still unverified.

## 1. Request

1. A microphone option in Settings. By default only the phone's built-in microphone is used, never Bluetooth or another microphone. Noise cancellation and microphone level should be as strong as possible.
2. A dashboard with graphs for token counts, time spent, all metrics, API prices, inference and cache hits.
3. Process: research the reference repository's algorithm first, reason about Android differences, implement in Java/Kotlin, test edge cases, document, then open a pull request.

The app is entirely Java, so the work is in Java.

## 2. Root cause of the Bluetooth behaviour

At `791e861`, `Recorder.recordAudio()` called `AudioManager.startBluetoothSco()` and `setBluetoothScoOn(true)` before every recording, whatever the user wanted. With a headset connected, Android then captured through the headset's narrow-band SCO link. No setting could avoid this.

## 3. What the reference application does

### 3.1 Microphone routing

Source: `VoiceInk/Infrastructure/Audio/Devices/AudioDeviceManager.swift` and `AudioDeviceManager+RecordingRouting.swift`.

- Input modes: system default, one custom device, or a prioritized list.
- `resolveCurrentRecordingDevice()` builds the mode's preferred candidates. It appends a fallback list with every built-in microphone (`kAudioDeviceTransportTypeBuiltIn`) first, then the other inputs. It deduplicates the list, drops an excluded device, and returns the first usable device together with a "fell back" flag. With the laptop lid closed, the fallback order flips.
- It never changes the macOS default input. When the active device disappears mid-recording, it switches device and notifies the user.
- `CoreAudioRecorder.swift` applies **no** gain or noise processing. It only meters: RMS and peak per buffer, `20·log10(max(x, 1e-6))` dBFS.

### 3.2 Dashboard

Source: `VoiceInk/Features/Dashboard/**` and `VoiceInk/Infrastructure/Persistence/Statistics/**`.

- `SessionMetric`: one row per completed transcription with words, audio duration, model, processing duration, speed factor (audio ÷ processing), enhancement model and duration, and an estimated token count.
- `EstimatedTokenCounter`: `max(1, (characters + 3) / 4)`. It never reads provider usage, and it has no prices or cache metrics.
- Periods: today, last 7 days, last 30 days, this year, all time.
- Time saved: `max(words / 40 wpm − audio duration, 0)`.
- Peak hours: the best two consecutive hours by words, wrapping at midnight, tie-broken by sessions.

## 4. Android design and the reasons for it

### 4.1 Device choice (`asr/MicRouter`, `asr/MicDevices`)

The reference resolution was ported as a pure function: preferred inputs of the selected kind, then built-in, then other inputs. It runs over `AudioManager.getDevices(GET_DEVICES_INPUTS)`, and the chosen device is applied with `AudioRecord.setPreferredDevice()`. There are two Android-specific choices:

- **Bluetooth is never a fallback.** Recording from a Bluetooth headset on Android requires opening an SCO link, which changes call audio routing. Only the Bluetooth setting does that. In phone mode with no built-in microphone, a wired or other microphone is used; with only Bluetooth present, the preferred device is left unset and Android's normal routing applies, still without SCO.
- **The bottom built-in microphone wins.** Phones list several built-in microphones (`bottom`, `back`, `top`). The bottom one is the voice microphone on most phones.

Telephony, tuner, remote-submix and echo-reference inputs are not microphones and are ignored. The device Android actually routed (`getRoutedDevice()`) is recorded for the dashboard.

### 4.2 Noise cancellation

Android gives an app three levers: the capture source, platform audio effects, and its own DSP. The settings combine them:

| Setting | Source | Platform effect | App DSP |
|---|---|---|---|
| Maximum (default) | `VOICE_COMMUNICATION` (call-grade multi-microphone noise cancellation) | `NoiseSuppressor` when available | 80 Hz high-pass |
| Standard | `VOICE_RECOGNITION` | `NoiseSuppressor` when available | 80 Hz high-pass |
| Off | `VOICE_RECOGNITION` | none | none |

`VOICE_COMMUNICATION` is the strongest noise cancellation available to an app without root, which is why it is the default the user asked for. Its processing is tuned for calls and can soften quiet consonants, so Standard is offered and the setting text tells the user when to choose it. The global audio mode is never changed, so other apps are not affected. If the phone has no `NoiseSuppressor`, or creating one fails, recording continues without it.

The high-pass filter is a second-order Butterworth biquad (RBJ cookbook) at 80 Hz. Speech energy begins around 100 Hz; below that is handling noise, wind rumble and DC offset.

### 4.3 Microphone boost (`asr/MicProcessor`)

A fixed large gain would clip loud speech, and clipping damages transcription more than low level does. "Maximum" therefore means *as loud as possible without clipping*. The boost is an automatic gain control built on the reference's dBFS meter:

1. RMS and peak are measured per frame (30 ms).
2. A noise floor follows quiet frames down immediately and rises at most 3 dB/s.
3. Only speech-like frames (at least 6 dB above the floor and above −65 dBFS) move the gain toward `target − level`, clamped to `[0, max]`. The gain falls quickly and rises at most 40 dB/s.
4. Each frame's gain is capped so its peak stays at −1 dBFS. A rising gain is spread across the frame to avoid clicks; a falling gain applies at once to avoid overshoot. A final clamp prevents 16-bit overflow.

Maximum targets −18 dBFS with up to +30 dB; Medium targets −24 dBFS with up to +12 dB. The rise rate was first 15 dB/s. Test `gainRisesQuicklyEnoughForShortDictations` failed at that rate: quiet speech reached only −33.6 dBFS after 1.5 s, because only about 40 % of frames count as speech. At 40 dB/s it reached −19.7 dBFS, and the noise-pumping test still passed, since the gain never rises on non-speech frames.

The WebRTC VAD (hands-free auto-stop) classifies the **raw** frame before processing, so auto-stop behaviour is unchanged. The processor also gathers the dashboard's microphone statistics: speech level before the boost, peak, clipped-sample ratio and average boost.

### 4.4 Usage metrics

- **Where they are measured.** `Whisper.prepareAndProcess()` is the single pipeline behind the main screen, keyboard, `RecognitionService` and `RECOGNIZE_SPEECH` Activity. A `SessionMeter` is attached to it, so every entry point is covered with one change.
- **Real usage instead of estimates.** `CloudClient` now reports the `usage` object of every successful request. For chat it reads `prompt_tokens`, `completion_tokens`, `prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens` and OpenRouter's `cost`. For transcription it reads token usage (`gpt-4o-*-transcribe`), `usage.seconds` (`whisper-1`) or the verbose-JSON `duration`. A request is reported once it succeeds at the HTTP level, even if its body is then rejected (no speech, truncated, unparseable), because the provider bills it. HTTP errors are not reported. A failing metrics listener never fails a dictation.
- **The reference estimate as fallback.** If a chat provider reports no usage, tokens are estimated at `(characters + 3) / 4` and flagged.
- **Local inference.** The on-device model loads asynchronously and the first window waits for it. The engine records when loading started and when the model became ready, so model load time and inference time are measured separately.
- **Statuses.** Completed dictations count words. Failed dictations, and cancelled ones that already made provider requests, count toward cost and reliability only.
- **Storage.** `metrics/UsageStore` is a private SQLite table excluded from backup by the existing rules. It stores counts and timings, never transcript text. Unknown values (NaN) are stored as NULL. Rows beyond 200 000 are pruned.

### 4.5 Prices (`metrics/PriceTable`)

A provider-reported cost always wins. Otherwise the user's edited rate is used, then the built-in rate. Rates were verified on 21 October 2026 from Groq's model documentation and OpenAI's pricing page; the table is in the [specification](superpowers/specs/2026-10-21-microphone-and-usage-dashboard.md#34-usage-metrics). Groq's 10-second minimum is applied per request when the request is recorded. Token-priced transcription without reported usage falls back to the per-minute estimate. A model with no rate is counted as *unpriced*, not as free. Prices are applied when the dashboard loads, so an edit applies to past usage too; provider-reported costs are stored as charged.

### 4.6 Dashboard (`DashboardActivity`, `metrics/DashboardStats`, `utils/BarChartView`)

The aggregation ports the reference's periods, time saved and peak window, and adds cost, tokens and cache hit rate (cached ÷ prompt tokens). Charts are a small custom `View` rather than a new dependency. The chart design follows the data-visualisation checklist: one axis, thin bars with 4 dp rounded data ends square at the baseline, a 2 dp surface gap between stacked segments, recessive gridlines, and at most about seven axis labels. Tapping a bar shows its value, a legend with totals accompanies the stacked chart, and the content description lists every value for screen readers. The three series colours were checked with the palette validator on both card surfaces. Light (`#0277BD #159A6B #E65100` on `#F0F9FF`) passes every check. For dark, the brand steps failed contrast on the navy card, so the reference dark steps (`#3987E5 #199E70 #D95926` on `#0B1D3A`) are used; they pass.

## 5. Tests

| Area | Test class | Kind |
|---|---|---|
| Device resolution, Bluetooth never a fallback, bottom microphone, duplicates, empty lists | `asr/MicRouterTest` | JVM |
| Boost to target, short-dictation rise time, Medium cap, no clipping, no noise pumping, gain hold in pauses, high-pass (DC, 20 Hz, 1 kHz), partial/odd/empty frames, clipping counter, byte-identical pass-through | `asr/MicProcessorTest` | JVM |
| Real recorder: phone default with a headset connected (SCO off, bottom microphone requested), Bluetooth setting (SCO on only while recording), Bluetooth fallback, Automatic, capture source per setting, boost raises level, Off keeps exact bytes, no input devices | `asr/RecorderMicrophoneTest` | Robolectric |
| Usage parsing (chat, cost, cached, malformed, negative, token/duration transcription) | `cloud/UsageTest` | JVM |
| Usage reporting by `CloudClient`: success, billed no-speech and invalid bodies, HTTP errors not counted, truncated enhancement, throwing listener | `cloud/CloudClientUsageTest` | JVM + MockWebServer |
| Prices: local free, per-hour, Groq minimum, token/minute fallback, cached input, reported cost, unpriced, edits, encoding | `metrics/PriceTableTest` | JVM |
| Meter: accumulation, Groq minimum, estimate fallback, local load vs inference, failed status, reported costs, microphone, word counting | `metrics/SessionMeterTest` | JVM |
| Aggregation: empty history, period windows, buckets, cost per bucket, time saved, tokens and cache, failed and cancelled, speed and latencies, peak window across midnight and ties, microphone, future timestamps | `metrics/DashboardStatsTest` | JVM |
| Store round trip with NaN and nulls, clear | `metrics/UsageStoreTest` | Robolectric |
| Pipeline records completed and failed dictations without text | `asr/WhisperUsageTest` | Robolectric |
| Dashboard screen: empty, tiles, cost, cache rate, reliability, token chart, model rows, period switch, price edits, formatting | `DashboardActivityTest` | Robolectric |
| Settings: dashboard link, microphone defaults | `SettingsScreenTest` | Robolectric |

`RecorderFailureTest` compares salvaged audio byte for byte with the source. Its setup now turns noise cancellation and boost off, because those tests are about salvage, not processing; `RecorderMicrophoneTest` covers processing.

### Evidence

- **Local, JDK 21 without the Android SDK:** the JVM-only classes were compiled and run directly with JUnit 4.13.2: `MicRouterTest`, `MicProcessorTest`, `UsageTest`, `PriceTableTest`, `SessionMeterTest`, `DashboardStatsTest` (54 tests), and `CloudClientUsageTest` together with the existing `CloudClientTest` (48 tests). All passed.
- **GitHub Actions:** see section 7.

## 6. Known limits

- No physical phone has run this build yet. Whether `VOICE_COMMUNICATION` improves or harms accuracy, which phones offer `NoiseSuppressor`, and how strong the call-grade cancellation is all vary by manufacturer. These need testing on DevilVed's phone.
- Robolectric does not route audio, so the routed-device result on a real phone is untested. The tests check the requested device and the SCO state.
- Prices change. The built-in rates are correct as of 21 October 2026 and can be edited on the dashboard.
- If OpenRouter reports cost for only some requests of a dictation, only the reported part is counted for that stage.
- Dictations cancelled before any provider request are not recorded.

## 7. Verification runs

(Filled in from GitHub Actions below.)

## 8. Source map

- `asr/MicRouter.java`, `asr/MicDevices.java`, `asr/MicSettings.java`, `asr/MicProcessor.java`, `asr/MicCapture.java`: the microphone.
- `asr/Recorder.java`: routing, SCO policy, capture source, effects, processing, capture statistics.
- `cloud/Usage.java`, `cloud/CloudClient.java`: provider usage.
- `metrics/*`: meter, store, prices, aggregation.
- `asr/Whisper.java`: meter attached to the shared pipeline; local load and inference timing.
- `DashboardActivity.java`, `utils/BarChartView.java`: the screen.
- `res/xml/settings_root.xml`, `res/values/mic_strings.xml`, `res/values/dashboard_strings.xml`, chart colours in `res/values*/colors.xml`.
