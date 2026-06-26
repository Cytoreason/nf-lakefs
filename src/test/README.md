# Running Tests

## Unit Tests

Unit tests run without any external dependencies:

```bash
./gradlew test
```

## Integration Tests (LakeFSNioSpec)

Integration tests require a running lakeFS instance and are skipped by default when the required environment variables are not set.

### Required Environment Variables

| Variable | Description |
|----------|-------------|
| `LAKEFS_ACCESS_KEY` | lakeFS access key |
| `LAKEFS_SECRET_KEY` | lakeFS secret key |
| `LAKEFS_API_URL` | lakeFS API endpoint (e.g., `https://your-lakefs-instance.com/api/v1`) |
| `LAKEFS_TEST_REPO` | Repository name to use for tests |
| `LAKEFS_TEST_BRANCH` | Branch name to use for tests |

### Optional Environment Variables (for physical_path transfer mode)

These are only needed when running tests with `physical_path` transfer mode or the ext-bucket linking tests:

| Variable                  | Description                                                    | Default |
|---------------------------|----------------------------------------------------------------|---------|
| `GOOGLE_PROJECT`          | GCP project ID                                                 | `""` |
| `GOOGLE_REGION`           | GCP region                                                     | `europe-west1` |
| `GOOGLE_EXT_BUCKET`       | GCS external bucket (different from the repo's bucket)         | `""` |
| `AWS_ACCESS_KEY_ID`       | AWS access key (S3-backed physical_path or ext-bucket linking) | `""` |
| `AWS_SECRET_ACCESS_KEY`   | AWS secret key                                                 | `""` |
| `AWS_REGION`              | AWS region                                                     | `eu-central-1` |
| `AWS_EXT_BUCKET`          | S3 external bucket (different from the repo's bucket)          | `""` |

### S3 checksum test (`S3ChecksumReaderSpec`)

The only S3-specific code in the plugin is reading the server-computed `crc64nvme` back from an object
(`GetObjectAttributes`) — nf-amazon's NIO exposes no checksum, so we read it via the AWS SDK directly.
`S3ChecksumReaderSpec` covers exactly that: it PUTs a tiny object with a default SDK v2 client (the faithful
stand-in for nf-amazon) under `requestChecksumCalculation=when_required` and asserts the helper reads back a
full-object `crc64nvme`. It needs no plugin loading, so it runs in the standard test harness. It is **skipped**
unless both variables below are set:

| Variable | Description |
|----------|-------------|
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | AWS credentials (any standard SDK credential source works) |
| `AWS_S3_TEST_PREFIX` | A writable s3 prefix, e.g. `s3://my-bucket/test` (a `nf-lakefs-it/` object is created and deleted) |

```bash
export AWS_ACCESS_KEY_ID="..." AWS_SECRET_ACCESS_KEY="..."
export AWS_S3_TEST_PREFIX="s3://my-bucket/test"
./gradlew test --tests '*S3ChecksumReaderSpec'
```

### S3-backed lakeFS: what is and isn't covered here

S3-backed repositories require **Nextflow ≥ 25.10** at runtime (nf-amazon's S3 NIO only uses AWS SDK v2 — and
thus `crc64nvme` — from 25.10 onward).

- **`signed_url`** is backend-agnostic — it PUTs to a presigned URL with its own HTTP client and needs no
  backend plugin — so it runs in this harness against an S3-backed repo. Point `LAKEFS_TEST_REPO` at an
  s3-backed repo and set `LAKEFS_TRANSFER_MODE=signed_url`. (Verified in-harness against an S3-backed repo:
  the `LakeFSNioSpec` signed_url round-trips — create/read/copy/size/exists/metadata/delete — all pass.)
- **`physical_path`** routes the transfer through nf-amazon's S3 NIO and **is now covered in-harness** by
  `LakeFSPhysicalPathS3Spec` (see below). This required a small bit of test wiring: by default the harness runs
  in dev-plugins mode with no plugins loaded, so `s3://` can't be resolved and a run fails with
  `IllegalStateException: Missing plugin 'nf-amazon'` (thrown by Nextflow's `FileHelper.asPath`). nf-amazon
  declares its `S3PathFactory` only in its pf4j `extensions.idx`, which the default manager doesn't read —
  whereas `gs://` works because nf-google ships a `META-INF/services/nextflow.file.FileSystemPathFactory`
  service file. We add the equivalent file for S3 in `src/test/resources` (registering `S3PathFactory` via JDK
  ServiceLoader), put `nf-amazon` on the test classpath, and set `aws.requestChecksumCalculation=when_required`
  in the test task (mirroring `NextflowLakeFSPlugin.start()`, which the harness doesn't invoke) so S3 stores
  the server-side `crc64nvme` that physical_path links back.

#### Gated S3 physical_path round-trip (`LakeFSPhysicalPathS3Spec`)

Writes via `lakefs://` → nf-amazon S3 NIO → S3, links the object into lakeFS, and asserts a 16-hex `crc64nvme`
checksum was linked and the content round-trips. Uses a **dedicated** `LAKEFS_S3_*` config (separate from the
primary `LAKEFS_*`, so it can target an S3 instance without clobbering a GCS default) plus AWS object-store
credentials. The target branch must already exist. **Skipped** unless all of these are set:

| Variable | Description |
|----------|-------------|
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | AWS creds for the S3 backing store (physical_path writes straight to S3) |
| `AWS_REGION` | bucket region (default `eu-central-1`) |
| `LAKEFS_S3_API_URL` | lakeFS API URL of an **S3-backed** instance |
| `LAKEFS_S3_ACCESS_KEY` / `LAKEFS_S3_SECRET_KEY` | lakeFS API creds for that instance |
| `LAKEFS_S3_TEST_REPO` / `LAKEFS_S3_TEST_BRANCH` | S3-backed repo + an existing branch |

```bash
# AWS creds for the S3 backing store — must have write access to the repo's bucket
export AWS_ACCESS_KEY_ID=…
export AWS_SECRET_ACCESS_KEY=…
export AWS_REGION=eu-central-1

# the S3-backed lakeFS instance + repo/branch (LAKEFS_S3_API_URL must end in /api/v1 exactly once)
export LAKEFS_S3_API_URL=
export LAKEFS_S3_ACCESS_KEY=…
export LAKEFS_S3_SECRET_KEY=…
export LAKEFS_S3_TEST_REPO=
export LAKEFS_S3_TEST_BRANCH=

./gradlew test --tests '*LakeFSPhysicalPathS3Spec*'
```

Physical_path makes nf-amazon write the object straight to
that bucket and then links it into lakeFS, so `AWS_*` must be S3 creds with write access to it, while
`LAKEFS_S3_*` are the lakeFS API creds for the instance. Both sets are required; missing any var → the spec skips.

How the AWS creds are consumed: the `test` task maps `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_REGION`
into the test JVM's environment (from the env var, or from `-Paws.accessKey`/`aws.secretKey`/`aws.region`).
Two S3 clients then pick them up: the **write** goes through nf-amazon, which reads them from `Global.config.aws`
(the spec populates that block from these env vars); the **crc64nvme read-back** in `S3ChecksumReader` builds a
client with no explicit credentials, so it uses the **AWS SDK default credential chain** — i.e. the same env
vars. Net: set the three `AWS_*` values (env or `-P`) and both paths authenticate.

#### Targeting a different lakeFS instance (e.g. an S3-backed repo) without editing `gradle.properties`

The test task resolves each `LAKEFS_*` value from `project.findProperty('lakefs.*')` **first**, falling back to
the matching env var. So if `gradle.properties` already pins `lakefs.*` to one instance (e.g. GCS), exporting
`LAKEFS_*` env vars for another instance is **silently ignored** — you must override with `-P`, which outranks
`gradle.properties`. The `lakefs.*` keys are backend-agnostic (plain lakeFS API creds); only the lakeFS
instance changes. Example — run the signed_url round-trips against an S3-backed repo:

```bash
./gradlew test \
  --tests '*LakeFSNioSpec.should create a blob*' \
  --tests '*LakeFSNioSpec.should upload, copy and download a file' \
  -Plakefs.transferMode=signed_url \
  -Plakefs.testRepo=<s3-backed-repo> \
  -Plakefs.testBranch=<branch> \
  -Plakefs.apiUrl="$API_URL" \
  -Plakefs.accessKey="$ACCESS_KEY" \
  -Plakefs.secretKey="$SECRET_KEY"
```

To avoid secrets in your shell history / process list, feed them via command substitution from a creds file,
e.g. `-Plakefs.secretKey="$(grep secret_access_key ~/.lakectl_aws.yaml | sed -E 's/.*:[[:space:]]*//' | tr -d '\r')"`.
Note: the cross-branch copy test (`should upload, copy and download a file`) hard-codes a `Ron_test` source
branch — create it in the target repo first, or that one test will fail with a `NoSuchFileException`.
(For a self-contained S3 physical_path check that needs no `-P` juggling, prefer `LakeFSPhysicalPathS3Spec`
above.)

### Running Locally

#### Option 1: Using gradle.properties

Add the following to your `gradle.properties` file (already in `.gitignore`):

```properties
lakefs.accessKey=your-access-key
lakefs.secretKey=your-secret-key
lakefs.apiUrl=https://your-lakefs-instance.com/api/v1
lakefs.testRepo=your-test-repo
lakefs.testBranch=your-test-branch

# Optional (for physical_path transfer mode)
google.project=your-gcp-project
google.region=europe-west1
```

Then run:

```bash
./gradlew test
```

#### Option 2: Using environment variables

```bash
export LAKEFS_ACCESS_KEY="your-access-key"
export LAKEFS_SECRET_KEY="your-secret-key"
export LAKEFS_API_URL="https://your-lakefs-instance.com/api/v1"
export LAKEFS_TEST_REPO="your-test-repo"
export LAKEFS_TEST_BRANCH="your-test-branch"

./gradlew test
```

### Running on GitHub Actions

Integration tests only run manually via `workflow_dispatch`:

1. Go to the **Actions** tab in the GitHub repository
2. Select the **Test** workflow
3. Click **Run workflow**

Before running, ensure the following secrets are configured in your repository settings (**Settings → Secrets and variables → Actions**):

- `LAKEFS_ACCESS_KEY`
- `LAKEFS_SECRET_KEY`
- `LAKEFS_API_URL`
- `LAKEFS_TEST_REPO`
- `LAKEFS_TEST_BRANCH`
- `GOOGLE_PROJECT` (optional)
- `GOOGLE_REGION` (optional)
- `GOOGLE_EXT_BUCKET` (optional)
- `AWS_ACCESS_KEY_ID` (optional — S3 physical_path and ext-bucket linking tests)
- `AWS_SECRET_ACCESS_KEY` (optional)
- `AWS_REGION` (optional)
- `AWS_EXT_BUCKET` (optional — S3 ext-bucket linking tests in `LakeFSNioSpec`)
- `LAKEFS_S3_API_URL` (optional — `LakeFSPhysicalPathS3Spec`)
- `LAKEFS_S3_ACCESS_KEY` (optional)
- `LAKEFS_S3_SECRET_KEY` (optional)
- `LAKEFS_S3_TEST_REPO` (optional)
- `LAKEFS_S3_TEST_BRANCH` (optional)

## Test Categories

### Unit Tests

- `LakeFSSDKClientSpec` - Tests for the lakeFS SDK client wrapper (branch operations, etc.)
- `NextflowLakeFSFileSystemProviderSpec` - Tests for the file system provider (auto-create branch logic, etc.)
- `NextflowLakeFSPathSpec` - Tests for path parsing and manipulation
- `NextflowLakeFSPathFactorySpec` - Tests for path factory
- `S3ChecksumReaderParseSpec` - S3 address bucket/key parsing (covers nf-amazon's `s3:///bucket/key` form)

### Integration Tests

- `S3ChecksumReaderSpec` - Direct S3 `crc64nvme` read-back check (gated on `AWS_ACCESS_KEY_ID` + `AWS_S3_TEST_PREFIX`)
- `LakeFSPhysicalPathS3Spec` - End-to-end physical_path write/link/read against an S3-backed repo (gated on `AWS_*` + `LAKEFS_S3_*`)
- `LakeFSNioSpec` - Full integration tests requiring a running lakeFS instance
  - File operations (read, write, copy, move, delete)
  - Directory operations
  - Auto-create branch feature tests
  - Tag and commit ID support tests (tags are created/deleted dynamically during tests)
