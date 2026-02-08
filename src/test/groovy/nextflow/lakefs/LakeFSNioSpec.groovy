package nextflow.lakefs

import groovy.util.logging.Slf4j
import io.lakefs.clients.sdk.ApiClient
import io.lakefs.clients.sdk.ObjectsApi
import nextflow.Global
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.file.CopyMoveHelper
import nextflow.file.FileHelper
import nextflow.trace.TraceHelper
import spock.lang.Ignore
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.nio.charset.Charset
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 *
 * @author rpohes@gmail.com
 */
@Slf4j
@Timeout(120)
@Requires({
    System.getenv('LAKEFS_ACCESS_KEY')
            && System.getenv('LAKEFS_SECRET_KEY')
            && System.getenv('LAKEFS_API_URL')
            && System.getenv('LAKEFS_TEST_REPO')
            && System.getenv('LAKEFS_TEST_BRANCH')
})
class LakeFSNioSpec extends Specification implements LakeFSBaseSpec {

    public static final String TEST_REPO_NAME = System.getenv('LAKEFS_TEST_REPO')
    public static final String TEST_MAIN_BRANCH_NAME = System.getenv('LAKEFS_TEST_BRANCH')
    public static final String GOOGLE_EXT_BUCKET = System.getenv('GOOGLE_EXT_BUCKET')


    @Shared
    private ObjectsApi lakeFSClient0

    ObjectsApi getLakeFSClient() { lakeFSClient0 }

    @Shared
    def transferModes = NextflowLakeFSFileSystemProvider.TransferMode.fromString(
            System.getenv('LAKEFS_TRANSFER_MODE'),
            NextflowLakeFSFileSystemProvider.TransferMode.signedURL
    )

    @Shared
    def config = loadConfig()

    private static Map loadConfig() {
        return [
                lakefs: [
                        accessKey   : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey   : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl      : System.getenv('LAKEFS_API_URL'),
                        transferMode: System.getenv('LAKEFS_TRANSFER_MODE') ?: 'signed_url'
                ],
                google: [
                        region    : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project   : System.getenv('GOOGLE_PROJECT') ?: '',
                        ext_bucket: System.getenv('GOOGLE_EXT_BUCKET') ?: ''
                ]
        ]
    }

    def setup() {
        ApiClient apiClient = new ApiClient()
        apiClient.setBasePath(System.getenv('LAKEFS_API_URL'))
        apiClient.setUsername(System.getenv('LAKEFS_ACCESS_KEY'))
        apiClient.setPassword(System.getenv('LAKEFS_SECRET_KEY'))
        this.lakeFSClient0 = new ObjectsApi(apiClient)
    }

    private void setupConfig(transferMode) {
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
        //todo ron >> hack basically the transfer mode is not changed between jvm runs so this shouldn't be done.
        // I think it is probably better to separate the suite for each mode.
//        lakeFSpath("lakefs://e2-demo-model/empty_nextflow_test/").getLakeFSFileSystem().provider().transferMode = transferMode
    }

//    def 'should setup transfer mode : #transferMode on provider'() {
//        given:
//        setupConfig(transferMode)
//        def repository = "e2-demo-model"
//        def branch = "empty_nextflow_test"
//        def objectPath = "file-name.txt"
//        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")
//
//        and:
//        !existsPath(repository, branch, objectPath)
//
//        then:
//        path.getLakeFSFileSystem().provider().transferMode == transferMode
//        def pathExists = existsPath(repository, branch, objectPath)
//
//        cleanup:
//        if (pathExists) deleteObject(repository, branch, objectPath)
//
//        where:
//        transferMode << transferModes
//    }

    @Unroll
    def 'should create a blob with transfer mode : #transferMode'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "file-name.txt"
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        and:
        !existsPath(repository, branch, objectPath)

        when:
        Files.createFile(path)

        then:
        def pathExists = existsPath(repository, branch, objectPath)

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'should write a file with transfer mode : #transferMode'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "file-name.txt"
        def TEXT = "Hello world!"
        and:
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        when:
        Files.write(path, TEXT.bytes)
        then:
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(path) == TEXT

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'should read a file'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "file-name.txt"
        def TEXT = "Hello world!"
        and:
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")


        when:
        Files.write(path, TEXT.bytes)
        def pathExists = existsPath(repository, branch, objectPath)
        then:
        new String(Files.readAllBytes(path)) == TEXT
        Files.readAllLines(path, Charset.forName('UTF-8')).get(0) == TEXT

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'should read a file with space'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "ab c/file-name.txt"
        def TEXT = "Hello world!"
        and:
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")


