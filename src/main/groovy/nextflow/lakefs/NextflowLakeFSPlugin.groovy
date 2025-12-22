
package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.file.FileHelper
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * Implements the NextflowLakeFSPlugin plugins entry point
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
class NextflowLakeFSPlugin extends BasePlugin {

    NextflowLakeFSPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

    @Override
    void start() {
        super.start()
        FileHelper.getOrInstallProvider(NextflowLakeFSFileSystemProvider)
    }
}
