package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.config.schema.ConfigOption
import nextflow.config.schema.ConfigScope
import nextflow.config.schema.ScopeName

/**
 * Configuration scope for lakeFS plugin settings.
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
@ScopeName('lakefs')
class LakeFSConfig implements ConfigScope {

    @ConfigOption
    final String apiUrl

    @ConfigOption
    final String accessKey

    @ConfigOption
    final String secretKey

    @ConfigOption
    final String transferMode

    @ConfigOption
    final Boolean autoCreateBranch

    @ConfigOption
    final String autoCreateBranchSource

    @ConfigOption
    final String readTimeout

    @ConfigOption
    final String connectTimeout

    @ConfigOption
    final Boolean allowLinkingDifferentNamespace

    LakeFSConfig() {
        this.apiUrl = null
        this.accessKey = null
        this.secretKey = null
        this.transferMode = 'signed_url'
        this.autoCreateBranch = false
        this.autoCreateBranchSource = 'main'
        this.readTimeout = '60s'
        this.connectTimeout = '30s'
        this.allowLinkingDifferentNamespace = false
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
        this.allowLinkingDifferentNamespace = opts.allowLinkingDifferentNamespace as Boolean ?: false
    }
}
