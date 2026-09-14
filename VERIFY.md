# Verify a download

Get `verify-release.mjs` and `release-trust.json` from this official repository.
Keep them outside the folder containing the downloaded release files. Do not
replace the trusted key with one supplied by an unknown download.

Download `hubdustry-mod.jar`, `release-manifest.json` and `release-manifest.sig`
from the same release into a folder, then use Node.js 24:

```sh
node verify-release.mjs --bundle path/to/downloads --release 0.1.2
```

The expected publisher key fingerprint is:

```text
1db8e8481ca32be021a3f22cd0dc3f4b047cdcd0b17b58c01a8ae4f4c3ebc1f6
```

This checks the signed manifest and the exact JAR bytes. Mindustry's built-in
Mod Browser does not run this separate verification step.
