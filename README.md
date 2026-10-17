ToneTunner is developed and maintained by [DevilVed](https://github.com/DevilVed).

ToneTunner is an input method editor (IME) that leverages voice recognition technology based on the Whisper engine.
It offers a seamless user experience, functioning both as a standalone application and an integrated IME that can be activated, e.g. via the microphone button in HeliBoard.
As a standalone app ToneTunner can also translate any supported language to English.

Besides providing an IME, ToneTunner can also be selected as system-wide voice input (RecognitionService) and it supports calls via intent (RecognizerIntent.ACTION_RECOGNIZE_SPEECH).

## Initial Setup

Upon launching ToneTunner for the first time, you will need to download the Whisper model from Hugging Face and install it.
Voice recognition works entirely offline, ensuring your privacy and convenience.

Please note that for use as voice input (not as IME) there is a separate settings activity which can be accessed from Android settings
(System > Languages > Speech > Voice Input). There you can activate the app as voice input and then click the settings button.

## Using ToneTunner

To get the most out of ToneTunner, follow these simple tips:

- Press and hold the button while speaking or use automatic mode where available
- Pause briefly before starting to speak
- Speak clearly, loudly, and at a moderate pace
- Please note that there is a limit of 30s for each recording

By following these guidelines, you'll be able to enjoy accurate and efficient voice recognition with Whisper.

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
