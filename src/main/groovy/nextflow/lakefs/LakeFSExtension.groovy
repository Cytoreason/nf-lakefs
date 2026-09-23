package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint

/**
 * Exposes lakeFS operations as functions callable from a Nextflow pipeline script,
 * e.g. `lakefsCommit(params.output_dir, params.commit_message)`.
 *
 * For committing automatically once a workflow completes successfully, prefer setting
 * `lakefs.autoCommit = true` in `nextflow.config` instead (see {@link LakeFSObserverFactory}).
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
class LakeFSExtension extends PluginExtensionPoint {

    @Override
    protected void init(Session session) {
    }

    /**
     * Commits the pending changes on the branch referenced by a lakefs:// path.
     *
     * @param path A `lakefs://repository/branch/...` URI identifying the repository and branch to commit.
     * @param message The commit message.
     * @return The created commit ID.
     */
    @Function
    String lakefsCommit(String path, String message) {
        return LakeFSCommitSupport.commit(path, message)
    }
}