        when:
        Files.write(path, TEXT.bytes)
        def pathExists = existsPath(repository, branch, objectPath)
        then:
        new String(Files.readAllBytes(path)) == TEXT
        Files.readAllLines(path, Charset.forName('UTF-8')).get(0) == TEXT

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'should read file attributes'() {
        given:
        setupConfig(transferMode)
        final start = System.currentTimeMillis()
        final TEXT = "Hello world!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = 'data/alpha.txt'
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        Files.write(path, TEXT.bytes)
        def pathExists = existsPath(repository, branch, objectPath)
        and:
        //
        // -- readAttributes
        //
        def attrs = Files.readAttributes(path, BasicFileAttributes)
        then:
        attrs.isRegularFile()
        !attrs.isDirectory()
        attrs.size() == 12
        !attrs.isSymbolicLink()
        !attrs.isOther()
        attrs.fileKey() == objectPath
        attrs.lastAccessTime().toMillis() - start < 5_000
        attrs.lastModifiedTime().toMillis() - start < 5_000
        attrs.creationTime().toMillis() - start < 5_000

        //
        // -- getLastModifiedTime
        //
        when:
        def time = Files.getLastModifiedTime(path)
        then:
        time == attrs.lastModifiedTime()

        //
        // -- readAttributes for a directory
        //
        when:
        attrs = Files.readAttributes(path.getParent(), BasicFileAttributes)
        then:
        !attrs.isRegularFile()
        attrs.isDirectory()
        attrs.size() == 0
        !attrs.isSymbolicLink()
        !attrs.isOther()
        attrs.fileKey() == "data/"
        attrs.lastAccessTime().toMillis() - start < 5_000
        attrs.lastModifiedTime().toMillis() - start < 5_000
        attrs.creationTime().toMillis() - start < 5_000

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'should copy a stream to repository'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def target = lakeFSpath("lakefs://$repository/$branch/$objectPath")


        and:
        def stream = new ByteArrayInputStream(new String(TEXT).bytes)
        Files.copy(stream, target)
        then:
        existsPath(repository, branch, objectPath)
        readObject(target) == TEXT

        when:
        stream = new ByteArrayInputStream(new String(TEXT).bytes)
        Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        then:
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(target) == TEXT

//        TODO Ron >> not supported. either lakefs should consistently generate the staging path
//         or check if file exists with lakefs path
//        when:
//        stream = new ByteArrayInputStream(new String(TEXT).bytes)
//        Files.copy(stream, target)
//        then:
//        thrown(FileAlreadyExistsException)

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'copy local file to a repository'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def target = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        def source = Files.createTempFile('test', 'nf')
        source.text = TEXT

        and:
        Files.copy(source, target)
        then:
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(target) == TEXT

        cleanup:
        if (source) Files.delete(source)
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    @Requires({ System.getenv('GOOGLE_PROJECT') && System.getenv('GOOGLE_REGION') })
    def 'copy a remote file to a repo which is backed by same file system'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world On GCS!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def gcsBucket = GOOGLE_EXT_BUCKET
        def objectPath = "data/file.txt"
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")


        and:
        final gcsSource = FileHelper.asPath("gs://$gcsBucket/nextflow-test/file.txt")
        Files.write(gcsSource, TEXT.bytes)


        and:
        // Files copy doesnt work since cloudstoragepath fails to copy to a posix file system path
        lakeFSPath.lakeFSFileSystem.provider().copy(gcsSource, lakeFSPath)
//        Files.copy(source, target)
        then:
//        existsPath(source)
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(lakeFSPath) == TEXT

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    @Requires({ System.getenv('GOOGLE_PROJECT') && System.getenv('GOOGLE_REGION') && System.getenv('GOOGLE_EXT_BUCKET') })
    def 'should link a remote file to a repo which is backed by same file system but not the same bucket'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def gcsBucket = GOOGLE_EXT_BUCKET
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        def client = new LakeFSSDKClient(config.lakefs)


        and:
        final gcsSource = FileHelper.asPath("gs://$gcsBucket/nextflow-test/file.txt")
        Files.write(gcsSource, TEXT.bytes)


        def provider = lakeFSPath.lakeFSFileSystem.provider()
        provider.allowedSchemaBucketsForLinking = ["s3://some-other-bucket", "gs://$gcsBucket"]
                .collect { schemaBucket -> URI.create(schemaBucket.toString()) }
        and:
        // Files copy doesnt work since cloudstoragepath fails to copy to a posix file system path
        Files.createLink(lakeFSPath, gcsSource)
//        provider.copy(gcsSource, lakeFSPath, LinkOption.NOFOLLOW_LINKS)
        //        Files.copy(source, target)
        then:
        //        existsPath(source)
        def pathExists = existsPath(repository, branch, objectPath)
//            readObject(lakeFSPath) == TEXT // we need to make sure lakefs have permission to a bucket which is not the same

        // Verify that the object is actually linked (physical address matches source)
        def stats = client.getObjectStats(repository, branch, objectPath, false)
        FileHelper.asPath(stats.physicalAddress) == gcsSource

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)
        // Reset config
        provider.allowedSchemaBucketsForLinking = []


        where:
        transferMode << transferModes
    }

    @Requires({ System.getenv('GOOGLE_PROJECT') && System.getenv('GOOGLE_REGION') && System.getenv('GOOGLE_EXT_BUCKET') })
    def 'should link a remote file to a repo which is backed by same file system but not the same bucket when using copy'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def gcsBucket = GOOGLE_EXT_BUCKET
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        def client = new LakeFSSDKClient(config.lakefs)


        and:
        final gcsSource = FileHelper.asPath("gs://$gcsBucket/nextflow-test/file.txt")
        Files.write(gcsSource, TEXT.bytes)


        def provider = lakeFSPath.lakeFSFileSystem.provider()
        provider.allowedSchemaBucketsForLinking = ["s3://some-other-bucket", "gs://$gcsBucket"]
                .collect { schemaBucket -> URI.create(schemaBucket.toString()) }
        and:

        FileHelper.copyPath(gcsSource, lakeFSPath)

        then:
        //        existsPath(source)
        def pathExists = existsPath(repository, branch, objectPath)
//        readObject(lakeFSPath) == TEXT // we need to make sure lakefs have permission to a bucket which is not the same

        // Verify that the object is actually linked (physical address matches source)
        def stats = client.getObjectStats(repository, branch, objectPath, false)
        FileHelper.asPath(stats.physicalAddress) == gcsSource

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)
        // Reset config
        provider.allowedSchemaBucketsForLinking = []


        where:
        transferMode << transferModes
    }

    @Requires({ System.getenv('GOOGLE_PROJECT') && System.getenv('GOOGLE_REGION') && System.getenv('GOOGLE_EXT_BUCKET') })
    def 'should NOT link a remote file to a repo which is backed by same file system but not the same bucket when using copy_nofollow but do a content copy'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def gcsBucket = GOOGLE_EXT_BUCKET
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        def client = new LakeFSSDKClient(config.lakefs)


        and:
        final gcsSource = FileHelper.asPath("gs://$gcsBucket/nextflow-test/file.txt")
        Files.write(gcsSource, TEXT.bytes)


        def provider = lakeFSPath.lakeFSFileSystem.provider()
        provider.allowedSchemaBucketsForLinking = ["s3://some-other-bucket", "gs://$gcsBucket"]
                .collect { schemaBucket -> URI.create(schemaBucket.toString()) }
        and:

        FileHelper.copyPath(gcsSource, lakeFSPath, LinkOption.NOFOLLOW_LINKS)

        then:
        //        existsPath(source)
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(lakeFSPath) == TEXT // we need to make sure lakefs have permission to a bucket which is not the same

        // Verify that the object is actually linked (physical address matches source)
        def stats = client.getObjectStats(repository, branch, objectPath, false)
        FileHelper.asPath(stats.physicalAddress) != gcsSource

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)
        // Reset config
        provider.allowedSchemaBucketsForLinking = []


        where:
        transferMode << transferModes
    }

    @Requires({ System.getenv('GOOGLE_PROJECT') && System.getenv('GOOGLE_REGION') })
    def 'should link a remote file to a repo which is backed by same file system and the same bucket'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world On GCS!"
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/linked-file.txt"
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        and:
        // Get the repository storage namespace to ensure we are on the same backend
        def client = new LakeFSSDKClient(config.lakefs)
        def storageNamespace = client.getRepositoryStorageNamespace(repository)
        def storageUri = URI.create(storageNamespace)

        // Construct a source path in the same bucket but different prefix
        // We assume we have write access to the bucket
        def bucket = storageUri.authority
        def sourcePathStr = "${storageUri.scheme}://${bucket}/nf-test-data/source-file-${UUID.randomUUID()}.txt"
        def gcsSource = FileHelper.asPath(sourcePathStr)

        Files.write(gcsSource, TEXT.bytes)

        when:
        Files.createLink(lakeFSPath, gcsSource)

        then:
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(lakeFSPath) == TEXT

        // Verify that the object is actually linked (physical address matches source)
        def stats = client.getObjectStats(repository, branch, objectPath, false)
        stats.physicalAddress == sourcePathStr

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)
        try {
            Files.deleteIfExists(gcsSource)
        } catch (Exception e) {
            log.warn("Failed to delete source file", e)
        }

        where:
        transferMode << transferModes
    }

    @Ignore("lakefs to lakefs link is not supported yet (0.4.X")
    @Requires({ System.getenv('GOOGLE_PROJECT') && System.getenv('GOOGLE_REGION') })
    def 'should link a lakefs file to another lakefs file (zero copy)'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world!"
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def sourcePath = "data/source-file.txt"
        def targetPath = "data/target-file.txt"
        def lakeFSSource = lakeFSpath("lakefs://e2-demo-model/1.2.3/config/dataset/E-MTAB-184/config.yaml")
        def lakeFSTarget = lakeFSpath("lakefs://$repository/$branch/$targetPath")
        def client = new LakeFSSDKClient(config.lakefs)

