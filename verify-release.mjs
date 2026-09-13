#!/usr/bin/env node
// Install this verifier and release-trust.json from an independently authenticated
// publisher channel. Never accept a replacement trust key from a downloaded bundle.
import { createHash, createPublicKey, verify, randomUUID } from "node:crypto";
import { openSync, closeSync, fstatSync, lstatSync, realpathSync, constants, readSync, writeFileSync, fsyncSync, linkSync, unlinkSync } from "node:fs";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const ARTIFACTS = Object.freeze({
  universal: { file: "hubdustry-mod.jar", maxBytes: 5767168 },
  desktop: { file: "hubdustry-mod-desktop-protected.jar", maxBytes: 3670016 },
  android: { file: "hubdustry-mod-android-protected.jar", maxBytes: 2097152 },
});
export const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
export const encode = (value) => Buffer.from(JSON.stringify(value, null, 2) + "\n");
export function readBounded(file, maximum) {
  if (!lstatSync(file).isFile()) throw new Error("REGULAR_FILE_REQUIRED");
  const fd = openSync(file, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
  try {
    const stat = fstatSync(fd), size = stat.size;
    if (!stat.isFile()) throw new Error("REGULAR_FILE_REQUIRED");
    if (!Number.isSafeInteger(size) || size < 1 || size > maximum) throw new Error("FILE_SIZE_INVALID");
    const bytes = Buffer.alloc(size); let offset = 0;
    while (offset < size) { const count = readSync(fd, bytes, offset, size - offset, null); if (!count) throw new Error("FILE_CHANGED"); offset += count; }
    if (readSync(fd, Buffer.alloc(1), 0, 1, null) !== 0 || fstatSync(fd).size !== size) throw new Error("FILE_CHANGED");
    return bytes;
  } finally { closeSync(fd); }
}
function fields(value, expected) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).sort().join("|") !== [...expected].sort().join("|")) throw new Error("MANIFEST_SCHEMA_INVALID");
}
function digest(value) { if (typeof value !== "string" || !/^[a-f0-9]{64}$/u.test(value)) throw new Error("DIGEST_INVALID"); }
export function requireRelease(value) {
  if (typeof value !== "string" || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}$/u.test(value)) throw new Error("EXPECTED_RELEASE_REQUIRED");
  return value;
}
export function trustedKey(trust) {
  fields(trust, ["schemaVersion", "algorithm", "keyId", "spki"]);
  if (trust.schemaVersion !== 1 || trust.algorithm !== "Ed25519" || typeof trust.spki !== "string") throw new Error("TRUST_INVALID");
  const der = Buffer.from(trust.spki, "base64");
  if (der.toString("base64") !== trust.spki || sha256(der) !== trust.keyId) throw new Error("TRUST_FINGERPRINT_INVALID");
  const key = createPublicKey({ key: der, type: "spki", format: "der" });
  if (key.asymmetricKeyType !== "ed25519") throw new Error("ED25519_REQUIRED");
  return key;
}
export function verifyBundle(bundle, trust, expectedRelease, variant = "universal") {
  requireRelease(expectedRelease);
  if (!Object.hasOwn(ARTIFACTS, variant)) throw new Error("VARIANT_INVALID");
  const manifestBytes = readBounded(join(bundle, "release-manifest.json"), 16 * 1024);
  const signature = readBounded(join(bundle, "release-manifest.sig"), 64);
  if (signature.length !== 64 || !verify(null, manifestBytes, trustedKey(trust), signature)) throw new Error("SIGNATURE_INVALID");
  const manifest = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(manifestBytes));
  // A canonical byte round-trip rejects duplicate keys, alternate encodings and trailing payloads.
  if (!encode(manifest).equals(manifestBytes)) throw new Error("MANIFEST_ENCODING_INVALID");
  fields(manifest, ["schemaVersion", "product", "releaseId", "keyId", "createdAt", "productionSourcesSha256", "recipeSha256", "artifacts"]);
  if (manifest.schemaVersion !== 1 || manifest.product !== "hubdustry-mod" || manifest.keyId !== trust.keyId) throw new Error("MANIFEST_IDENTITY_INVALID");
  if (manifest.releaseId !== expectedRelease) throw new Error("RELEASE_MISMATCH");
  if (typeof manifest.createdAt !== "string" || !Number.isFinite(Date.parse(manifest.createdAt)) || new Date(manifest.createdAt).toISOString() !== manifest.createdAt) throw new Error("MANIFEST_TIME_INVALID");
  digest(manifest.productionSourcesSha256); digest(manifest.recipeSha256);
  fields(manifest.artifacts, Object.keys(ARTIFACTS));
  for (const [name, definition] of Object.entries(ARTIFACTS)) {
    const item = manifest.artifacts[name]; fields(item, ["file", "bytes", "sha256"]); digest(item.sha256);
    if (item.file !== definition.file || !Number.isSafeInteger(item.bytes) || item.bytes < 1 || item.bytes > definition.maxBytes) throw new Error("ARTIFACT_METADATA_INVALID");
  }
  // Path selection is exclusively from the verifier's constants, never manifest data.
  const artifact = manifest.artifacts[variant], bytes = readBounded(join(bundle, ARTIFACTS[variant].file), ARTIFACTS[variant].maxBytes);
  if (bytes.length !== artifact.bytes || sha256(bytes) !== artifact.sha256) throw new Error("ARTIFACT_INTEGRITY_INVALID");
  return { manifest, artifact, bytes };
}
export function stageVerified(result, output) {
  // Check the in-memory bytes again, then write those same bytes once. No second source read.
  if (result.bytes.length !== result.artifact.bytes || sha256(result.bytes) !== result.artifact.sha256) throw new Error("STAGED_BYTES_CHANGED");
  const temporary = join(dirname(output), ".hubdustry-verified-" + randomUUID() + ".tmp");
  const fd = openSync(temporary, "wx", 0o600);
  try {
    try { writeFileSync(fd, result.bytes); fsyncSync(fd); } finally { closeSync(fd); }
    // Atomic, no-replace creation. A crash during the write leaves only a .tmp file.
    linkSync(temporary, output);
  } finally { unlinkSync(temporary); }
}
export function options(args, allowed) {
  const result = {};
  if (args.length % 2) throw new Error("OPTION_VALUE_REQUIRED");
  for (let i = 0; i < args.length; i += 2) {
    const name = args[i];
    if (!allowed.includes(name) || Object.hasOwn(result, name) || !args[i + 1] || args[i + 1].startsWith("--")) throw new Error("OPTION_INVALID");
    result[name] = args[i + 1];
  }
  return result;
}
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  try {
    const args = options(process.argv.slice(2), ["--bundle", "--release", "--variant", "--output"]);
    if (!args["--bundle"]) throw new Error("USAGE: node verify-release.mjs --bundle <downloads> --release <expected-id> [--variant universal|desktop|android] [--output <new-import-file.jar>]");
    const trust = JSON.parse(readBounded(join(dirname(fileURLToPath(import.meta.url)), "release-trust.json"), 4096));
    const result = verifyBundle(resolve(args["--bundle"]), trust, args["--release"], args["--variant"]);
    if (args["--output"]) stageVerified(result, resolve(args["--output"]));
    console.log(JSON.stringify({ status: "VERIFIED", releaseId: result.manifest.releaseId, keyId: trust.keyId, ...result.artifact, staged: Boolean(args["--output"]) }, null, 2));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
