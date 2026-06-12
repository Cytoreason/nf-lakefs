# nf-lakefs — S3-backed lakeFS support

## Goal

Make an **S3-backed** lakeFS repo work in **both** transfer modes (`signed_url`, `physical_path`),
storing a uniform, server-authoritative **crc64nvme** checksum on S3 (GCS keeps **crc32c**).

## Design (option A — scheme-agnostic, use the backend's Nextflow NIO)

physical_path no longer has any S3-specific transfer code. `upload()` and `newByteChannel` just resolve the
backend path and copy through its own Nextflow NIO — `gs://` via nf-google, `s3://` via nf-amazon (which does
multipart for large files, incl. >2 GB). Symmetric with how GCS already worked.

**Checksum = whatever the backend computed over what it stored** (authoritative, no client-side hashing):

- **signed_url** — `resolveUploadChecksum(responseHeaders)` reads the PUT-response checksum: GCS `x-goog-hash`
  crc32c, S3 `x-amz-checksum-crc64nvme`. (Verified: a header-less presigned PUT to S3 *does* echo
  `x-amz-checksum-crc64nvme` + `x-amz-checksum-type: FULL_OBJECT`.) Unquoted ETag is the fallback.
- **physical_path** — `getLinkMetadata(Path)`: gs → crc32c (`getCrc32cToHexString`), s3 → crc64nvme via
  `S3PhysicalUploader.crc64nvmeHex()` (a single `GetObjectAttributes` call). S3 exposes no checksum through
  nf-amazon's NIO attributes, hence the small read-back.

**Making nf-amazon store crc64nvme:** nf-amazon has no checksum config; its SDK v2 client defaults to CRC32.
`NextflowLakeFSPlugin.start()` sets the JVM system property `aws.requestChecksumCalculation=when_required`
(only if unset), so the client sends **no** upload checksum and S3 applies its server-side **crc64nvme**
default — which `crc64nvmeHex` then reads back. JVM-global on purpose (also upgrades Nextflow's other S3
transfers CRC32→crc64nvme; benign, it's AWS's newer default).

## Checksum matrix

| | signed_url | physical_path |
|---|---|---|
| **S3** | crc64nvme | crc64nvme |
| **GCS** | crc32c | crc32c |

## Compatibility (important)

Needs **Nextflow ≥ 25.10**. nf-amazon's S3 NIO is AWS **SDK v1** in Nextflow 25.04 (nf-amazon 2.15.0,
`aws-java-sdk-s3-1.12.777` — no crc64nvme), and **SDK v2** in 25.10 (nf-amazon 3.4.4, `s3-2.33.2`).
So `nextflowVersion = 25.10.4` (the manifest then *requires* ≥25.10, refusing to load where S3 would break),
and the AWS SDK is `compileOnly` (provided at runtime by nf-amazon) pinned to **2.33.2**.

## Implementation

- `NextflowLakeFSFileSystemProvider`: scheme-agnostic `upload()` + `newByteChannel`; `getLinkMetadata` s3 →
  crc64nvme (throws on unknown scheme / non-Path); `resolveUploadChecksum` unchanged (handles s3 crc64nvme).
  The bespoke `S3PhysicalWriteChannel` / `S3PhysicalReadChannel` / `S3MultipartUpload` are **deleted**.
- New `S3PhysicalUploader.crc64nvmeHex(s3Address)` — GetObjectAttributes read-back (+ ETag fallback w/ warn).
- `NextflowLakeFSPlugin.start()` — sets `aws.requestChecksumCalculation=when_required`, debug-logs the value.
- `build.gradle` — `nextflowVersion 25.10.4`; `software.amazon.awssdk:s3:2.33.2` compileOnly + testImplementation
  + `url-connection-client` (test HTTP client). nf-amazon is **not** declared (Nextflow provides it at runtime).

## Validation

- Unit: `resolveUploadChecksum` (GCS crc32c + S3 crc64nvme + ETag fallbacks) — 17 tests.
- `S3PhysicalUploaderSpec` (real S3, gated on `AWS_ACCESS_KEY_ID` + `AWS_S3_TEST_PREFIX`): a default SDK client
  (faithful stand-in for nf-amazon) + `when_required` → object stored with crc64nvme → `crc64nvmeHex` reads 16-hex.
- Live on Nextflow 25.10.4: **>2 GB** upload to an S3-backed repo linked a full-object crc64nvme
  (`db00d6a7c31222c4`); single-PUT and 3-part multipart both → crc64nvme (FULL_OBJECT); signed_url GCS + S3
  round-trips; presigned PUT returns `x-amz-checksum-crc64nvme`.

## Notes / limits

- The gradle test harness loads only one Nextflow plugin (nf-google, via the test `MANIFEST.MF`), so the full
  `lakefs://` → nf-amazon → S3 e2e can't run there; it's covered by the live run + `S3PhysicalUploaderSpec` +
  the fact that gs:// and s3:// share the identical generic transfer/link code (GCS tests exercise it).
- gradle.properties `lakefs.*` overrides `LAKEFS_*` env vars (the test block prefers `findProperty`).
- 3 pre-existing `LakeFSNioSpec` physical_path failures ("invalid address signature") are unrelated to this work.

## Status: implemented + validated, not yet committed.
