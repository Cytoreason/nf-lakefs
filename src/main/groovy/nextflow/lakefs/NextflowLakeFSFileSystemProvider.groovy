package nextflow.lakefs

import com.google.common.base.Preconditions
import groovy.transform.CompileStatic
import io.lakefs.clients.sdk.model.ObjectStats
import io.lakefs.clients.sdk.model.StagingLocation
import io.lakefs.clients.sdk.model.StagingMetadata
import nextflow.extension.FilesEx
import nextflow.file.CopyMoveHelper
import nextflow.file.FileHelper
import nextflow.file.FileSystemTransferAware
import org.eclipse.jgit.errors.NotSupportedException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.NonReadableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.TimeUnit

import static java.lang.String.format

/**
 * FileSystemProvider implementation for lakeFS.
 * This allows Nextflow workflows to use lakeFS repositories as a storage backend.
 *
 * URI format: lakefs://repository/ref/path/to/object
 * Examples:
 *   - lakefs://my-repo/main/data/file.txt
 *   - lakefs://my-repo/branch-1/results/output.csv*/
class NextflowLakeFSFileSystemProvider extends FileSystemProvider implements FileSystemTransferAware {

    private static final Logger log = LoggerFactory.getLogger(NextflowLakeFSFileSystemProvider.class)

    private static final String SCHEME = 'lakefs'
    private final Map<String, NextflowLakeFSFileSystem> fileSystems = [:]
    protected LakeFSSDKClient lakeFSClient
    TransferMode transferMode
    boolean autoCreateBranch = false
    String autoCreateBranchSource = 'main'
    boolean allowLinkingDifferentNamespace = false

    @Override
    boolean canUpload(Path source, Path target) {
        return true
    }

    @Override
    boolean canDownload(Path source, Path target) {
        return false
    }

    @Deprecated(since = "0.2.0")
    // probably not needed as the nio implementation works ok
    @Override
    void download(Path remoteFile, Path localDestination, CopyOption... options) throws IOException {
        final NextflowLakeFSPath lakeFSPath = (NextflowLakeFSPath) remoteFile

        lakeFSClient
                .listObjects(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath, transferMode.presign)
                .getResults()
                .forEach {
                    log.debug(it.toString())
                    if (it.pathType == ObjectStats.PathTypeEnum.OBJECT) {
                        def path = FileHelper.asPath(it.physicalAddress)
                        log.debug("storing file " + path + " in " + localDestination)
                        FilesEx.copyTo(path, localDestination)
                    } else {
                        CopyMoveHelper.copyToForeignTarget(lakeFSPath, localDestination, options)
                    }
                }
    }

    @SuppressWarnings('GroovyUnusedAssignment')
    @Override
    void upload(Path source, Path remoteDestination, CopyOption... options) throws IOException {
        final NextflowLakeFSPath lakeFSTarget = (NextflowLakeFSPath) remoteDestination

        def isSourceDirectory = false
        try {
            isSourceDirectory = Files.isDirectory(source)
        } catch (Throwable ignored) {
            // default to false if we can't determine
        }
        if (isSourceDirectory) {
            log.debug("******** staging directory " + lakeFSTarget + " to remote " + remoteDestination.toString())
            CopyMoveHelper.copyDirectory(source, remoteDestination, options)
        } else {
            lakeFSTarget.setCachedAttributes(null)//clear attributes as this might change
            def stagingLocation = lakeFSClient.getStagingLocation(lakeFSTarget.repository(), lakeFSTarget.ref(), lakeFSTarget.objectPath, transferMode.presign)

            log.debug("******** staging " + lakeFSTarget + " to " + stagingLocation.physicalAddress.toString())
            switch (transferMode) {
                case TransferMode.signedURL:
                    def conn = SignedUrlWriteOnlyChannel.createHttpConnection(stagingLocation)
                    try (OutputStream os = conn.getOutputStream()) {
                        Files.copy(source, os)  // 👈 directly streams file into request
                    }
                    def flatHeaders = SignedUrlWriteOnlyChannel.getUploadHttpHeadersAndCloseConnection(conn, stagingLocation)
                    linkLakeFSToBackendFile(flatHeaders, lakeFSTarget, stagingLocation)
                    break
                case TransferMode.physicalPath:
                    def cloudStoragePhysicalPath = FileHelper.asPath(stagingLocation.physicalAddress)

                    def targetPhysicalPath = FilesEx.copyTo(source, cloudStoragePhysicalPath)
                    log.debug("******** target cloudStoragePhysicalPath " + targetPhysicalPath + "was created from " + source)
                    linkLakeFSToBackendFile(targetPhysicalPath, lakeFSTarget, stagingLocation)
                    break
                default: throw new RuntimeException("only signed url and physical path are supported")
            }
        }
    }

