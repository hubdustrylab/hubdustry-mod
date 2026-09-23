import assert from "node:assert/strict";
import { test, after } from "node:test";
import { generateKeyPairSync, sign } from "node:crypto";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync, renameSync, rmSync, realpathSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname, basename } from "node:path";
import { ARTIFACTS, KOTLIN_ARTIFACTS, encode, sha256, verifyBundle, stageVerified } from "./verify-release.mjs";

const workspace = mkdtempSync(join(tmpdir(), "hubdustry-signature-check-"));
const pair = generateKeyPairSync("ed25519"), other = generateKeyPairSync("ed25519");
const publicBytes = pair.publicKey.export({ type: "spki", format: "der" });
const trust = { schemaVersion: 1, algorithm: "Ed25519", keyId: sha256(publicBytes), spki: publicBytes.toString("base64") };
after(() => {
  const actual = realpathSync(workspace);
  if (dirname(actual) !== realpathSync(tmpdir()) || !basename(actual).startsWith("hubdustry-signature-check-")) throw new Error("TEST_CLEANUP_PATH_INVALID");
  rmSync(actual, { recursive: true, force: true });
});
let index = 0;
function fixture(edit = () => {}, signingKey = pair.privateKey, serialize = encode, schemaVersion = 1) {
  const directory = join(workspace, String(index++)); mkdirSync(directory);
  const artifacts = {};
  const definitions = schemaVersion === 2 ? KOTLIN_ARTIFACTS : ARTIFACTS;
  for (const [variant, definition] of Object.entries(definitions)) {
    const bytes = Buffer.from("ZIP fixture identity: " + variant);
    writeFileSync(join(directory, definition.file), bytes);
    artifacts[variant] = { file: definition.file, bytes: bytes.length, sha256: sha256(bytes) };
  }
  const manifest = { schemaVersion, product: "hubdustry-mod", releaseId: "candidate.2", keyId: trust.keyId,
    createdAt: "2026-09-13T00:00:00.000Z", productionSourcesSha256: "a".repeat(64), recipeSha256: "b".repeat(64), artifacts };
  if (schemaVersion === 2) { manifest.sourceRepository = "hubdustrylab/hubdustry-mod"; manifest.sourceRevision = "a".repeat(40); }
  edit(manifest);
  const bytes = serialize(manifest);
  writeFileSync(join(directory, "release-manifest.json"), bytes);
  writeFileSync(join(directory, "release-manifest.sig"), sign(null, bytes, signingKey));
  return directory;
}
function denied(directory, pattern, expected = "candidate.2", variant = "universal") {
  const output = join(directory, "not-installed.jar");
  assert.throws(() => stageVerified(verifyBundle(directory, trust, expected, variant), output), pattern);
  assert.equal(existsSync(output), false);
}
test("authentic platform selection stages only the exact verified buffer, despite source replacement", () => {
  for (const variant of Object.keys(ARTIFACTS)) {
    const directory = fixture(), result = verifyBundle(directory, trust, "candidate.2", variant);
    const before = Buffer.from(result.bytes), source = join(directory, ARTIFACTS[variant].file);
    renameSync(source, source + ".original"); writeFileSync(source, "replacement after verification");
    const output = join(directory, "import.jar"); stageVerified(result, output);
    assert.deepEqual(readFileSync(output), before);
    assert.throws(() => stageVerified(result, output), /EEXIST/u);
    assert.deepEqual(readFileSync(output), before);
  }
});
test("changed artifact is refused without staging", () => {
  const directory = fixture(); writeFileSync(join(directory, ARTIFACTS.universal.file), "modified code");
  denied(directory, /ARTIFACT_INTEGRITY/u);
});

test("public universal-only bundle verifies without private platform alternatives", () => {
  const directory = fixture();
  for (const variant of ["desktop", "android"]) {
    const source = join(directory, ARTIFACTS[variant].file);
    renameSync(source, join(workspace, "private-" + index + "-" + variant + ".jar"));
  }
  const result = verifyBundle(directory, trust, "candidate.2");
  assert.equal(result.artifact.file, "hubdustry-mod.jar");
  const output = join(directory, "import.jar");
  stageVerified(result, output);
  assert.deepEqual(readFileSync(output), result.bytes);
  denied(directory, /ENOENT/u, "candidate.2", "android");
});
test("changed signed metadata is refused", () => {
  const directory = fixture(), file = join(directory, "release-manifest.json");
  writeFileSync(file, readFileSync(file, "utf8").replace("candidate.2", "candidate.3"));
  denied(directory, /SIGNATURE_INVALID/u);
});
test("attacker key cannot replace publisher even when claiming the trusted key ID", () => denied(fixture(() => {}, other.privateKey), /SIGNATURE_INVALID/u));
test("missing signature is refused", () => {
  const directory = fixture(); renameSync(join(directory, "release-manifest.sig"), join(directory, "missing.sig")); denied(directory, /ENOENT/u);
});
test("truncated and oversized signatures are refused", () => {
  for (const length of [63, 65]) { const directory = fixture(); writeFileSync(join(directory, "release-manifest.sig"), Buffer.alloc(length)); denied(directory, /SIGNATURE_INVALID|FILE_SIZE_INVALID/u); }
});
test("an authentic older/different release cannot satisfy an explicit expected release", () => denied(fixture(), /RELEASE_MISMATCH/u, "candidate.3"));
test("expected release is mandatory and platform cannot come from an arbitrary field", () => {
  denied(fixture(), /EXPECTED_RELEASE_REQUIRED/u, ""); denied(fixture(), /VARIANT_INVALID/u, "candidate.2", "__proto__");
});
test("signed path traversal, URLs, aliases and wrong platform mapping are refused", () => {
  for (const path of ["../escape.jar", "C:\\escape.jar", "https://example.com/a.jar", ARTIFACTS.desktop.file, ARTIFACTS.universal.file + ":stream"]) {
    denied(fixture(m => { m.artifacts.universal.file = path; }), /ARTIFACT_METADATA_INVALID/u);
  }
});
test("signed duplicate JSON keys are refused", () => denied(fixture(() => {}, pair.privateKey,
  m => Buffer.from(encode(m).toString().replace('"schemaVersion": 1,', '"schemaVersion": 1,\n  "schemaVersion": 1,'))), /MANIFEST_ENCODING_INVALID/u));
