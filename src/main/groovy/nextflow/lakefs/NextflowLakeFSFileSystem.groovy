package nextflow.lakefs


import groovy.transform.CompileStatic

import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchService
import java.nio.file.attribute.UserPrincipalLookupService

/**
 * LakeFS file system implementation
 */
@CompileStatic
class NextflowLakeFSFileSystem extends FileSystem {
    private final NextflowLakeFSFileSystemProvider provider
    private final URI uri
    final String repository
    final String ref
    private final NextflowLakeFSPath rootDirectory

    NextflowLakeFSFileSystem(NextflowLakeFSFileSystemProvider provider, URI uri, String repository, String ref) {
        this.provider = provider
        this.uri = uri
        this.repository = repository
        this.rootDirectory = new NextflowLakeFSPath(this, "")
        this.ref = ref
    }

    @Override
    NextflowLakeFSFileSystemProvider provider() {
        return provider
    }

    @Override
    void close() {
        // Nothing to close
    }

    @Override
    boolean isOpen() {
        return true
    }

    @Override
    boolean isReadOnly() {
        return false
    }

    @Override
    String getSeparator() {
        return "/"
    }

    @Override
    Iterable<Path> getRootDirectories() {
        return [rootDirectory] as Iterable<Path>
    }

    @Override
    Iterable<FileStore> getFileStores() {
        return []
    }

    @Override
    Set<String> supportedFileAttributeViews() {
        return ["basic"] as Set
    }

    @Override
    Path getPath(String first, String... more) {
        String path = first.startsWith("/") ? first.substring(1) : first
        if (more) {
            for (String segment : more) {
                if (segment) {
                    path += "/" + segment
                }
            }
        }

        if (!path) {
            return rootDirectory
        }

        return new NextflowLakeFSPath(this, path)
    }

    @Override
    PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("no path matcher")
//        final pos = syntaxAndPattern.indexOf(':')
//        if (pos <= 0 || pos == syntaxAndPattern.length() - 1) {
//            throw new IllegalArgumentException("Invalid path matcher syntax: $syntaxAndPattern")
//        }
//
//        final syntax = syntaxAndPattern.substring(0, pos)
//        final pattern = syntaxAndPattern.substring(pos + 1)
//
//        if (syntax != "glob" && syntax != "regex") {
//            throw new UnsupportedOperationException("Unsupported path matcher syntax: $syntax")
//        }
//
//        return FileHelper.getPathMatcherFor("($syntax)$pattern", this)
    }

    @Override
    UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("User principal lookup not supported")
    }

    @Override
    WatchService newWatchService() {
        throw new UnsupportedOperationException("Watch service not supported")
    }
}