    private boolean isSameBackendStorage(Path source, String storageNamespace) {
        try {
            def sourceUri = source.toUri()
            def storageUri = URI.create(storageNamespace)
            if (sourceUri.scheme != storageUri.scheme) {
                return false
            }
            if (!allowLinkingDifferentNamespace) {
                return sourceUri.authority == storageUri.authority
            }
            return true
        } catch (Throwable t) {
            return false
        }
    }

    private void linkLakeFSToBackendFile(Object physicalPathAttributesHolder, NextflowLakeFSPath lakeFSTarget, StagingLocation stagingLocation) {

        Map<String, String> tags = Optional.ofNullable(lakeFSTarget.tags)
                .orElse(Collections.emptyMap())

        tags = tags.collectEntries { k, v -> [(k.toString()): v.toString()] // needed to convert from GString type to string
        }
        log.trace("tags " + lakeFSTarget.repository() + " " + lakeFSTarget.ref() + " " + lakeFSTarget.objectPath + " " + tags)

        def objectProperties = CloudProvidersSpecificFactories.getLinkMetadata(physicalPathAttributesHolder)

        def stagingMetadata = new StagingMetadata()
        stagingMetadata.setUserMetadata(tags)
        stagingMetadata.setStaging(stagingLocation)
        stagingMetadata.setChecksum(objectProperties.checksum as String)
        stagingMetadata.setSizeBytes(objectProperties.size)
        def objectStats = lakeFSClient.linkPhysicalAddress(lakeFSTarget.repository(), lakeFSTarget.ref(), lakeFSTarget.objectPath,
                stagingMetadata)
        log.trace(objectStats)
    }

    @Override
    String getScheme() {
        return SCHEME
    }

    /**
     * LakeFS file attributes implementation*/
    @CompileStatic
    class LakeFSFileAttributes implements BasicFileAttributes {
        private final boolean directory
        private final long size
        private final FileTime lastModifiedTime
        private final Object fileKey

        LakeFSFileAttributes(boolean directory, long size, FileTime lastModified, Object fileKey) {
            this.directory = directory
            this.size = size
            this.lastModifiedTime = lastModified
            this.fileKey = fileKey
        }

        @Override
        FileTime lastModifiedTime() {
            return lastModifiedTime
        }

        @Override
        FileTime lastAccessTime() {
            return lastModifiedTime
        }

        @Override
        FileTime creationTime() {
            return lastModifiedTime
        }

        @Override
        boolean isRegularFile() {
            return !directory
        }

        @Override
        boolean isDirectory() {
            return directory
        }

        @Override
        boolean isSymbolicLink() {
            return false
        }

        @Override
        boolean isOther() {
            return false
        }

        @Override
        long size() {
            return size
        }

        @Override
        Object fileKey() {
            return fileKey
        }
    }


