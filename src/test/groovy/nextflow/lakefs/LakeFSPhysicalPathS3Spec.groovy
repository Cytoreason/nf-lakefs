package nextflow.lakefs

import io.lakefs.clients.sdk.ApiClient
import io.lakefs.clients.sdk.ObjectsApi
import nextflow.Global
import nextflow.Session
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files

/**
 * End-to-end physical_path test against an S3-backed lakeFS repository: exercises the full
 * lakefs:// -> nf-amazon S3 NIO -> S3 write + crc64nvme link-back path in-harness.
 *
 * This is the one path that needs nf-amazon's S3 provider loaded. It runs in the unit-test harness because
 * src/test/resources/META-INF/services/nextflow.file.FileSystemPathFactory registers S3PathFactory via JDK
 * ServiceLoader — nf-amazon declares it only in its pf4j extensions.idx, which the harness's default plugin
 * manager doesn't read. build.gradle also sets aws.requestChecksumCalculation=when_required (mirroring
 * {@link NextflowLakeFSPlugin#start}) so S3 stores the server-side crc64nvme that physical_path links.
 *
 * Gated on AWS credentials + a dedicated S3-backed lakeFS instance (LAKEFS_S3_*), kept separate from the
 * primary LAKEFS_* config so it can target an S3 instance without clobbering the (often GCS) default. The
 * branch must already exist. Skipped entirely when the env vars are absent.
 */
@Requires({
    System.getenv('AWS_ACCESS_KEY_ID') &&
            System.getenv('AWS_SECRET_ACCESS_KEY') &&
            System.getenv('LAKEFS_S3_API_URL') &&
            System.getenv('LAKEFS_S3_ACCESS_KEY') &&
            System.getenv('LAKEFS_S3_SECRET_KEY') &&
            System.getenv('LAKEFS_S3_TEST_REPO') &&
            System.getenv('LAKEFS_S3_TEST_BRANCH')
})
class LakeFSPhysicalPathS3Spec extends Specification implements LakeFSBaseSpec {

    static final String REPO = System.getenv('LAKEFS_S3_TEST_REPO')
    static final String BRANCH = System.getenv('LAKEFS_S3_TEST_BRANCH')

    @Shared
    private ObjectsApi lakeFSClient0
    ObjectsApi getLakeFSClient() { lakeFSClient0 }

    @Shared
    def config = [
            lakefs: [
                    accessKey   : System.getenv('LAKEFS_S3_ACCESS_KEY'),
                    secretKey   : System.getenv('LAKEFS_S3_SECRET_KEY'),
                    apiUrl      : System.getenv('LAKEFS_S3_API_URL'),
                    transferMode: 'physical_path'
            ],
            // consumed by nf-amazon's S3PathFactory (via Global.config.aws); falls back to the AWS default
            // credential chain when these are absent
            aws   : [
                    accessKey: System.getenv('AWS_ACCESS_KEY_ID'),
                    secretKey: System.getenv('AWS_SECRET_ACCESS_KEY'),
                    region   : System.getenv('AWS_REGION') ?: 'eu-central-1'
            ]
    ]

    def setup() {
        Global.config = config
        Global.session = Mock(Session) { getConfig() >> config }
        def apiClient = new ApiClient()
        apiClient.setBasePath(System.getenv('LAKEFS_S3_API_URL'))
        apiClient.setUsername(System.getenv('LAKEFS_S3_ACCESS_KEY'))
        apiClient.setPassword(System.getenv('LAKEFS_S3_SECRET_KEY'))
        this.lakeFSClient0 = new ObjectsApi(apiClient)
    }

    def 'physical_path: write to an S3-backed repo, link crc64nvme, read back'() {
        given:
        def objectPath = "nf-lakefs-it/physical-${UUID.randomUUID()}.txt"
        def TEXT = "hello physical_path on s3\n"
        def path = lakeFSpath("lakefs://$REPO/$BRANCH/$objectPath")

        when: 'the write goes lakefs:// -> nf-amazon S3 NIO -> S3, then links into lakeFS on channel close'
        Files.write(path, TEXT.bytes)

        then: 'the object exists in lakeFS and was linked with a full-object crc64nvme (16 hex)'
        existsPath(REPO, BRANCH, objectPath)
        lakeFSClient.statObject(REPO, BRANCH, objectPath).execute().getChecksum() ==~ /[0-9a-f]{16}/

        and: 'the content round-trips'
        readObject(path) == TEXT

        cleanup:
        deleteObject(REPO, BRANCH, objectPath)
    }
}