test("unknown schema/fields, wrong product, wrong key ID and invalid date are refused", () => {
  for (const edit of [m => { m.schemaVersion = 2; }, m => { m.product = "another-mod"; }, m => { m.keyId = "c".repeat(64); },
    m => { m.publicKey = trust.spki; }, m => { m.createdAt = "not-a-date"; }, m => { m.artifacts.extra = m.artifacts.universal; }]) {
    denied(fixture(edit), /MANIFEST_/u);
  }
});
test("signed invalid hashes, fractional lengths and excessive sizes are refused", () => {
  for (const edit of [m => { m.artifacts.universal.sha256 = "g".repeat(64); }, m => { m.artifacts.universal.bytes = 1.5; },
    m => { m.artifacts.universal.bytes = 5767169; }]) denied(fixture(edit), /DIGEST_INVALID|ARTIFACT_METADATA_INVALID/u);
});
test("oversized metadata is bounded before cryptographic verification", () => {
  const directory = fixture(); writeFileSync(join(directory, "release-manifest.json"), Buffer.alloc(16385)); denied(directory, /FILE_SIZE_INVALID/u);
});
test("replaced trusted key material and changed verified buffer are refused", () => {
  const directory = fixture(); assert.throws(() => verifyBundle(directory, { ...trust, keyId: "f".repeat(64) }, "candidate.2"), /TRUST_FINGERPRINT_INVALID/u);
  const result = verifyBundle(directory, trust, "candidate.2"); result.bytes[0] ^= 1;
  const output = join(directory, "changed.jar"); assert.throws(() => stageVerified(result, output), /STAGED_BYTES_CHANGED/u); assert.equal(existsSync(output), false);
});

test("Kotlin bundles verify and stage all unobfuscated variants under the existing trust key", () => {
  const directory = fixture(() => {}, pair.privateKey, encode, 2);
  for (const variant of Object.keys(KOTLIN_ARTIFACTS)) {
    const result = verifyBundle(directory, trust, "candidate.2", variant);
    assert.equal(result.manifest.schemaVersion, 2);
    assert.equal(result.manifest.sourceRepository, "hubdustrylab/hubdustry-mod");
    assert.equal(result.artifact.file, KOTLIN_ARTIFACTS[variant].file);
    const output = join(directory, `${variant}-verified.jar`); stageVerified(result, output);
    assert.deepEqual(readFileSync(output), result.bytes);
  }
});

test("Kotlin source identity, legacy filename substitution and cross-platform names are rejected", () => {
  for (const edit of [m => { delete m.sourceRevision; }, m => { m.sourceRevision = "main"; }, m => { m.sourceRepository = "other/repo"; }, m => { m.artifacts.desktop.file = ARTIFACTS.desktop.file; }, m => { m.artifacts.android.file = KOTLIN_ARTIFACTS.desktop.file; }, m => { m.schemaVersion = 3; }]) {
    denied(fixture(edit, pair.privateKey, encode, 2), /MANIFEST_|ARTIFACT_METADATA_INVALID/u);
  }
});

test("Kotlin tampering, alternate signing keys and artifact budget overruns are rejected", () => {
  denied(fixture(() => {}, other.privateKey, encode, 2), /SIGNATURE_INVALID/u);
  const directory = fixture(() => {}, pair.privateKey, encode, 2);
  writeFileSync(join(directory, KOTLIN_ARTIFACTS.android.file), "modified");
  denied(directory, /ARTIFACT_INTEGRITY_INVALID/u, "candidate.2", "android");
  for (const variant of Object.keys(KOTLIN_ARTIFACTS)) denied(fixture(m => { m.artifacts[variant].bytes = KOTLIN_ARTIFACTS[variant].maxBytes + 1; }, pair.privateKey, encode, 2), /ARTIFACT_METADATA_INVALID/u);
});