    /**
     * Initialize the lakeFS provider with connection details
     *
     * @param apiUrl lakeFS API endpoint
     * @param accessKey lakeFS access key
     * @param secretKey lakeFS secret key
     */
    protected static void init(Map lakefsConfig) {

        var apiUrl = lakefsConfig.apiUrl as String
        var accessKey = lakefsConfig.accessKey as String
        var secretKey = lakefsConfig.secretKey as String
        def mode = TransferMode.fromString(lakefsConfig.transferMode)

        if (!apiUrl) throw new IllegalStateException("Missing lakeFS apiUrl configuration")
        if (!accessKey) throw new IllegalStateException("Missing lakeFS accessKey configuration")
        if (!secretKey) throw new IllegalStateException("Missing lakeFS secretKey configuration")
        if (lakefsConfig.transferMode && !mode) throw new IllegalStateException("${lakefsConfig.transferMode} is not a valid lakeFS transfer mode use ${TransferMode.values().collect { it.configName }.join(",")}")

        // Remove trailing slash if present
        if (apiUrl.endsWith('/')) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1)
        }

        log.debug "Created lakeFS file system provider | apiUrl=$apiUrl"
    }

    @Override
    void createLink(Path lakefsLink, Path existingSource) throws IOException {
        final NextflowLakeFSPath lakeFSTarget = (NextflowLakeFSPath) lakefsLink
        String storageNamespace = lakeFSClient.getRepositoryStorageNamespace(lakeFSTarget.repository())
        def sourcePhysicalPath = existingSource

        if (existingSource instanceof NextflowLakeFSPath) {
            // todo ron >> lakefs source is not well supported yet
            //  because the lakefs backend storage fails when used as staged backend
            def lakefsSource = (NextflowLakeFSPath) existingSource
            final sourceRepo = lakefsSource.repository()
            final sourceRef = lakefsSource.ref()
            final sourcePath = lakefsSource.objectPath
            def sourceObjectStats = lakeFSClient.getPathStats(sourceRepo, sourceRef, sourcePath, false)
            sourcePhysicalPath = FileHelper.asPath(sourceObjectStats.physicalAddress)
            log.debug("trying to link file " + sourcePhysicalPath.toUriString() + " to " + lakeFSTarget)
        }
        if (isSameBackendStorage(sourcePhysicalPath, storageNamespace)) {
            log.debug("******** linking physical " + sourcePhysicalPath + " to " + lakeFSTarget)
            def stagingLocation = new StagingLocation()
            stagingLocation.physicalAddress = sourcePhysicalPath.toUri().toASCIIString()
            linkLakeFSToBackendFile(sourcePhysicalPath, lakeFSTarget, stagingLocation)
        } else {
            log.warn("******** failure on request linking " + sourcePhysicalPath + " to " + lakeFSTarget + " since backend storage is not the same")
        }
    }

    @Override
    FileSystem newFileSystem(URI uri, Map<String, ?> lakeFSConfig) throws IOException {
        init(lakeFSConfig)

        def repoAndRef = getRepoRefAndPath(uri)
        def fileSystemKey = getFileSystemKey(repoAndRef.repository, repoAndRef.ref)
        synchronized (fileSystems) {
            def fs = fileSystems.get(fileSystemKey)
            if (fs) return fs

            // Verify repository exists
//            verifyRepository(repository)

            transferMode = TransferMode.fromString(lakeFSConfig.transferMode, TransferMode.signedURL)
            autoCreateBranch = lakeFSConfig.autoCreateBranch ?: false
            autoCreateBranchSource = lakeFSConfig.autoCreateBranchSource ?: 'main'
            allowLinkingDifferentNamespace = lakeFSConfig.allowLinkingDifferentNamespace ?: false
            lakeFSClient = new LakeFSSDKClient(lakeFSConfig)

            // Check/create ref once at file system creation
            ensureRefExists(repoAndRef.repository, repoAndRef.ref)

            fs = new NextflowLakeFSFileSystem(this, uri, repoAndRef.repository, repoAndRef.ref)
            fileSystems.put(fileSystemKey, fs)
            return fs
        }
    }

    static def getRepoRefAndPath(URI uri) {
        final path = uri.path?.toString()

        if (!path) throw new IllegalArgumentException("Missing path component in lakeFS URI: $uri")

        // Parse URI format: lakefs://repo/ref/path/to/object
        final repository = uri.authority
        if (!repository) throw new IllegalArgumentException("Missing lakeFS repository name in URI: $uri")


        // First path component is ref (branch/tag/commit), rest is the path within that ref
        final pathStr = path.startsWith('/') ? path.substring(1) : path
        final parts = pathStr.split('/', 2)

        if (parts.length == 0 || parts[0].isEmpty()) throw new IllegalArgumentException("Missing lakeFS branch/reference name in URI: $uri")


        final ref = parts[0]
        // Get the file system

        final objectPath = parts.length > 1 ? parts[1] : ''

        [repository: repository, ref: ref, objectPath: objectPath]
    }

    static String getFileSystemKey(String repository, String ref) {
        "${repository}/${ref}".toString()
    }

    /**
     * Ensures the reference exists when file system is created.
     * The ref can be a branch, tag, or commit ID.
     * If autoCreateBranch is enabled and the ref doesn't exist at all, creates it as a branch from the source branch.
     * @throws NoSuchFileException if ref doesn't exist and autoCreateBranch is disabled
     * @throws IllegalArgumentException if autoCreateBranchSource is a tag (tags cannot be used as branch source)
     */
    protected void ensureRefExists(String repository, String ref) {
        // First check if the ref exists as any type (branch, tag, or commit)
        if (lakeFSClient.refExists(repository, ref)) {
            log.debug("Reference '$ref' exists in repository '$repository'")
            return
        }

        // Ref doesn't exist at all - try to create as branch if auto-create is enabled
        if (autoCreateBranch) {
            // Validate that autoCreateBranchSource is not a tag
            validateAutoCreateBranchSource(repository)

            log.info("Reference '$ref' does not exist in repository '$repository', auto-creating branch from '$autoCreateBranchSource'")
            lakeFSClient.createBranch(repository, ref, autoCreateBranchSource)
        } else {
            throw new NoSuchFileException("Reference '$ref' does not exist in repository '$repository'. Enable 'autoCreateBranch' in lakefs config to auto-create branches.")
        }
    }

    /**
     * Validates that autoCreateBranchSource is a valid branch (not a tag).
     * Tags are immutable references and cannot be used as source for new branches in the same way branches can.
     * @throws IllegalArgumentException if autoCreateBranchSource is a tag
     * @throws NoSuchFileException if autoCreateBranchSource does not exist
     */
    protected void validateAutoCreateBranchSource(String repository) {
        // Check if the source is a tag - tags should not be used as branch source
        if (lakeFSClient.tagExists(repository, autoCreateBranchSource)) {
            throw new IllegalArgumentException(
                    "Invalid 'autoCreateBranchSource' configuration: '$autoCreateBranchSource' is a tag, not a branch. " +
                            "Tags are immutable references and should not be used as the source for auto-creating branches. " +
                            "Please specify a branch name in your lakefs.autoCreateBranchSource configuration."
            )
        }

        // Check if the source branch exists
        if (!lakeFSClient.branchExists(repository, autoCreateBranchSource)) {
            throw new NoSuchFileException(
                    "Invalid 'autoCreateBranchSource' configuration: branch '$autoCreateBranchSource' does not exist in repository '$repository'. " +
                            "Please specify an existing branch name in your lakefs.autoCreateBranchSource configuration."
            )
        }
    }


    @Override
    FileSystem getFileSystem(URI uri) {
        synchronized (fileSystems) {
            def repoAndRef = getRepoRefAndPath(uri)
            def result = fileSystems.get(getFileSystemKey(repoAndRef.repository, repoAndRef.ref))
            if (result == null) {
                throw new FileSystemNotFoundException("LakeFS file system not found for URI: $uri")
            }
            return result
        }
    }

    @Override
    Path getPath(URI uri) {
        def fileSystem = (NextflowLakeFSFileSystem) getFileSystem(uri)
        def repoRefAndPath = getRepoRefAndPath(uri)
        return new NextflowLakeFSPath(fileSystem, repoRefAndPath.objectPath)
    }

