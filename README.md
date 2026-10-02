# Nextflow lakeFS Plugin (`nf-lakefs`)

This plugin integrates Nextflow with lakeFS, allowing you to use `lakefs://` URIs as inputs and outputs in your pipelines. It brings the power of data versioning, reproducibility, and atomic operations to your Nextflow workflows.

The plugin treats lakeFS repositories and branches as a native file system, enabling seamless data management without requiring manual pre-loading or post-processing steps.

## Plugin availability

The nf-lakefs plugin is published and distributed via the official Nextflow plugin registry.

➡️ Registry page:
https://registry.nextflow.io/plugins/nf-lakefs

## Features

*   **Native URI Support:** Use `lakefs://<repo>/<branch>/<path>` URIs directly in your Nextflow scripts and `nextflow.config`.
*   **Read Inputs:** Stage input files from a lakeFS repository for use in your processes.
*   **Publish Outputs:** Publish output files from your processes directly to a lakefs repository branch.
*   **Transparent Authentication:** Securely configure your lakeFS credentials.

## Installation

To use the plugin, add the following to your `nextflow.config` file. Replace `<version>` with the desired plugin version (e.g., `0.1.0`).
```groovy 
plugins {
    id 'nf-lakefs@<version>'
}
```

## Configuration

After installation, you must configure the plugin with your lakeFS server details and credentials. This is also done in your `nextflow.config`.
In addition there are 2 modes of the plugin to read and write data from/to lakefs. 
1. signed_url - The plugin requests a signed url for the file he wants to consume from lakefs, or generates a cloud provider storage signed url to use for the write request of a new file.
2. physical_path - The plugin requests the cloud provider specific physical path (s3:// or gs:// for example) and delegates the request to the relevant nextflow plugin to process the read or write request. by that nextflow process should have the permissions to use the cloud provider specific api requests.
```groovy
lakefs {
    apiUrl    = 'https://your-lakefs-server.example.com/api/v1'
    accessKey = 'YOUR_LAKEFS_ACCESS_KEY'
    secretKey = 'YOUR_LAKEFS_SECRET_KEY'
    transferMode = 'signed_url'
    autoCreateBranch = false
    autoCreateBranchSource = 'main'
}
```

### Configuration Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `apiUrl` | Yes | - | lakeFS API endpoint URL (must end with `/api/v1`) |
| `accessKey` | Yes | - | lakeFS access key |
| `secretKey` | Yes | - | lakeFS secret key |
| `transferMode` | No | `signed_url` | Transfer mode: `signed_url` or `physical_path` |
| `autoCreateBranch` | No | `false` | Automatically create branch if it doesn't exist when writing |
| `autoCreateBranchSource` | No | `main` | Source branch to create new branches from |
| `readTimeout` | No | `60s` | HTTP read timeout for lakeFS API calls |
| `connectTimeout` | No | `30s` | HTTP connect timeout for lakeFS API calls |
| `allowedSchemaBucketsForLinking` | No | `[]` | Whitelist of cloud storage scheme+bucket prefixes (e.g. `s3://bucket`, `gs://bucket`) allowed as link sources when the source bucket differs from the repository's backing bucket. Only used with `physical_path` transfer mode. |
| `autoCommit` | No | `false` | Automatically commit the branch backing the workflow output directory once the workflow completes successfully. See [Committing Changes](#committing-changes). |
### Auto-Create Branch

When `autoCreateBranch` is enabled, the plugin will automatically create a new branch if it doesn't exist when you attempt to write to it. This is useful for workflows that dynamically create output branches.

```groovy
lakefs {
    apiUrl    = 'https://your-lakefs-server.example.com/api/v1'
    accessKey = 'YOUR_LAKEFS_ACCESS_KEY'
    secretKey = 'YOUR_LAKEFS_SECRET_KEY'
    autoCreateBranch = true
    autoCreateBranchSource = 'main'  // New branches will be created from 'main'
}
```

With this configuration, writing to `lakefs://my-repo/new-branch/file.txt` will automatically create `new-branch` from `main` if it doesn't already exist.

### allowedSchemaBucketForLinking

When using `java.nio.file.Files.createLink()` or when using Link mode on publishing, the plugin may need to determine whether two paths refer to the same underlying cloud storage backend .

The `allowedSchemaBucketForLinking` option allows you to explicitly whitelist cloud storage **scheme + bucket** combinations that are allowed to be treated as equivalent backends for linking as source for a target repository which has a namespace defined with different backed storage.

As an example you might have a repo lakefs://repo1 which has a namespace defined for gs://bucket-back so you can link files to this repo from ; 'gs://bucket1' and 'gs://bucket2' by setting :  
```groovy
lakefs {
    allowedSchemaBucketForLinking = [
            'gs://bucket1',
            'gs://bucket2'
    ]
}
```

The same applies to S3-backed repos:
```groovy
lakefs {
    allowedSchemaBucketForLinking = [
            's3://bucket1',
            's3://bucket2'
    ]
}
```

When using `physical_path` with an S3-backed lakeFS repository, also configure your AWS credentials in `nextflow.config`:
```groovy
aws {
    accessKey = '<AWS_ACCESS_KEY_ID>'
    secretKey = '<AWS_SECRET_ACCESS_KEY>'
    region    = 'eu-central-1'
}
```

## Usage Example

Once configured, you can use `lakefs://` URIs just like you would with `s3://` or `gs://`.

Here is a simple pipeline that reads a file from a lakeFS repository, processes it, and publishes the result back to the same repository.

**`main.nf`**
```groovy
params.input = 'lakefs://my-repo/main/path/to/**/config.yaml'
params.output_dir = 'lakefs://my-repo/output/path/to/'

process EXAMPLE_PROCESS {

    input:
    path my_file

    output:
    path 'result.txt'

    script:
    """
    echo "Processing ${my_file}..."
    cat "${my_file}" | wc -l > result.txt
    """
}

workflow {
    Channel.fromPath(params.input, type: 'file')
            | view
            | EXAMPLE_PROCESS
}
```

**Run the pipeline:**
```shell
nextflow run main.nf 
```

## Committing Changes

Writing files to a `lakefs://` path stages them on the branch, but they remain uncommitted until a commit is created.

### Automatic commit on workflow completion

Set `lakefs.autoCommit = true` and the plugin will automatically commit the branch backing the workflow's [output directory](https://www.nextflow.io/docs/latest/reference/config.html#outputdir) once the workflow completes successfully — no `workflow.onComplete` code needed in your pipeline:

```groovy
lakefs {
    apiUrl    = 'https://your-lakefs-server.example.com/api/v1'
    accessKey = 'YOUR_LAKEFS_ACCESS_KEY'
    secretKey = 'YOUR_LAKEFS_SECRET_KEY'
    autoCommit = true
}

outputDir = 'lakefs://my-repo/output-branch/path/to/'
```

The commit message is always taken from `params.lakefs_commit_message`, since — unlike the lakeFS connection settings above — it's specific to a single run rather than the environment, and so is naturally passed as a pipeline param on the command line rather than hardcoded in `nextflow.config`:

```shell
nextflow run main.nf --lakefs_commit_message "my commit message"
```

If `params.lakefs_commit_message` isn't provided, the plugin logs a warning as soon as the run starts, then a clear error once the workflow finishes, and no commit is attempted. If the workflow doesn't complete successfully, or the output directory isn't a `lakefs://` path, no commit is attempted either.

### Committing manually

The plugin also exposes a `lakefsCommit(path, message)` function that pipelines can call directly, for finer-grained control (e.g. committing multiple branches):

```groovy
workflow.onComplete {
    if (workflow.success) {
        lakefsCommit(params.output_dir, params.lakefs_commit_message)
    }
}
```

`path` can be any `lakefs://<repo>/<branch>/...` URI on the branch you want to commit; only the repository and branch components are used. `lakefsCommit` returns the created commit ID.

## Support & contributions

This Nextflow plugin is currently published for **read-only usage**.
