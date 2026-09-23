# Verify a download

Get `verify-release.mjs` and `release-trust.json` from this official repository.
Keep them outside the folder containing the downloaded release files. Do not
replace the trusted key with one supplied by an unknown download.

Download `hubdustry-mod.jar`, `release-manifest.json` and `release-manifest.sig`
from the same release into a folder, then use Node.js 24:

```sh
node verify-release.mjs --bundle path/to/downloads --release 0.2.0
```

The expected publisher key fingerprint is:

```text
1db8e8481ca32be021a3f22cd0dc3f4b047cdcd0b17b58c01a8ae4f4c3ebc1f6
```

This checks the signed manifest and the exact JAR bytes. Mindustry's built-in
Mod Browser does not run this separate verification step.

Kotlin releases use manifest version2 and the same publisher key. Their platform
alternatives are `hubdustry-mod-desktop.jar` and `hubdustry-mod-android.jar`; use
`--variant desktop` or `--variant android` to verify the selected alternative.
The default remains the universal `hubdustry-mod.jar`. Version1 manifests and
their earlier artifact names remain supported.

Version2 identifies `sourceRepository` and the exact `sourceRevision`.
`productionSourcesSha256` hashes the publisher's uncompressed `git archive`
source tar, and `recipeSha256` hashes the canonical JSON list of build-input
paths and SHA-256 digests. These are source/build identities; they do not claim
independently reproducible builds, game acceptance or release approval.
