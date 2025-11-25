# Renaming Whisper+ to ToneTunner

I have renamed the application from "Whisper+" to "ToneTunner" across the entire project.

## Changes Made

### 1. Application Name and Labels

Updated `strings.xml` and localized versions to use "ToneTunner".

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ar/strings.xml`
- `app/src/main/res/values-de/strings.xml`
- `app/src/main/res/values-fr/strings.xml`
- `app/src/main/res/values-hu/strings.xml`
- `app/src/main/res/values-iw/strings.xml`
- `app/src/main/res/values-pt-rBR/strings.xml`
- `app/src/main/res/values-ro/strings.xml`
- `app/src/main/res/values-ru/strings.xml`

### 2. Project Configuration

Updated `settings.gradle` to set the root project name to "ToneTunner".

- `settings.gradle`

### 3. Documentation

Updated `README.md` to refer to the app as "ToneTunner" while preserving references to the "Whisper" model/engine.

- `README.md`

### 4. Fastlane Metadata

Updated store listing titles and descriptions in multiple languages.

- `fastlane/metadata/android/en-US/title.txt`
- `fastlane/metadata/android/ar/title.txt`
- `fastlane/metadata/android/*/full_description.txt` (multiple languages)

## Verification

I performed a global search for "Whisper+" and confirmed that no user-visible occurrences remain.
I also checked for "Whisper" to ensure that model references were preserved but app name references were updated (specifically in Arabic where "Whisper" was used as the app name).

# Replacing Application Logo

I have replaced the application logo with the user-provided image.

## Changes Made

### 1. Icon Resources

- Added `app/src/main/res/drawable/ic_launcher_foreground.png`.
  - Initially attempted to generate a processed logo using AI, but fell back to manual cropping due to service unavailability.
  - Cropped the user-provided image to a square (558x558) centered on the logo using `sips`.
- Updated `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` to use the new foreground and a black background.
- Added `ic_launcher_background` color (black) to `app/src/main/res/values/colors.xml`.

## Verification

- Verified that `ic_launcher_foreground.png` exists and is a valid PNG.
- Verified `ic_launcher.xml` points to the correct resources.

# Replacing Developer Name

I have replaced "woheller69" with "DevilVed" in the application configuration and documentation to ensure privacy.

## Changes Made

### 1. Build Configuration

- Updated `applicationId` in `app/build.gradle` to `org.DevilVed.whisperplus`.

### 2. Source Code

- Updated GitHub links in `app/src/main/java/com/whisperonnx/MainActivity.java` to point to `DevilVed/whisperIMEplus`.

### 3. Documentation

- Updated license holder and repo links in `README.md` to "DevilVed".

## Verification

- Verified `app/build.gradle` contains the new `applicationId`.
- Verified `MainActivity.java` contains the updated links.
- Verified `README.md` reflects the name change.
- **Note**: The dependency `com.github.woheller69:FreeDroidWarn` was changed to `com.github.DevilVed:FreeDroidWarn` and the corresponding import was updated to `org.DevilVed.freeDroidWarn.FreeDroidWarn`, as confirmed by the user.
