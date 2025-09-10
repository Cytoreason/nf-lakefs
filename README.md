# Nextflow lakeFS Plugin (`nf-lakefs`)

[!build](https://github.com/<your-username>/nf-lakefs/actions/workflows/ci.yml)
[!Nextflow plugin](https://www.nextflow.io/docs/latest/plugins.html)
[!License: MIT](https://opensource.org/licenses/MIT)

This plugin integrates Nextflow with lakeFS, allowing you to use `lakefs://` URIs as inputs and outputs in your pipelines. It brings the power of data versioning, reproducibility, and atomic operations to your Nextflow workflows.

The plugin treats lakeFS repositories and branches as a native file system, enabling seamless data management without requiring manual pre-loading or post-processing steps.

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
}
```

### Best Practice: Using Secrets

For better security, it is highly recommended to store your `accessKey` and `secretKey` as environment variables or using Nextflow's secrets management.

```groovy
// nextflow.config
lakefs {
    apiUrl    = 'https://your-lakefs-server.example.com/api/v1'
    accessKey = params.LAKEFS_ACCESS_KEY
    secretKey = params.LAKEFS_SECRET_KEY
}
```

You can then provide the secrets when running your pipeline:
```groovy
export NXF_SECRET_LAKEFS_ACCESS_KEY='your-access-key'
export NXF_SECRET_LAKEFS_SECRET_KEY='your-secret-key'

nextflow run your-pipeline.nf
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
nextflow run main.nf -profile standard
```

After the run completes, the output file `line_count_result.txt` will be available in your lakeFS repository at `my-repo/main/path/to/output/`.

## Development

To contribute to this plugin, clone the repository and use the provided `Makefile` targets to build and test your changes.

**Prerequisites:**
*   Java (version 11 or later)
*   Nextflow

### Build the Plugin
This command compiles the code and packages it into a JAR file.
```shell
make assemble
# or ./gradlew assemble
```
### Run Unit Tests
Execute the plugin's unit tests.
```shell
make test
# or ./gradlew test
```
### Install Locally for Testing
Install the plugin to your local `~/.nextflow/plugins` directory for end-to-end testing with a real pipeline.
You can then run a test pipeline like this:
```shell
make install
# or ./gradlew install
```
### Clean the Project
Remove all build artifacts and temporary files.
```shell
make clean
```

### Publish a New Version
(For maintainers) To publish the plugin, you first need to configure your credentials. Create a file at `$HOME/.gradle/gradle.properties` and add your Nextflow Plugin Registry access token:

Then, run the release command. This will tag the release on GitHub and publish it to the Nextflow plugin registry.
```shell
make release
# or ./gradlew releasePlugin
```
## License

This project is licensed under the MIT License