//        and:
//        Files.write(lakeFSSource, TEXT.bytes)

        when:
        // Use LinkOption.NOFOLLOW_LINKS to trigger the optimization
        Files.createLink(lakeFSTarget, lakeFSSource)
//        lakeFSTarget.lakeFSFileSystem.provider().copy(lakeFSSource, lakeFSTarget, LinkOption.NOFOLLOW_LINKS)

        then:
        def pathExists = existsPath(repository, branch, targetPath)
        readObject(lakeFSTarget) == TEXT

        // Verify that the object is actually linked (physical address matches source)
        def sourceStats = client.getObjectStats(repository, branch, sourcePath, false)
        def targetStats = client.getObjectStats(repository, branch, targetPath, false)
        targetStats.physicalAddress == sourceStats.physicalAddress

        cleanup:
        if (pathExists) deleteObject(repository, branch, targetPath)
        deleteObject(repository, branch, sourcePath)

        where:
        transferMode << transferModes
    }

    def 'move local file to a repo'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world On GCS!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")


        and:
        final source = Files.createTempFile('foo', null); source.text = TEXT

        and:
        Files.move(source, lakeFSPath)
        then:
        !Files.exists(source)
        def pathExists = existsPath(repository, branch, objectPath)
        readObject(lakeFSPath) == TEXT

        cleanup:
        if (pathExists) deleteObject(repository, branch, objectPath)

        where:
        transferMode << transferModes
    }

    def 'copy a remote lakefs file to local (download)'() {
        given:
        setupConfig(transferMode)
        def TEXT = "Hello world On GCS!"

        when:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"

        def target = Files.createTempFile('foo', null)

        and:
        def lakeFSPath = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        Files.write(lakeFSPath, TEXT.bytes)

        and:
        Files.copy(lakeFSPath, target, StandardCopyOption.REPLACE_EXISTING)

        then:
//        !existsPath(repository, branch, objectPath)
        Files.exists(target)
        target.text == TEXT

        cleanup:
        if (target) Files.deleteIfExists(target)

        where:
        transferMode << transferModes
    }

    def 'should create a file'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        when:
        def path = lakeFSpath("lakefs://$repository/$branch/data/file.txt")
        Files.createFile(path)
        then:
        existsPath(repository, branch, "data/file.txt")

        cleanup:
        deleteObject(repository, branch, "data/file.txt")

        where:
        transferMode << transferModes
    }

    def 'should create a file with space in path'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        when:
        def path = lakeFSpath("lakefs://$repository/$branch/data a/file.txt")
        Files.createFile(path)
        then:
        existsPath(repository, branch, "data a/file.txt")

        cleanup:
        deleteObject(repository, branch, "data a/file.txt")

        where:
        transferMode << transferModes
    }


    def 'should delete a file'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def target = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        and:
        Files.write(target, 'HELLO WORLD'.bytes)

        when:
        Files.delete(target)
        sleep 100
        then:
        !existsPath(repository, branch, objectPath)

        where:
        transferMode << transferModes

    }


    @Ignore("need to handle response 204 differently")
    def 'should throw a NoSuchFileException when deleting an object not existing'() {

        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        def path = lakeFSpath("lakefs://$repository/$branch/alpha/bravo")

        when:
        Files.delete(path)
        then:
        thrown(NoSuchFileException)

        where:
        transferMode << transferModes
    }


    def 'should validate exists method'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        and:
        createObject("lakefs://$repository/$branch/file.txt", 'HELLO')

        expect:
        Files.exists(lakeFSpath("lakefs://$repository/$branch"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/file.txt"))
        !Files.exists(lakeFSpath("lakefs://$repository/$branch/fooooo.txt"))

        cleanup:
        deleteObject(repository, branch, "file.txt")

        where:
        transferMode << transferModes
    }


    def 'should check is it is a directory'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"


        when:
        def target = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        Files.write(target, 'HELLO WORLD'.bytes)


        then:
        def parentDirectory = target.getParent()
        Files.isDirectory(parentDirectory)
        !Files.isRegularFile(parentDirectory)

        when:
        def file = parentDirectory.resolve('this/and/that')
        Files.write(file, 'HELLO WORLD'.bytes)
        then:
        !Files.isDirectory(file)
        Files.isRegularFile(file)
        Files.isReadable(file)
        Files.isWritable(file)
//        !Files.isExecutable(file)
//        !Files.isSymbolicLink(file)

        expect:
        Files.isDirectory(file.parent)
        !Files.isRegularFile(file.parent)
        Files.isReadable(file)
        Files.isWritable(file)
//        !Files.isExecutable(file)
//        !Files.isSymbolicLink(file)

        cleanup:
        Files.delete(target)
        Files.delete(file)

        where:
        transferMode << transferModes
    }

    def 'should check that is the same file'() {

        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        def file1 = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        def file2 = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        def file3 = lakeFSpath("lakefs://$repository/$branch/data/file3.txt")

        expect:
        Files.isSameFile(file1, file2)
        !Files.isSameFile(file1, file3)

        where:
        transferMode << transferModes

    }

    def 'should create a newBufferedReader'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"

        and:
        def TEXT = randomText(50 * 1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        Files.write(path, TEXT.bytes)

        when:
        def reader = Files.newBufferedReader(path, Charset.forName('UTF-8'))
        then:
        reader.text == TEXT

        when:
        def unknown = lakeFSpath("lakefs://$repository/$branch/unknown.txt")
        Files.newBufferedReader(unknown, Charset.forName('UTF-8'))
        then:
        thrown(NoSuchFileException)

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should create a newBufferedWriter'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"

        and:
        final TEXT = randomText(50 * 1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        when:
        def writer = Files.newBufferedWriter(path, Charset.forName('UTF-8'))
        TEXT.readLines().each { it -> writer.println(it) }
        writer.close()
        then:
        readObject(path) == TEXT

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should create a newInputStream'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"

        and:
        final TEXT = randomText(50 * 1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        Files.write(path, TEXT.bytes)

        when:
        def reader = Files.newInputStream(path)
        then:
        reader.text == TEXT

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should copy a directory'() {

        given:
        setupConfig(transferMode)
        def folder = Files.createTempDirectory('test')
        def target = folder.resolve('file.txt')
        and:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        and:
        final TEXT = randomText(50 * 1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        Files.write(path, TEXT.bytes)

        when:
        target = FileHelper.copyPath(path, target)

        then:
        target.text == TEXT

        cleanup:
        folder?.deleteDir()
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should create a newOutputStream'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        and:
        final TEXT = randomText(2048)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        when:
        def writer = Files.newOutputStream(path)
        TEXT.readLines().each { it ->
            writer.write(it.bytes)
            writer.write((int) ('\n' as char))
        }
        writer.close()
        then:
        readObject(path) == TEXT

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should read a newByteChannel'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        and:
        final TEXT = randomText(1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")
        Files.write(path, TEXT.bytes)

        when:
        def channel = Files.newByteChannel(path)
        then:
        readChannel(channel, 100) == TEXT

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should write a byte channel'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        and:
        def TEXT = randomText(1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        when:
        def channel = Files.newByteChannel(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        writeChannel(channel, TEXT, 200)
        channel.close()
        then:
        readObject(path) == TEXT

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    def 'should check file size'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def objectPath = "data/file.txt"
        and:
        final TEXT = randomText(50 * 1024)
        def path = lakeFSpath("lakefs://$repository/$branch/$objectPath")

        when:
        Files.write(path, TEXT.bytes)
        then:
        Files.size(path) == TEXT.size()

        when:
        Files.size(path.resolve('xxx'))
        then:
        thrown(NoSuchFileException)

        cleanup:
        Files.delete(path)

        where:
        transferMode << transferModes
    }

    // test fails on GHA, likely due to concurrent execution
    //@IgnoreIf({ System.getenv('GITHUB_ACTIONS') })
//    @Ignore
    def 'should list root directory'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        and:
        createObject("lakefs://$repository/$branch/file.1", 'xxx')
        createObject("lakefs://$repository/$branch/foo/file.2", 'xxx')
        createObject("lakefs://$repository/$branch/foo/bar/file.3", 'xxx')

        and:
        def root = lakeFSpath("lakefs://$repository/$branch")

        when:
        Set<String> dirs = []
        Set<String> files = []
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                dirs << dir.toString()
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files << file.toString()
                return FileVisitResult.CONTINUE
            }

        })
        then:
        println dirs
        println files
        dirs.contains('')
        dirs.contains("foo/" as String)
        dirs.contains("foo/bar/" as String)
        files.contains("file.1" as String)
        files.contains("foo/file.2" as String)
        files.contains("foo/bar/file.3" as String)

        cleanup:
        deleteObject(repository, branch, "file.1")
        deleteObject(repository, branch, "foo/file.2")
        deleteObject(repository, branch, "foo/bar/file.3")

        where:
        transferMode << transferModes
    }


    def 'should stream directory content'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        createObject("lakefs://$repository/$branch/foo/file1.txt", 'A')
        createObject("lakefs://$repository/$branch/foo/file2.txt", 'BB')
        createObject("lakefs://$repository/$branch/foo/bar/file3.txt", 'CCC')
        createObject("lakefs://$repository/$branch/foo/bar/baz/file4.txt", 'DDDD')
        createObject("lakefs://$repository/$branch/foo/bar/file5.txt", 'EEEEE')
        createObject("lakefs://$repository/$branch/foo/file6.txt", 'FFFFFF')

//        when:
//        def list = Files.newDirectoryStream(lakeFSpath("lakefs://$repository/$branch/")).collect { it.getFileName().toString() }
//        then:
//        list.size() == 1
//        list == ['foo']

        when:
        def list = Files.newDirectoryStream(lakeFSpath("lakefs://$repository/$branch/foo/")).collect { it.getFileName().toString() }
        then:
        list.size() == 4
        list as Set == ['file1.txt', 'file2.txt', 'bar', 'file6.txt'] as Set

        when:
        list = Files.newDirectoryStream(lakeFSpath("lakefs://$repository/$branch/foo/bar/")).collect { it.getFileName().toString() }
        then:
        list.size() == 3
        list as Set == ['file3.txt', 'baz', 'file5.txt'] as Set

        when:
        list = Files.newDirectoryStream(lakeFSpath("lakefs://$repository/$branch/foo/bar/baz/")).collect { it.getFileName().toString() }
        then:
        list.size() == 1
        list == ['file4.txt']

        cleanup:
        deleteObject(repository, branch, "foo/file1.txt")
        deleteObject(repository, branch, "foo/file2.txt")
        deleteObject(repository, branch, "foo/bar/file3.txt")
        deleteObject(repository, branch, "foo/bar/baz/file4.txt")
        deleteObject(repository, branch, "foo/bar/file5.txt")
        deleteObject(repository, branch, "foo/file6.txt")

        where:
        transferMode << transferModes
    }


//    @Ignore
    def 'should check walkTree'() {

        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        createObject("lakefs://$repository/$branch/foo/file1.txt", 'A')
        createObject("lakefs://$repository/$branch/foo/file2.txt", 'BB')
        createObject("lakefs://$repository/$branch/foo/bar/file3.txt", 'CCC')
        createObject("lakefs://$repository/$branch/foo/bar/baz/file4.txt", 'DDDD')
        createObject("lakefs://$repository/$branch/foo/bar/file5.txt", 'EEEEE')
        createObject("lakefs://$repository/$branch/foo/file6.txt", 'FFFFFF')

        when:
        List<String> dirs = []
        Map<String, BasicFileAttributes> files = [:]
        def base = lakeFSpath("lakefs://$repository/$branch/")
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {

            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dirs << base.relativize(dir).toString()
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files[file.getFileName().toString()] = attrs
                return FileVisitResult.CONTINUE
            }
        })

        then:
        files.size() == 6
        files['file1.txt'].size() == 1
        files['file2.txt'].size() == 2
        files['file3.txt'].size() == 3
        files['file4.txt'].size() == 4
        files['file5.txt'].size() == 5
        files['file6.txt'].size() == 6
        dirs.size() == 4
        dirs.contains("")
        dirs.contains('foo/')
        dirs.contains('foo/bar/')
        dirs.contains('foo/bar/baz/')


        when:
        dirs = []
        files = [:]
        base = lakeFSpath("lakefs://$repository/$branch/foo/bar/")
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {

            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dirs << base.relativize(dir).toString()
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files[file.getFileName().toString()] = attrs
                return FileVisitResult.CONTINUE
            }
        })

        then:
        files.size() == 3
        files.containsKey('file3.txt')
        files.containsKey('file4.txt')
        files.containsKey('file5.txt')
        dirs.size() == 2
        dirs.contains("")
        dirs.contains('baz')

        cleanup:
        deleteObject(repository, branch, "foo/file1.txt")
        deleteObject(repository, branch, "foo/file2.txt")
        deleteObject(repository, branch, "foo/bar/file3.txt")
        deleteObject(repository, branch, "foo/bar/baz/file4.txt")
        deleteObject(repository, branch, "foo/bar/file5.txt")
        deleteObject(repository, branch, "foo/file6.txt")

        where:
        transferMode << transferModes
    }

