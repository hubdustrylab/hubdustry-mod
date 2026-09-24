<p align="center">
  <img src="assets/hero.svg" width="100%" alt="Hubdustry" />
</p>

<p align="center">A source-available schematic and map library for Mindustry.</p>

<p align="center">
  <a href="https://github.com/hubdustrylab/hubdustry-mod"><img src="assets/download.svg" width="172" height="56" alt="Source" /></a>
</p>

<p align="center">Mindustry 160.2 &middot; Kotlin/JVM 1.9.25 &middot; Java 17</p>

## Build

Use JDK 17 and the checked-in Gradle wrapper. Windows uses `gradlew.bat`.

```sh
./gradlew test desktopJar
./gradlew androidJar
./gradlew universalJar
```

Android packaging additionally needs `ANDROID_SDK_ROOT` or `ANDROID_HOME`,
build-tools 35.0.0 and android-35. D8 and Android API input hashes are checked
before packaging. Android 8.0 (API 26) is the current minimum. Outputs are
`build/dist/hubdustry-mod-desktop.jar` and `hubdustry-mod-android.jar`.
The build output `hubdustry-mod-0.2.1.jar` combines JVM classes and Android
DEX in one artifact for the game's built-in mod installer.

Install [Hubdustry 0.2.1](https://github.com/hubdustrylab/hubdustry-mod/releases/tag/v0.2.1)
using `hubdustry-mod.jar`. Platform-specific alternatives are included in
`platform-alternatives.zip`; the standalone release JAR works on both platforms.

The public artifact is built from this repository alone. It contains no
updater, obfuscated classes, private service implementation or developer
endpoint. The backend origin is fixed to `https://api.hubdustry.com` and all
source downloads are size and SHA-256 checked before import.

This release temporarily opts out of the public Mod Browser through
`hideBrowser: true`. Install it directly from the GitHub release above.

Click the **Hubdustry logo/avatar at the bottom of the main menu** to open the
library. Click Login or your name below it to open account actions. The sidebar
switches between **Schematic browser** and **Map browser**, and can collapse
to an icon rail. Narrow screens use a navigation menu. Settings → Hubdustry
remains available as another entry point.
The two pages keep their own search, filters and position. Image cards open a
separate detail page; grouped filters and account actions have separate dialogs.
Tags are selections from the system catalog, not user-created labels.
The [native interface guide](docs/interface.md) defines the shared colors,
typography and component behavior.
Network failures leave ordinary Mindustry gameplay available. Authentication,
authoring and moderation controls appear only when the backend projects the
corresponding capability.

The production library API is available. The version0.2.0 desktop browser,
renderer backgrounds and schematic import were exercised in Mindustry160.2
against the activated backend. Version0.2.1 adds the main-menu account entry and
responsive sidebar, with original avatar colors and transparent hologram marks.
Android packaging and earlier fixture checks
remain separate evidence; there is no new Android production gameplay claim.
The map browser is available, with an empty map catalog at launch.

The [public API snapshot](docs/library-contract.json) contains the client-facing
authentication and library schemas. Imported author credit is independent of
uploader identity; a verified matching Discord identity can reclaim management.

The [source review license](SOURCE-AVAILABLE-LICENSE.md) permits official personal
play and private verification builds. It prohibits reuse, redistribution and
commercial exploitation of Hubdustry code, subject to the stated platform and
third-party exceptions. This is source-available software, not open source.