/**
 * Get object statistics*/

    @Override
    SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        final NextflowLakeFSPath lakeFSPath = (NextflowLakeFSPath) path
        lakeFSPath.setCachedAttributes(null)
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.CREATE) || options.contains(StandardOpenOption.CREATE_NEW) || options.contains(StandardOpenOption.APPEND)) {
            def stagingLocation = lakeFSClient.getStagingLocation(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath, transferMode.presign)

            switch (transferMode) {
                case TransferMode.signedURL:
                    return new SignedUrlWriteOnlyChannel(lakeFSPath, stagingLocation)
                    break
                case TransferMode.physicalPath:
                    return new LakeFSCloudProviderDelegateByteChannel(lakeFSPath, stagingLocation, options, attrs)
                    break
                default: throw new RuntimeException("only signed url and physical path are supported")
            }
        } else {
            def stats = lakeFSClient.getPathStats(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath, transferMode.presign)
            def physicalPath = FileHelper.asPath(stats.physicalAddress)
            return physicalPath.getFileSystem().provider().newByteChannel(physicalPath, options, attrs)
        }
    }

    @Override
    DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        final NextflowLakeFSPath lakeFSPath = (NextflowLakeFSPath) dir
        Iterator<NextflowLakeFSPath> lakeFSPathStream = lakeFSClient
                .listObjects(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath)
                .getResults()
                .stream()
                .map(objectSummary -> {
                    final String key = objectSummary.getPath()
                    log.debug("key " + key)
                    def uri = new URI("${scheme}://${lakeFSPath.repository()}/${lakeFSPath.ref()}")
                    final NextflowLakeFSPath path = new NextflowLakeFSPath((NextflowLakeFSFileSystem) getFileSystem(uri), key)
                    return path
                }).iterator()

        return new DirectoryStream<Path>() {

            @Override
            Iterator<Path> iterator() {
                return lakeFSPathStream
            }

            @Override
            void close() {}

        }
    }

    @Override
    void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        //todo ron >> not sure we need this yet
    }

    @Override
    void delete(Path path) {
        final lakeFSPath = (NextflowLakeFSPath) path
        lakeFSClient.deleteObject(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath)
// todo ron >> not handling directory delete yet
//        final repository = (((LakeFSFileSystem)) lakeFSPath.fileSystem).repository
//        final ref = lakeFSPath.ref
//        final objectPath = lakeFSPath.objectPath
//
//        if (objectPath.isEmpty()) {
//            throw new IOException("Cannot delete ref root: $path")
//        }
//
//        if (isDirectory(path)) {
//            // Delete all objects under this prefix
//            deleteObjects(repository, ref, objectPath)
//        } else {
//            // Delete single object
//            deleteObject(repository, ref, objectPath)
//        }
    }

    @Override
    void copy(Path source, Path target, CopyOption... options) {
        if (source instanceof NextflowLakeFSPath) {
            def lakefsSource = (NextflowLakeFSPath) source
            final sourceRepo = lakefsSource.repository()
            final sourceRef = lakefsSource.ref()
            final sourcePath = lakefsSource.objectPath
            if (isDirectory(source)) {
                upload(source, target, options)
            } else {
                def sourceObjectStats = lakeFSClient.getPathStats(sourceRepo, sourceRef, sourcePath, transferMode.presign)
                def sourcePhysicalPath = FileHelper.asPath(sourceObjectStats.physicalAddress)
                log.debug("copying file " + sourcePhysicalPath + " in " + target)

                upload(sourcePhysicalPath, target, options)
            }
        }
        if (target instanceof NextflowLakeFSPath) {
            upload(source, target, options)
        }
    }


    @Override
    void move(Path source, Path target, CopyOption... options) {
        //todo ron >> this is probably implemented already in upload/download
//        copy(source, target, options)
//        delete(source)
    }

    @Override
    boolean isSameFile(Path path, Path path2) {
        if (path == path2) return true
        if (!(path instanceof NextflowLakeFSPath && path2 instanceof NextflowLakeFSPath)) return false

        NextflowLakeFSPath lp1 = (NextflowLakeFSPath) path
        NextflowLakeFSPath lp2 = (NextflowLakeFSPath) path2

        return lp1.repository() == lp2.repository() && lp1.ref() == lp2.ref() && lp1.objectPath == lp2.objectPath
    }

    @Override
    boolean isHidden(Path path) {
        return false
    }

    @Override
    FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException("getFileStore not supported by lakeFS provider")
    }

    @Override
    void checkAccess(Path path, AccessMode... modes) {
//        final lakeFSPath = (NextflowLakeFSPath) path
//        final repository = lakeFSPath.repository()
//        final reference = lakeFSPath.ref()
//        final objectPath = lakeFSPath.objectPath

//        // Check if reference exists
//        if (!checkRefExists(repository, ref)) {
//            throw new NoSuchFileException("Reference does not exist: $ref in repository $repository")
//        }

        // Root of reference always exists
//        if (objectPath.isEmpty()) {
//            return
//        }

        // Check if object exists
//        if (!objectExists(repository, ref, objectPath) && !isDirectory(path)) {
//            throw new NoSuchFileException("$path")
//        }

//        for (AccessMode mode : modes) {
//            if (mode == AccessMode.WRITE) {
//                // Check if writable - depends on branch status
//                if (!isBranchWritable(repository, ref)) {
//                    throw new AccessDeniedException("$path is not writable (reference $ref might be a tag or commit)")
//                }
//            }
//        }
    }

    @Override
    <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        Preconditions.checkArgument(path instanceof NextflowLakeFSPath,
                "path must be an instance of %s", NextflowLakeFSPath.class.getName())
        NextflowLakeFSPath lakeFSPath = (NextflowLakeFSPath) path
        if (type.isAssignableFrom(BasicFileAttributeView.class)) {
            try {
                //noinspection unchecked
                return (V) new BasicFileAttributeView() {
                    @Override
                    String name() {
                        return "basic"
                    }

                    @Override
                    BasicFileAttributes readAttributes() throws IOException {
                        return readAttr0(lakeFSPath)
                    }

                    @Override
                    void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable read attributes for file: " + FilesEx.toUriString(lakeFSPath), e)
            }
        }
        return null
//        throw new UnsupportedOperationException("Not a valid lakefs file system provider file attribute view: " + type.getName())
    }


    @Override
    Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        // This method should parse the attributes string and return the requested attributes
        // For simplicity, we'll just return all basic attributes
        BasicFileAttributes attrs = readAttributes(path, LakeFSFileAttributes.class, options)
        Map<String, Object> result = new HashMap<>()

        result.put("lastModifiedTime", attrs.lastModifiedTime())
        result.put("lastAccessTime", attrs.lastAccessTime())
        result.put("creationTime", attrs.creationTime())
        result.put("size", attrs.size())
        result.put("isRegularFile", attrs.isRegularFile())
        result.put("isDirectory", attrs.isDirectory())
        result.put("isSymbolicLink", attrs.isSymbolicLink())
        result.put("isOther", attrs.isOther())

        return result
    }

    @Override
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        Preconditions.checkArgument(path instanceof NextflowLakeFSPath,
                "path must be an instance of %s", NextflowLakeFSPath.class.getName())
        NextflowLakeFSPath lakeFSPath = (NextflowLakeFSPath) path
        if (type.isAssignableFrom(LakeFSFileAttributes.class)) {
            //noinspection unchecked
            return (A) readAttr0(lakeFSPath)
        }
        // not support attribute class
        throw new UnsupportedOperationException(format("only %s supported", BasicFileAttributes.class))
    }

    private LakeFSFileAttributes readAttr0(NextflowLakeFSPath lakeFSPath) throws IOException {
        def cachedAttributes = lakeFSPath.getCachedAttributes()
        if (cachedAttributes) return cachedAttributes
        ObjectStats objectSummary = lakeFSClient.getPathStats(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath)

        // parse the data to BasicFileAttributes.
        FileTime lastModifiedTime = FileTime.from(objectSummary.getMtime(), TimeUnit.MILLISECONDS)

        Long size = Optional.ofNullable(objectSummary.getSizeBytes()).orElse(0L)

        boolean directory = ObjectStats.PathTypeEnum.OBJECT != objectSummary.getPathType()


        def attributes = new LakeFSFileAttributes(directory, size, lastModifiedTime, lakeFSPath.objectPath)
        lakeFSPath.setCachedAttributes(attributes)
        return attributes
    }

    @Override
    void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new UnsupportedOperationException("setAttribute not supported by lakeFS provider")
    }