//    @Ignore
//    // FIXME
    def 'should handle dir and files having the same name'() {

        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        createObject("lakefs://$repository/$branch/foo", 'file-1')
        createObject("lakefs://$repository/$branch/foo/bar", 'file-2')
        createObject("lakefs://$repository/$branch/foo/baz", 'file-3')
        and:
        def root = lakeFSpath("lakefs://$repository/$branch")

        when:
        def file1 = root.resolve('foo')
        then:
        Files.isRegularFile(file1)
        !Files.isDirectory(file1)
        file1.text == 'file-1'

        when:
        def dir1 = root.resolve('foo/')
        then:
        !Files.isRegularFile(dir1)
        Files.isDirectory(dir1)

        when:
        def file2 = root.resolve('foo/bar')
        then:
        Files.isRegularFile(file2)
        !Files.isDirectory(file2)
        file2.text == 'file-2'


        when:
        def parent = file2.parent
        then:
        !Files.isRegularFile(parent)
        Files.isDirectory(parent)

        when:
        Set<String> dirs = []
        Map<String, BasicFileAttributes> files = [:]
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dirs << root.relativize(dir).toString()
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                files[root.relativize(file).toString()] = attrs
                return FileVisitResult.CONTINUE
            }
        })
        then:
        dirs.size() == 2
        dirs.contains('')
        dirs.contains('foo/')
        files.size() == 3
        files.containsKey('foo')
        files.containsKey('foo/bar')
        files.containsKey('foo/baz')

        cleanup:
        deleteObject(repository, branch, "foo")
        deleteObject(repository, branch, "foo/bar")
        deleteObject(repository, branch, "foo/baz")

        where:
        transferMode << transferModes
    }

    def 'should handle file names with same prefix'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        and:
        createObject("lakefs://$repository/$branch/transcript_index.junctions.fa", 'foo')
        createObject("lakefs://$repository/$branch/alpha-beta/file1", 'bar')
        createObject("lakefs://$repository/$branch/alpha/file2", 'baz')

        expect:
        Files.exists(lakeFSpath("lakefs://$repository/$branch/transcript_index.junctions.fa"))
        !Files.exists(lakeFSpath("lakefs://$repository/$branch/transcript_index.junctions"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/alpha-beta/file1"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/alpha/file2"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/alpha-beta/"))
        !Files.exists(lakeFSpath("lakefs://$repository/$branch/alpha-beta"))//todo ron >> currently only with / at the end is directory
        Files.exists(lakeFSpath("lakefs://$repository/$branch/alpha/"))
        !Files.exists(lakeFSpath("lakefs://$repository/$branch/alpha"))//todo ron >> currently only with / at the end is directory

        cleanup:
        deleteObject(repository, branch, "transcript_index.junctions.fa")
        deleteObject(repository, branch, "alpha-beta/file1")
        deleteObject(repository, branch, "alpha/file2")

        where:
        transferMode << transferModes
    }

    def 'should add user metadata (tag) to a file'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        and:
        def path = lakeFSpath("lakefs://$repository/$branch/alpha.txt")
        def copy = lakeFSpath("lakefs://$repository/$branch/omega.txt")
        and:
        def client = getLakeFSClient()

        when:
        path.setTags(FOO: 'Hello world', BAR: 'xyz')
        Files.createFile(path)
        then:
        Files.exists(path)
        and:
        def tags = client.statObject(path.repository(), path.ref(), path.getObjectPath()).execute().metadata
        tags.find { it.key == 'FOO' }.value == 'Hello world'
        tags.find { it.key == 'BAR' }.value == 'xyz'

        when:
        copy.setTags(FOO: 'Hola mundo', BAZ: '123')
        Files.copy(path, copy)
        then:
        Files.exists(copy)
        and:
        def copyTags = client.statObject(copy.repository(), copy.ref(), copy.getObjectPath()).execute().metadata
        copyTags.find { it.key == 'FOO' }.value == 'Hola mundo'
        copyTags.find { it.key == 'BAZ' }.value == '123'
        copyTags.find { it.key == 'BAR' } == null

        cleanup:
        deleteObject(copy.repository(), copy.ref(), copy.getObjectPath())
        deleteObject(path.repository(), path.ref(), path.getObjectPath())

        where:
        transferMode << transferModes
    }

    def 'should download lakefs dir/prefix to local dir'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        createObject("lakefs://$repository/$branch/cache/foo/file-1", 'File one')
        createObject("lakefs://$repository/$branch/cache/foo/bar/file-2", 'File two')
        createObject("lakefs://$repository/$branch/cache/foo/baz/file-3", 'File three')
        and:
        def local = Files.createTempDirectory('test')
        def cache1 = local.resolve('cache1')
        def cache2 = local.resolve('cache2')
        and:
        def remote = lakeFSpath("lakefs://$repository/$branch/cache/foo/")

        when:
        CopyMoveHelper.copyToForeignTarget(remote, cache1)
        then:
        cache1.resolve('file-1').exists()
        cache1.resolve('bar/file-2').exists()
        cache1.resolve('baz/file-3').exists()

        when:
        // the use of 'FileHelper.copyPath' will invoke the lakefs provider download directory method
        // make sure the resulting local directory structure matches the one created by 'CopyMoveHelper.copyToForeignTarget'
        FileHelper.copyPath(remote, cache2)
        then:
        cache2.resolve('file-1').exists()
        cache2.resolve('bar/file-2').exists()
        cache2.resolve('baz/file-3').exists()

        cleanup:
        local?.deleteDir()
        deleteObject(repository, branch, "cache/foo/file-1")
        deleteObject(repository, branch, "cache/foo/bar/file-2")
        deleteObject(repository, branch, "cache/foo/baz/file-3")

        where:
        transferMode << transferModes
    }

