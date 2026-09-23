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
The release candidate `hubdustry-mod-0.2.0.jar` combines JVM classes and Android
DEX in one artifact for the game's built-in mod installer.

The public artifact is built from this repository alone. It contains no
updater, obfuscated classes, private service implementation or developer
endpoint. The backend origin is fixed to `https://api.hubdustry.com` and all
source downloads are size and SHA-256 checked before import.

The client provides a native Arc browser for public schematic and map items.
Network failures leave ordinary Mindustry gameplay available. Authentication,
authoring and moderation controls appear only when the backend projects the
corresponding capability.

This new generation is a development candidate. Full database integration,
in-game desktop/Android acceptance and production backend activation are release
gates; a successful build alone does not establish them. Earlier releases remain
available while this candidate is validated.

The [public API snapshot](docs/library-contract.json) contains the client-facing
authentication and library schemas. Imported author credit is independent of
uploader identity; a verified matching Discord identity can reclaim management.

The [source review license](SOURCE-AVAILABLE-LICENSE.md) permits official personal
play and private verification builds. It prohibits reuse, redistribution and
commercial exploitation of Hubdustry code, subject to the stated platform and
third-party exceptions. This is source-available software, not open source.
