# Pull Request Guidelines

If a pull request contains updates to any `.jules` folder or its files—such as `bolt.md`, `security.md`, or similar files—do not modify or remove those changes. These files serve as log files for the Jules agent, providing context and helping prevent it from repeating the same pull request work.

- Do not name the reference/inspiration application in pull-request titles or descriptions. Describe the behavior and say it was inspired by another application when useful. Keep named attribution and source links in the README and engineering research documentation.

# Project identity

- ToneTunner is developed and maintained by **DevilVed** (GitHub `DevilVed`, Hugging Face `realSauron`).
- Never replace or remove the `DevilVed` name anywhere (applicationId, package names, dependency coordinates, links, README, NOTICE) without explicit permission from DevilVed.
- Java package: `com.devilved.tonetunner`. applicationId: `org.DevilVed.whisperplus` (do not change: installed copies would stop receiving updates).

# Licensing (required, do not remove)

ToneTunner is GPLv3 and is a modified version of woheller69/whisperIMEplus. These must stay:
- `LICENSE` (GPLv3) and `NOTICE`.
- The Apache-2.0 copyright headers (Luca Martino / RTranslator, Android Open Source Project) in `voice_translation/**`.
- The Credits section in `README.md`.
Removing them violates GPLv3/Apache-2.0 and risks a DMCA takedown of the repository.

# Branches

- `master`: releases. Do not target new development pull requests here unless DevilVed explicitly requests it.
- `beta-test-v1`: current development and pull-request target, including bug fixes and the VoiceInk-inspired work. Branch from its latest remote commit. Merge `master` into it regularly so it gets release fixes.
- `testing-phase-1` was deleted. Its old Large-V3 code is only in the local branch `archive/testing-phase-1`; port pieces from it, don't restore it.
- Merged branches are deleted automatically. Stacked PRs are retargeted to the base when the lower PR merges, so wait for that before merging the next one.

# Documentation location (required)

- `AGENTS.md` is intentionally tracked as shared project guidance. Keep credentials and private machine paths outside it.
- Keep every engineering document, research note, design, implementation plan, verification record, and other documentation created for ToneTunner inside the ToneTunner project directory.
- Use the repository `docs/` tree for canonical documents. Optional copies in `.superpower/` must also remain inside the ToneTunner project directory.
- Do not place the only or canonical copy of documentation in global Codex directories, external temporary folders, or another project. When using an isolated worktree, keep the PR copy in its repository tree and keep a synchronized copy under the main ToneTunner project directory's `docs/` tree throughout the work.
- The `inspiration folder` contains the reference VoiceInk checkout. It is not the required location for ToneTunner documentation; do not vendor that checkout into ToneTunner commits.

# Building

- Run Android builds and executable tests in GitHub Actions:
  - `.github/workflows/android-build.yml`: debug APK on push/PR, or `gh workflow run android-build.yml -f ref=<branch>`.
  - `.github/workflows/release.yml`: push tag `v<versionName>` to publish a signed APK to GitHub Releases; a manual run is a signed dry run.
- The repo has no `gradlew` script. Read the current Gradle version from `gradle/wrapper/gradle-wrapper.properties`, the Android Gradle Plugin version from the root `build.gradle`, and the JDK configuration from the workflows. Keep workflow toolchain pins synchronized with those files when upgrading.
- Never commit keystores, passwords or `local.properties`. Signing keys live only in GitHub secrets and in the maintainer's private backup.
- Before every release, bump `versionCode` and `versionName` in `app/build.gradle`.

# Model support (Recognizer)

- The current engine expects the RTranslator Whisper-Small ONNX set (`Whisper_initializer`, `encoder`, `cache_initializer`, `decoder`, `detokenizer`) in the app's external files directory.
- Model shape must come from the model's `config.json`. Use `decoder_layers` and `decoder_attention_heads` for the decoder cache (not `num_hidden_layers`/`encoder_*`; distil-whisper differs). Head dim = `d_model / decoder_attention_heads`.
- Past self-attention cache starts empty: shape `[batch, heads, 0, head_dim]` per layer.
- Cross-attention (encoder) key/values are not empty. They are computed from the encoder output, either by a cache-initializer model exported for that architecture or by a Hugging Face `decoder_model_merged.onnx` that takes `encoder_hidden_states`.
- Special token IDs differ between models (large-v3 adds `yue`, shifting translate/transcribe/no-timestamps by +1); read them from `generation_config.json`.
- Close every `OnnxTensor` and `OrtSession.Result` you create; large models run out of memory quickly otherwise.

# Model hosting

Model weights are published on Hugging Face under `realSauron` (upload with the `hf` CLI).