//    @Ignore("TODO >> curentlly a problem when not using FileSystemTransferAware, and FileSystemTransferAware fails on copyPath")
    def 'should upload local dir to lakefs directory/prefix'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME

        def local = Files.createTempDirectory('test')
        local.resolve('cache/foo').mkdirs()
        local.resolve('cache/foo/bar').mkdirs()
        local.resolve('cache/foo/baz').mkdirs()
        and:
        local.resolve('cache/foo/file-1').text = 'File one'
        local.resolve('cache/foo/bar/file-2').text = 'File two'
        local.resolve('cache/foo/baz/file-3').text = 'File three'
        local.resolve('cache/foo/baz/file-4').text = 'File four'

        when:
        CopyMoveHelper.copyToForeignTarget(local.resolve('cache/foo'), lakeFSpath("lakefs://$repository/$branch/cache1"))
        then:
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache1/file-1"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache1/bar/file-2"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache1/baz/file-3"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache1/baz/file-4"))

        when:
        FileHelper.copyPath(local.resolve('cache/foo'), lakeFSpath("lakefs://$repository/$branch/cache2"))
        then:
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache2/file-1"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache2/bar/file-2"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache2/baz/file-3"))
        Files.exists(lakeFSpath("lakefs://$repository/$branch/cache2/baz/file-4"))

        cleanup:
        local?.deleteDir()
        deleteObject(repository, branch, "cache1/file-1")
        deleteObject(repository, branch, "cache1/bar/file-2")
        deleteObject(repository, branch, "cache1/baz/file-3")
        deleteObject(repository, branch, "cache1/baz/file-4")
        deleteObject(repository, branch, "cache2/file-1")
        deleteObject(repository, branch, "cache2/bar/file-2")
        deleteObject(repository, branch, "cache2/baz/file-3")
        deleteObject(repository, branch, "cache2/baz/file-4")

        where:
        transferMode << transferModes
    }

    void "should upload a stream with multiple flush"() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        and:
        def path = lakeFSpath("lakefs://$repository/$branch/alpha.txt")

        when:
        PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, Charset.defaultCharset()))
        writer.println '*' * 20
        writer.flush()
        writer.println '*' * 20
        writer.flush()
        writer.close()

        then:
        Files.readString(lakeFSpath("lakefs://$repository/$branch/alpha.txt")).length() == 42 // 2*20 + 2 return lines

        cleanup:
        deleteObject(repository, branch, "alpha.txt")

        where:
        transferMode << transferModes
    }

    void "should upload a stream without flush"() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        and:
        def path = lakeFSpath("lakefs://$repository/$branch/alpha.txt")

        when:
        PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, Charset.defaultCharset()))
        writer.println '*' * 20
        writer.println '*' * 20
        writer.close()

        then:
        Files.readString(lakeFSpath("lakefs://$repository/$branch/alpha.txt")).length() == 42 // 2*20 + 2 return lines

        cleanup:
        deleteObject(path.repository(), path.ref(), path.objectPath)

        where:
        transferMode << transferModes
    }

    @Unroll
    def 'should upload, copy and download a file'() {
        given:
        setupConfig(NextflowLakeFSFileSystemProvider.TransferMode.signedURL)
        def TEXT = randomText(fileSize)
        def folder = Files.createTempDirectory('test')
        def file = Files.write(folder.resolve('foo.data'), TEXT.bytes)
        and:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def branch2 = "Ron_test"

        // upload a file to a remote repository
        when:
        def target1 = lakeFSpath("lakefs://$repository/$branch/foo.data")
        FileHelper.copyPath(file, target1)
        // the file exist
        then:
        Files.exists(target1)
        Files.size(target1) == Files.size(file)

        // copy a file across branches
        when:
        def target2 = lakeFSpath("lakefs://$repository/$branch2/foo.data")
        FileHelper.copyPath(target1, target2)
        // the file exist
        then:
        Files.exists(target2)
        Files.size(target2) == Files.size(target1)

        // download a file locally
        when:
        def result = folder.resolve('result.data')
        FileHelper.copyPath(target2, result)
        then:
        Files.exists(result)
        and:
        Files.size(target2) == Files.size(result)

        cleanup:
        deleteObject(target2.repository(), target2.ref(), target2.objectPath)
        deleteObject(target1.repository(), target1.ref(), target1.objectPath)
        folder?.deleteDir()

        // in the test resources
        where:
        [fileSize, transferMode] << [[50 * 1024, 11 * 1024 * 1024], [transferModes]].combinations()
    }

    @Unroll
    @Ignore("set content type unsupported")
    def 'should set file media type'() {
        given:
        setupConfig(transferMode)
        def TEXT = randomText(FILE_SIZE)
        def folder = Files.createTempDirectory('test')
        def file = Files.write(folder.resolve('foo.data'), TEXT.bytes)
        and:
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def branch2 = "Ron_test"

        // upload a file to a remote repository
        when:
        def target1 = lakeFSpath("lakefs://$repository/$branch/foo.data")
        and:
        target1.setContentType('text/foo')
        def client = getLakeFSClient()
        and:
        FileHelper.copyPath(file, target1)
        // the file exist
        then:
        Files.exists(target1)
        and:
        client.statObject(repository, branch, "foo.data").execute()
                .contentType == 'text/foo'

        // copy a file across repositories
        when:
        def target2 = lakeFSpath("lakefs://$repository/$branch2/foo.data")
        and:
        target2.setContentType('text/bar')
        and:
        FileHelper.copyPath(target1, target2)
        // the file exist
        then:
        Files.exists(target2)
        client.statObject(repository, branch2, "foo.data").execute()
                .contentType == 'text/bar'

        cleanup:
        deleteObject(repository, branch, "foo.data")
        deleteObject(repository, branch2, "foo.data")
        folder?.deleteDir()

        where:
        FILE_SIZE << [50 * 1024, 11 * 1024 * 1024]
        transferMode << transferModes
    }


    def 'should overwrite a file'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def path = lakeFSpath("lakefs://$repository/$branch/foo/bar.txt")
        and:
        path.text = 'foo'

        when:
        def file = TraceHelper.newFileWriter(path, true, 'Test')
        file.write('Hola')
        file.close()
        then:
        path.text == 'Hola'

        cleanup:
        deleteObject(path.repository(), path.ref(), path.objectPath)

        where:
        transferMode << transferModes
    }

    @Ignore("not supported")
    def 'should not overwrite a file'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def branch = TEST_MAIN_BRANCH_NAME
        def path = lakeFSpath("lakefs://$repository/$branch/foo/bar.txt")

        and:
        path.text = 'foo'

        when:
        TraceHelper.newFileWriter(path, false, 'Test')
        then:
        def e = thrown(AbortOperationException)
        e.message == "Test file already exists: ${path.toUriString()} -- enable the 'test.overwrite' option in your config file to overwrite existing files"

        cleanup:
        deleteObject(path.repository(), path.ref(), path.objectPath)

        where:
        transferMode << transferModes
    }

    // =====================================================
    // Auto-create branch tests
    // =====================================================

    def 'should throw NoSuchFileException when branch does not exist and autoCreateBranch is disabled'() {
        given:
        def configWithoutAutoCreate = [
                lakefs: [
                        accessKey       : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey       : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl          : System.getenv('LAKEFS_API_URL'),
                        autoCreateBranch: false
                ],
                google: [
                        region : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project: System.getenv('GOOGLE_PROJECT') ?: ''
                ]
        ]
        Global.config = configWithoutAutoCreate
        Global.session = Mock(Session) { getConfig() >> configWithoutAutoCreate }

        def nonExistentBranch = "non-existent-branch-${UUID.randomUUID().toString().substring(0, 8)}"

        when:
        lakeFSpath("lakefs://$TEST_REPO_NAME/$nonExistentBranch/test.txt")

        then:
        thrown(NoSuchFileException)
    }

    def 'should auto-create branch when autoCreateBranch is enabled'() {
        given:
        def newBranchName = "auto-created-branch-${UUID.randomUUID().toString().substring(0, 8)}"
        def configWithAutoCreate = [
                lakefs: [
                        accessKey             : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey             : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl                : System.getenv('LAKEFS_API_URL'),
                        autoCreateBranch      : true,
                        autoCreateBranchSource: TEST_MAIN_BRANCH_NAME
                ],
                google: [
                        region : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project: System.getenv('GOOGLE_PROJECT') ?: ''
                ]
        ]
        Global.config = configWithAutoCreate
        Global.session = Mock(Session) { getConfig() >> configWithAutoCreate }

        and:
        def client = new LakeFSSDKClient(configWithAutoCreate.lakefs)

        when:
        def path = lakeFSpath("lakefs://$TEST_REPO_NAME/$newBranchName/test-file.txt")
        Files.write(path, "Hello from auto-created branch".bytes)

        then:
        noExceptionThrown()
        client.branchExists(TEST_REPO_NAME, newBranchName)
        Files.exists(path)

        cleanup:
        // Delete the test file
        try {
            deleteObject(TEST_REPO_NAME, newBranchName, "test-file.txt")
        } catch (Exception ignored) {
        }
        // Delete the auto-created branch
        try {
            client.branchesApi.deleteBranch(TEST_REPO_NAME, newBranchName).execute()
        } catch (Exception ignored) {
        }
        // Reset config
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
    }

    def 'should use custom source branch for auto-create'() {
        given:
        def newBranchName = "custom-source-branch-${UUID.randomUUID().toString().substring(0, 8)}"
        def configWithCustomSource = [
                lakefs: [
                        accessKey             : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey             : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl                : System.getenv('LAKEFS_API_URL'),
                        autoCreateBranch      : true,
                        autoCreateBranchSource: TEST_MAIN_BRANCH_NAME
                ],
                google: [
                        region : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project: System.getenv('GOOGLE_PROJECT') ?: ''
                ]
        ]
        Global.config = configWithCustomSource
        Global.session = Mock(Session) { getConfig() >> configWithCustomSource }

        and:
        def client = new LakeFSSDKClient(configWithCustomSource.lakefs)

        when:
        def path = lakeFSpath("lakefs://$TEST_REPO_NAME/$newBranchName/custom-source-test.txt")
        Files.write(path, "Hello from custom source branch".bytes)

        then:
        noExceptionThrown()
        client.branchExists(TEST_REPO_NAME, newBranchName)

        cleanup:
        try {
            deleteObject(TEST_REPO_NAME, newBranchName, "custom-source-test.txt")
        } catch (Exception ignored) {
        }
        try {
            client.branchesApi.deleteBranch(TEST_REPO_NAME, newBranchName).execute()
        } catch (Exception ignored) {
        }
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
    }

    // =====================================================
    // Tag support tests
    // =====================================================

    def 'should read file from a tag'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def testBranchName = "tag-test-branch-${UUID.randomUUID().toString().substring(0, 8)}"
        def tagName = "test-tag-${UUID.randomUUID().toString().substring(0, 8)}"
        def client = new LakeFSSDKClient(config.lakefs)

        and:
        // Create a separate branch for this test
        client.createBranch(repository, testBranchName, TEST_MAIN_BRANCH_NAME)

        and:
        // Create a file on the test branch
        def objectPath = "tag-test-file.txt"
        def TEXT = "Hello from tag test!"
        def branchPath = lakeFSpath("lakefs://$repository/$testBranchName/$objectPath")
        Files.write(branchPath, TEXT.bytes)

        and:
        // Commit the changes so the tag can see them
        client.commit(repository, testBranchName, "Add test file for tag test")

        and:
        // Create a tag pointing to the committed branch
        client.createTag(repository, tagName, testBranchName)

        when:
        // Read from the tag
        def tagPath = lakeFSpath("lakefs://$repository/$tagName/$objectPath")

        then:
        noExceptionThrown()
        Files.exists(tagPath)
        new String(Files.readAllBytes(tagPath)) == TEXT

        cleanup:
        try {
            client.deleteTag(repository, tagName)
        } catch (Exception ignored) {
        }
        try {
            client.deleteBranch(repository, testBranchName)
        } catch (Exception ignored) {
        }

        where:
        transferMode << transferModes
    }

    def 'should check tag exists using refExists'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def tagName = "test-tag-${UUID.randomUUID().toString().substring(0, 8)}"
        def client = new LakeFSSDKClient(config.lakefs)

        and:
        // Create a tag pointing to the main branch (no need for file, just testing tag existence)
        client.createTag(repository, tagName, TEST_MAIN_BRANCH_NAME)

        expect:
        client.tagExists(repository, tagName)
        client.refExists(repository, tagName)
        !client.branchExists(repository, tagName) // tag is not a branch

        cleanup:
        try {
            client.deleteTag(repository, tagName)
        } catch (Exception ignored) {
        }

        where:
        transferMode << transferModes
    }

    def 'should not throw when accessing tag with autoCreateBranch disabled'() {
        given:
        def repository = TEST_REPO_NAME
        def tagName = "test-tag-${UUID.randomUUID().toString().substring(0, 8)}"
        def client = new LakeFSSDKClient(config.lakefs)

        and:
        // Create a tag pointing to the main branch
        client.createTag(repository, tagName, TEST_MAIN_BRANCH_NAME)

        and:
        def configWithoutAutoCreate = [
                lakefs: [
                        accessKey       : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey       : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl          : System.getenv('LAKEFS_API_URL'),
                        autoCreateBranch: false
                ],
                google: [
                        region : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project: System.getenv('GOOGLE_PROJECT') ?: ''
                ]
        ]
        Global.config = configWithoutAutoCreate
        Global.session = Mock(Session) { getConfig() >> configWithoutAutoCreate }

        when:
        // This should work even with autoCreateBranch=false because the tag exists
        def path = lakeFSpath("lakefs://$repository/$tagName/")

        then:
        noExceptionThrown()
        Files.exists(path)

        cleanup:
        try {
            client.deleteTag(repository, tagName)
        } catch (Exception ignored) {
        }
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
    }

    def 'should read file from a commit ID'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def testBranchName = "commit-test-branch-${UUID.randomUUID().toString().substring(0, 8)}"
        def client = new LakeFSSDKClient(config.lakefs)

        and:
        // Create a separate branch for this test
        client.createBranch(repository, testBranchName, TEST_MAIN_BRANCH_NAME)

        and:
        // Create a file on the test branch
        def objectPath = "commit-test-file.txt"
        def TEXT = "Hello from commit test!"
        def branchPath = lakeFSpath("lakefs://$repository/$testBranchName/$objectPath")
        Files.write(branchPath, TEXT.bytes)

        and:
        // Commit the changes and get the commit ID
        def commitId = client.commit(repository, testBranchName, "Add test file for commit ID test")

        when:
        // Read from the commit ID directly
        def commitPath = lakeFSpath("lakefs://$repository/$commitId/$objectPath")

        then:
        noExceptionThrown()
        Files.exists(commitPath)
        new String(Files.readAllBytes(commitPath)) == TEXT

        cleanup:
        try {
            client.deleteBranch(repository, testBranchName)
        } catch (Exception ignored) {
        }

        where:
        transferMode << transferModes
    }

    def 'refExists should correctly identify branch, tag, and commit'() {
        given:
        setupConfig(transferMode)
        def repository = TEST_REPO_NAME
        def tagName = "test-tag-${UUID.randomUUID().toString().substring(0, 8)}"

        and:
        def client = new LakeFSSDKClient(config.lakefs)
        def commitId = client.branchesApi.getBranch(repository, TEST_MAIN_BRANCH_NAME).execute().commitId

        and:
        // Create a tag for testing
        client.createTag(repository, tagName, TEST_MAIN_BRANCH_NAME)

        expect:
        // Branch exists
        client.branchExists(repository, TEST_MAIN_BRANCH_NAME)
        client.refExists(repository, TEST_MAIN_BRANCH_NAME)

        // Tag exists
        client.tagExists(repository, tagName)
        client.refExists(repository, tagName)
        !client.branchExists(repository, tagName)

        // Commit ID exists
        client.refExists(repository, commitId)
        !client.branchExists(repository, commitId)
        !client.tagExists(repository, commitId)

        // Non-existent ref
        !client.refExists(repository, "non-existent-ref-${UUID.randomUUID()}")

        cleanup:
        try {
            client.deleteTag(repository, tagName)
        } catch (Exception ignored) {
        }

        where:
        transferMode << transferModes
    }

    def 'should throw IllegalArgumentException when autoCreateBranchSource is a tag'() {
        given:
        def repository = TEST_REPO_NAME
        def tagName = "test-tag-${UUID.randomUUID().toString().substring(0, 8)}"
        def newBranchName = "should-fail-branch-${UUID.randomUUID().toString().substring(0, 8)}"
        def client = new LakeFSSDKClient(config.lakefs)

        and:
        // Create a tag to use as (invalid) source
        client.createTag(repository, tagName, TEST_MAIN_BRANCH_NAME)

        and:
        def configWithTagAsSource = [
                lakefs: [
                        accessKey             : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey             : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl                : System.getenv('LAKEFS_API_URL'),
                        autoCreateBranch      : true,
                        autoCreateBranchSource: tagName  // Using a tag as source - should fail
                ],
                google: [
                        region : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project: System.getenv('GOOGLE_PROJECT') ?: ''
                ]
        ]
        Global.config = configWithTagAsSource
        Global.session = Mock(Session) { getConfig() >> configWithTagAsSource }

        when:
        lakeFSpath("lakefs://$repository/$newBranchName/test.txt")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("'$tagName' is a tag, not a branch")
        e.message.contains("Tags are immutable references")

        cleanup:
        try {
            client.deleteTag(repository, tagName)
        } catch (Exception ignored) {
        }
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
    }

    def 'should throw NoSuchFileException when autoCreateBranchSource does not exist'() {
        given:
        def nonExistentSource = "non-existent-source-${UUID.randomUUID().toString().substring(0, 8)}"
        def newBranchName = "should-fail-branch-${UUID.randomUUID().toString().substring(0, 8)}"
        def configWithBadSource = [
                lakefs: [
                        accessKey             : System.getenv('LAKEFS_ACCESS_KEY'),
                        secretKey             : System.getenv('LAKEFS_SECRET_KEY'),
                        apiUrl                : System.getenv('LAKEFS_API_URL'),
                        autoCreateBranch      : true,
                        autoCreateBranchSource: nonExistentSource  // Non-existent source - should fail
                ],
                google: [
                        region : System.getenv('GOOGLE_REGION') ?: 'europe-west1',
                        project: System.getenv('GOOGLE_PROJECT') ?: ''
                ]
        ]
        Global.config = configWithBadSource
        Global.session = Mock(Session) { getConfig() >> configWithBadSource }

        when:
        lakeFSpath("lakefs://$TEST_REPO_NAME/$newBranchName/test.txt")

        then:
        def e = thrown(NoSuchFileException)
        e.message.contains("branch '$nonExistentSource' does not exist")

        cleanup:
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
    }

}