/**
 * Check if path refers to a directory*/
    boolean isDirectory(Path path) {
        try {
            BasicFileAttributes attrs = readAttributes(path, BasicFileAttributes.class)
            return attrs.isDirectory()
        } catch (Exception ignored) {
            return false
        }
    }

    @Override
    boolean exists(Path path, LinkOption... options) {
        if (path instanceof NextflowLakeFSPath) {
            NextflowLakeFSPath lakeFSPath = (NextflowLakeFSPath) path
            try {
                if (lakeFSPath.objectPath.isEmpty() || lakeFSPath.objectPath.endsWith("/")) {
                    lakeFSClient.getPathStats(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath)
                } else {
                    lakeFSClient.headObject(lakeFSPath.repository(), lakeFSPath.ref(), lakeFSPath.objectPath)
                }
                return true
            } catch (Exception ignore) {
                return false
            }
        }
        return false
    }

    class LakeFSCloudProviderDelegateByteChannel implements SeekableByteChannel {

        private final SeekableByteChannel channel
        Path physicalPath
        NextflowLakeFSPath lakeFSPath
        StagingLocation stagingLocation

        LakeFSCloudProviderDelegateByteChannel(NextflowLakeFSPath lakeFSPath, StagingLocation stagingLocation, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
            this.physicalPath = FileHelper.asPath(stagingLocation.physicalAddress)
            this.channel = physicalPath.getFileSystem().provider().newByteChannel(physicalPath, options, attrs)
            this.lakeFSPath = lakeFSPath
            this.stagingLocation = stagingLocation
        }

        @Override
        int read(ByteBuffer dst) throws IOException {
            channel.read(dst)
        }

        @Override
        int write(ByteBuffer src) throws IOException {
            return channel.write(src)
        }

        @Override
        long position() throws IOException {
            return channel.position()
        }

        @Override
        SeekableByteChannel position(long newPosition) throws IOException {
            return channel.position(newPosition)
        }

        @Override
        long size() throws IOException {
            return channel.size()
        }

        @Override
        SeekableByteChannel truncate(long size) throws IOException {
            return channel.truncate(size)
        }

        @Override
        boolean isOpen() {
            return channel.isOpen()
        }

        @Override
        void close() throws IOException {
            channel.close()
            linkLakeFSToBackendFile(physicalPath, lakeFSPath, stagingLocation)
        }
    }

    class SignedUrlWriteOnlyChannel implements SeekableByteChannel {
        private final WritableByteChannel delegate
        private final HttpURLConnection conn
        private boolean open = true
        private long position = 0
        NextflowLakeFSPath lakeFSPath
        StagingLocation stagingLocation


        SignedUrlWriteOnlyChannel(NextflowLakeFSPath lakeFSPath, StagingLocation stagingLocation) throws IOException {
            this.lakeFSPath = lakeFSPath
            this.stagingLocation = stagingLocation
            conn = createHttpConnection(stagingLocation)

            OutputStream os = conn.getOutputStream()
            this.delegate = Channels.newChannel(os)
        }

        static HttpURLConnection createHttpConnection(StagingLocation stagingLocation) {
            URL url = new URL(stagingLocation.presignedUrl)

            def conn = (HttpURLConnection) url.openConnection()
            conn.setDoOutput(true)
            conn.setRequestMethod("PUT")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn
        }

        @Override
        int write(ByteBuffer src) throws IOException {
            int written = delegate.write(src)
            position += written
            return written
        }

        @Override
        int read(ByteBuffer dst) throws IOException {
            throw new NonReadableChannelException()
        }

        @Override
        long position() { return position }

        @Override
        SeekableByteChannel position(long newPosition) {
            throw new UnsupportedOperationException("Cannot seek in HTTP PUT stream")
        }

        @Override
        long size() { return position }

        @Override
        SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException("Cannot truncate HTTP PUT stream")
        }

        @Override
        boolean isOpen() { return open }

        @Override
        void close() throws IOException {
            delegate.close()
            Map<String, ?> flatHeaders = getUploadHttpHeadersAndCloseConnection(conn, stagingLocation)
            linkLakeFSToBackendFile(flatHeaders, lakeFSPath, stagingLocation)
            open = false
        }

        static Map<String, ?> getUploadHttpHeadersAndCloseConnection(conn, stagingLocation) {
            int responseCode = conn.getResponseCode()
            if (responseCode / 100 != 2) {
                throw new IOException("Upload failed, response code " + responseCode)
            }
            conn.disconnect()

            def headerFields = conn.getHeaderFields()
            def flatHeaders = headerFields.collectEntries { key, values -> [(key ?: "Status"): values.join("; ")]
            } + [physicalAddress: stagingLocation.physicalAddress]
            flatHeaders
        }
    }

    class CloudProvidersSpecificFactories {

        static getLinkMetadata(Object objectAttributesHolder) {
            if (Path.isAssignableFrom(objectAttributesHolder.getClass())) {
                def targetPhysicalPath = ((Path) objectAttributesHolder)
                def provider = targetPhysicalPath.getFileSystem().provider()
                def targetAttributes = provider.getFileAttributeView(targetPhysicalPath, BasicFileAttributeView.class)
                        .readAttributes()
                if (targetPhysicalPath.getScheme() == "gs") { // google backed
                    log.trace("resolved ${targetAttributes.info.getMd5ToHexString()} etag")
                    return [checksum: targetAttributes.info.getMd5ToHexString(), size: targetAttributes.size()]
                } else if (targetPhysicalPath.getScheme() == "s3") {
                    throw new NotSupportedException("s3 backed file system still not supported by lakefs plugin")
                } else if (targetPhysicalPath.getScheme() == "az") {
                    throw new NotSupportedException("az backed file system still not supported by lakefs plugin")
                }
            } else if (Map.isAssignableFrom(objectAttributesHolder.getClass())) {
                def targetAttributes = (Map) objectAttributesHolder
                return [checksum: targetAttributes.ETag, size: Long.parseLong(targetAttributes["x-goog-stored-content-length"].toString())]
//                if(targetAttributes.hasProperty()){}
            }


        }

    }

    enum TransferMode {

        signedURL("signed_url", true),
        physicalPath("physical_path", false)

        boolean presign
        String configName

        TransferMode(configName, presign) {
            this.presign = presign
            this.configName = configName
        }

        static TransferMode fromString(str, defaultVal = null) {
            for (mode in values()) {
                if (mode.configName == str)
                    return mode
            }
            return defaultVal
        }
    }

}
