package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * Configuration scope for lakeFS plugin settings.
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
@ScopeName('lakefs')
@Description('''
    The `lakefs` scope allows you to configure the `nf-lakefs` plugin.
''')
class LakeFSConfig implements ConfigScope {

    @ConfigOption
    @Description('lakeFS API endpoint URL, e.g. https://your-lakefs-server.example.com/api/v1')
    final String apiUrl

    @ConfigOption
    @Description('lakeFS access key')
    final String accessKey

    @ConfigOption
    @Description('lakeFS secret key')
    final String secretKey

    @ConfigOption
    @Description('Transfer mode: `signed_url` (default) or `physical_path`. Use `physical_path` for zero-copy linking and direct cloud-provider transfers (requires the matching nf-amazon or nf-google plugin).')
    final String transferMode

    @ConfigOption
    @Description('Automatically create the branch if it does not exist when writing to it (default: false)')
    final Boolean autoCreateBranch

    @ConfigOption
    @Description('Source branch to use when auto-creating a new branch (default: main)')
    final String autoCreateBranchSource

    @ConfigOption
    @Description('HTTP read timeout for lakeFS API calls (default: 60s)')
    final String readTimeout

    @ConfigOption
    @Description('HTTP connect timeout for lakeFS API calls (default: 30s)')
    final String connectTimeout

    @ConfigOption
    @Description('Whitelist of cloud storage scheme+bucket prefixes (e.g. s3://my-bucket, gs://my-bucket) that are allowed as link sources when the source bucket differs from the repository\'s backing bucket. Only used with `physical_path` transfer mode.')
    final List<String> allowedSchemaBucketsForLinking

    LakeFSConfig() {
        this.apiUrl = null
        this.accessKey = null
        this.secretKey = null
        this.transferMode = 'signed_url'
        this.autoCreateBranch = false
        this.autoCreateBranchSource = 'main'
        this.readTimeout = '60s'
        this.connectTimeout = '30s'
        this.allowedSchemaBucketsForLinking = []
    }

    LakeFSConfig(Map opts, Map env = [:]) {
        this.apiUrl = opts.apiUrl ?: env.get('LAKEFS_API_URL')
        this.accessKey = opts.accessKey ?: env.get('LAKEFS_ACCESS_KEY')
        this.secretKey = opts.secretKey ?: env.get('LAKEFS_SECRET_KEY')
        this.transferMode = opts.transferMode ?: 'signed_url'
        this.autoCreateBranch = opts.autoCreateBranch as Boolean ?: false
        this.autoCreateBranchSource = opts.autoCreateBranchSource ?: 'main'
        this.readTimeout = opts.readTimeout ?: '60s'
        this.connectTimeout = opts.connectTimeout ?: '30s'
        def raw = opts.allowedSchemaBucketsForLinking
        this.allowedSchemaBucketsForLinking =
                raw instanceof List ? raw :
                        raw ? [raw.toString()] : []
    }
}
