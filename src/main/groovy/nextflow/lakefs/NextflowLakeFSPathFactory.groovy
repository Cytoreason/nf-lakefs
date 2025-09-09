package nextflow.lakefs


import nextflow.Global
import nextflow.file.FileHelper
import nextflow.file.FileSystemPathFactory
import nextflow.plugin.Priority
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Path
/**
 * Nextflow plugin factory for lakeFS file system.
 * This class is used by Nextflow's plugin system to register the lakeFS provider.
 */
@Priority(value = 100)
class NextflowLakeFSPathFactory extends FileSystemPathFactory {

    private static final Logger log = LoggerFactory.getLogger(NextflowLakeFSPathFactory.class)
    public static final String LAKEFS_CONFIG_ELEMENT = 'lakefs'

    static NextflowLakeFSPath create(String path) {
        if (!path) throw new IllegalArgumentException("Missing lakefs path argument")
        if (!path.startsWith('lakefs://')) throw new IllegalArgumentException("lakefs path must start with lakefs:// prefix -- offending value '$path'")
        // note: this URI constructor parse the path parameter and extract the `scheme` and `authority` components
        final uri = URI.create(path)
        return (NextflowLakeFSPath) FileHelper.getOrCreateFileSystemFor(uri, config()).provider().getPath(uri)
    }

    static private Map config() {
        final result = Global.config?.get(LAKEFS_CONFIG_ELEMENT) as Map
        return result != null ? result : Collections.emptyMap()
    }


    @Override
    Path parseUri(String uri) {
        if (uri.startsWith('lakefs://') && uri[9] != '/') {
//            final path = "lakefs://${uri.substring(9)}"
            return create(uri)
        }
        return null
    }

    @Override
    boolean equals(Object obj) {
        return obj instanceof NextflowLakeFSPathFactory
    }

    @Override
    int hashCode() {
        return getClass().hashCode()
    }

    @Override
    protected String toUriString(Path path) {
        return path instanceof NextflowLakeFSPath ? path.toUri().toString() : null
    }

    @Override
    protected String getBashLib(Path target) {
        return ""
    }

    @Override
    protected String getUploadCmd(String source, Path target) {
        return ""
    }
}