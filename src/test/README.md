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

These are only needed when running tests with `physical_path` transfer mode, which requires the user to be authenticated with the underlying storage (e.g., GCS):

| Variable            | Description         | Default |
|---------------------|---------------------|---------|
| `GOOGLE_PROJECT`    | GCP project ID      | `""` |
| `GOOGLE_REGION`     | GCP region          | `europe-west1` |
| `GOOGLE_EXT_BUCKET` | GCS external bucket | `""` |

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

## Test Categories

### Unit Tests

- `LakeFSSDKClientSpec` - Tests for the lakeFS SDK client wrapper (branch operations, etc.)
- `NextflowLakeFSFileSystemProviderSpec` - Tests for the file system provider (auto-create branch logic, etc.)
- `NextflowLakeFSPathSpec` - Tests for path parsing and manipulation
- `NextflowLakeFSPathFactorySpec` - Tests for path factory

### Integration Tests

- `LakeFSNioSpec` - Full integration tests requiring a running lakeFS instance
  - File operations (read, write, copy, move, delete)
  - Directory operations
  - Auto-create branch feature tests
  - Tag and commit ID support tests (tags are created/deleted dynamically during tests)