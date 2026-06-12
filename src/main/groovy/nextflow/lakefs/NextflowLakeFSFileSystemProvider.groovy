package nextflow.lakefs

import com.google.common.base.Preconditions
import groovy.transform.CompileStatic
import io.lakefs.clients.sdk.model.ObjectStats
import io.lakefs.clients.sdk.model.StagingLocation
import io.lakefs.clients.sdk.model.StagingMetadata
import nextflow.extension.FilesEx
import nextflow.file.CopyMoveHelper
import nextflow.file.CopyOptions
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
import java.util.concurrent.ConcurrentHashMap
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
    List<URI> allowedSchemaBucketsForLinking = []
    private final Map<String, String> repoStorageNamespaceCache = new ConcurrentHashMap<>()

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
                        if (log.isDebugEnabled())
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
            if (log.isDebugEnabled())
                log.debug("******** staging directory " + lakeFSTarget + " to remote " + remoteDestination.toString())
            CopyMoveHelper.copyDirectory(source, remoteDestination, options)
        } else {
            lakeFSTarget.setCachedAttributes(null)//clear attributes as this might change
            def copyOptions = CopyOptions.parse(options)
            if (copyOptions.followLinks()) {
                String targetRepoStorageNamespace = getRepositoryStorageNamespace(lakeFSTarget.repository())
                if (isSameBackendStorage(source, targetRepoStorageNamespace)) {
                    if (log.isDebugEnabled())
                        log.debug("******** linking physical " + source + " to " + lakeFSTarget)
                    def sourceStagingLocation = new StagingLocation()
                    sourceStagingLocation.physicalAddress = source.toUri().toASCIIString()
                    linkLakeFSToBackendFile(source, lakeFSTarget, sourceStagingLocation)
                    return
                } else {
                    if (log.isDebugEnabled())
                        log.debug("******** cant link " + source + " to " + lakeFSTarget + " for backend storage " + targetRepoStorageNamespace + ". falling back to copy...")
                }
            }
            // Generate a new backend staging location to upload the target to
            def targetStagingLocation = lakeFSClient.getStagingLocation(lakeFSTarget.repository(), lakeFSTarget.ref(), lakeFSTarget.objectPath, transferMode.presign)
            if (log.isDebugEnabled())
                log.debug("******** staging " + lakeFSTarget + " to " + targetStagingLocation.physicalAddress.toString())
            switch (transferMode) {
                case TransferMode.signedURL:
                    // Pass the source size so the PUT streams a fixed-length body instead of buffering it
                    // all in memory (HttpURLConnection's byte[] is capped at Integer.MAX_VALUE ~= 2 GiB).
                    // Works for the normal publish (source is a local file). For a lakefs->lakefs copy the
                    // source is a presigned-HTTPS path whose Files.size() throws (a nextflow XFileSystemProvider
                    // module-access bug), so we fall back to -1: that path keeps buffering and stays ~2 GiB capped.
                    long sourceSize = -1
                    try { sourceSize = Files.size(source) } catch (Throwable ignored) {}
                    def conn = SignedUrlWriteOnlyChannel.createHttpConnection(targetStagingLocation, sourceSize)
                    long bytesWritten
                    try (OutputStream os = conn.getOutputStream()) {
                        bytesWritten = Files.copy(source, os)
                    }
                    // Use the checksum the backend computed over what it actually stored (authoritative):
                    // crc32c from GCS's x-goog-hash, crc64nvme from S3's x-amz-checksum-crc64nvme. Size is the
                    // number of bytes we streamed (S3's PUT response carries no object-size header).
                    def flatHeaders = SignedUrlWriteOnlyChannel.getUploadHttpHeadersAndCloseConnection(conn, targetStagingLocation)
                    linkLakeFSToBackendFileWithMetadata([checksum: CloudProvidersSpecificFactories.resolveUploadChecksum(flatHeaders), size: bytesWritten], lakeFSTarget, targetStagingLocation)
                    break
                case TransferMode.physicalPath:
                    // Scheme-agnostic: copy to the backend via its own NIO (gs:// -> nf-google, s3:// -> nf-amazon)
                    // and link the checksum getLinkMetadata reads back from the stored object.
                    def cloudStoragePhysicalPath = FileHelper.asPath(targetStagingLocation.physicalAddress)

                    def targetPhysicalPath = FilesEx.copyTo(source, cloudStoragePhysicalPath)
                    if (log.isDebugEnabled())
                        log.debug("******** target cloudStoragePhysicalPath " + targetPhysicalPath + "was created from " + source)
                    linkLakeFSToBackendFile(targetPhysicalPath, lakeFSTarget, targetStagingLocation)
                    break
                default: throw new RuntimeException("only signed url and physical path are supported")
            }
        }
    }

    private String getRepositoryStorageNamespace(String repository) {
        return repoStorageNamespaceCache.computeIfAbsent(repository, { repo ->
            lakeFSClient.getRepositoryStorageNamespace(repo)
        })
    }

    private boolean isSameBackendStorage(Path source, String storageNamespace) {
        try {
            URI sourceUri = source.toUri()
            URI storageUri = URI.create(storageNamespace)

            // must match scheme
            if (sourceUri.scheme != storageUri.scheme)
                return false

            // explicit whitelist always wins
            if (allowedSchemaBucketsForLinking) {
                if (log.isDebugEnabled())
                    log.debug(allowedSchemaBucketsForLinking.join(",") + " is checked for source " + sourceUri)
                return allowedSchemaBucketsForLinking.any { URI allowed ->
                    sourceUri.scheme == allowed.scheme &&
                            sourceUri.authority == allowed.authority
                }

            }

            // fallback: same bucket
            return sourceUri.authority == storageUri.authority
        }
        catch (Throwable t) {
            return false
        }
    }

    private void linkLakeFSToBackendFile(Object physicalPathAttributesHolder, NextflowLakeFSPath lakeFSTarget, StagingLocation stagingLocation) {
        def objectProperties = CloudProvidersSpecificFactories.getLinkMetadata(physicalPathAttributesHolder)
        linkLakeFSToBackendFileWithMetadata(objectProperties, lakeFSTarget, stagingLocation)
    }

    // Link with a precomputed {checksum, size} (e.g. crc32c computed client-side during a signed_url PUT),
    // skipping the backend-specific header/attribute parsing in getLinkMetadata.
    private void linkLakeFSToBackendFileWithMetadata(Map objectProperties, NextflowLakeFSPath lakeFSTarget, StagingLocation stagingLocation) {

        Map<String, String> tags = Optional.ofNullable(lakeFSTarget.tags)
                .orElse(Collections.emptyMap())

        tags = tags.collectEntries { k, v -> [(k.toString()): v.toString()] // needed to convert from GString type to string
        }
        if (log.isTraceEnabled())
            log.trace("tags " + lakeFSTarget.repository() + " " + lakeFSTarget.ref() + " " + lakeFSTarget.objectPath + " " + tags)

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
        String storageNamespace = getRepositoryStorageNamespace(lakeFSTarget.repository())
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
            if (log.isDebugEnabled())
                log.debug("trying to link file " + sourcePhysicalPath.toUriString() + " to " + lakeFSTarget)
        }
        if (isSameBackendStorage(sourcePhysicalPath, storageNamespace)) {
            if (log.isDebugEnabled())
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
            allowedSchemaBucketsForLinking =
                    lakeFSConfig.allowedSchemaBucketsForLinking.collect { schemaBucket -> URI.create(schemaBucket.toString()) }
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
            if (log.isDebugEnabled())
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
                if (log.isDebugEnabled())
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

        static HttpURLConnection createHttpConnection(StagingLocation stagingLocation, long contentLength = -1) {
            URL url = new URL(stagingLocation.presignedUrl)

            def conn = (HttpURLConnection) url.openConnection()
            conn.setDoOutput(true)
            conn.setRequestMethod("PUT")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            // When the size is known, stream a fixed-length body. Otherwise HttpURLConnection buffers
            // the whole request in memory to compute Content-Length — a byte[] capped at
            // Integer.MAX_VALUE (~2 GiB), which is the real source of the signed_url size limit.
            if (contentLength >= 0)
                conn.setFixedLengthStreamingMode(contentLength)
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
            // checksum from the backend's PUT response (authoritative); size is the bytes we wrote.
            def flatHeaders = getUploadHttpHeadersAndCloseConnection(conn, stagingLocation)
            linkLakeFSToBackendFileWithMetadata([checksum: CloudProvidersSpecificFactories.resolveUploadChecksum(flatHeaders), size: position], lakeFSPath, stagingLocation)
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
                    // crc32c is always populated by GCS — including composite objects, where md5 is null
                    // (the cause of the HTTP 400). It is content-based and identical across upload modes.
                    def checksum = targetAttributes.info.getCrc32cToHexString()
                    if (log.isTraceEnabled())
                        log.trace("resolved crc32c checksum ${checksum}")
                    return [checksum: checksum, size: targetAttributes.size()]
                } else if (targetPhysicalPath.getScheme() == "s3") {
                    // S3 exposes no checksum through NIO file attributes (unlike GCS's crc32c), so read the
                    // server-computed full-object crc64nvme via GetObjectAttributes. The upload stores crc64nvme
                    // because we run the SDK with requestChecksumCalculation=when_required (see NextflowLakeFSPlugin).
                    def checksum = S3ChecksumReader.crc64nvmeHex(targetPhysicalPath.toUri().toString())
                    if (log.isTraceEnabled())
                        log.trace("resolved crc64nvme checksum ${checksum}")
                    return [checksum: checksum, size: targetAttributes.size()]
                } else if (targetPhysicalPath.getScheme() == "az") {
                    throw new NotSupportedException("az backed file system still not supported by lakefs plugin")
                } else {
                    // fail loudly instead of returning null (which would NPE in linkLakeFSToBackendFileWithMetadata)
                    throw new NotSupportedException("unsupported backend scheme '${targetPhysicalPath.getScheme()}' for lakefs physical_path linking")
                }
            } else {
                throw new NotSupportedException("getLinkMetadata expects a backend Path, got ${objectAttributesHolder?.getClass()?.name}")
            }
        }

        // signed_url: the checksum the backend computed over what it actually stored, taken from the PUT
        // response headers (authoritative — no client-side guessing). GCS -> crc32c (x-goog-hash);
        // S3 -> crc64nvme (x-amz-checksum-crc64nvme); unquoted ETag (md5) as a fallback on either.
        static String resolveUploadChecksum(Map responseHeaders) {
            // Header keys are server-cased and vary by HTTP version, so match case-insensitively.
            def header = { String name -> responseHeaders.find { k, v -> k?.toString()?.equalsIgnoreCase(name) }?.value?.toString() }
            def scheme = header("physicalAddress")?.with { URI.create(it).scheme }
            def checksum
            if (scheme == "gs") {
                def matcher = (header("x-goog-hash") ?: "") =~ 'crc32c=([A-Za-z0-9+/=]+)'
                checksum = matcher.find() ? base64ToHex(matcher.group(1)) : header("ETag")?.replaceAll('"', '')
            } else if (scheme == "s3") {
                def c64 = header("x-amz-checksum-crc64nvme")
                checksum = c64 ? base64ToHex(c64) : header("ETag")?.replaceAll('"', '')
            } else if (scheme == "az") {
                throw new NotSupportedException("az backed file system still not supported by lakefs plugin")
            }
            if (log.isTraceEnabled())
                log.trace("resolved ${scheme} checksum ${checksum}")
            return checksum
        }

        // base64-encoded checksum bytes -> lowercase hex (GCS/S3 return checksums base64-encoded).
        static String base64ToHex(String b64) {
            return java.util.Base64.getDecoder().decode(b64).collect { format('%02x', it & 0xff) }.join()
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
