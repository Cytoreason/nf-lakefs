package nextflow.lakefs

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Path

/**
 * Shared logic to commit the pending changes on the branch referenced by a `lakefs://` path.
 * Used both by the {@link LakeFSExtension#lakefsCommit} function and by {@link LakeFSObserver}'s
 * automatic commit on workflow completion.
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
class LakeFSCommitSupport {

    private static final Logger log = LoggerFactory.getLogger(LakeFSCommitSupport)

    /**
     * Commits the pending changes on the branch referenced by a lakefs:// path.
     *
     * @param path A `lakefs://repository/branch/...` URI identifying the repository and branch to commit.
     * @param message The commit message.
     * @return The created commit ID.
     */
    static String commit(String path, String message) {
        return commit(NextflowLakeFSPathFactory.create(path), message)
    }

    /**
     * Commits the pending changes on the branch referenced by a lakefs:// path.
     *
     * @param path A `NextflowLakeFSPath` identifying the repository and branch to commit.
     * @param message The commit message.
     * @return The created commit ID.
     */
    static String commit(Path path, String message) {
        if (!(path instanceof NextflowLakeFSPath))
            throw new IllegalArgumentException("Not a lakefs:// path: $path")
        final lakeFSPath = (NextflowLakeFSPath) path
        final provider = (NextflowLakeFSFileSystemProvider) lakeFSPath.getFileSystem().provider()
        final repository = lakeFSPath.repository()
        final branch = lakeFSPath.ref()
        log.info("Committing lakeFS repository '$repository' branch '$branch' with message: $message")
        return provider.lakeFSClient.commit(repository, branch, message)
    }
}
