package nextflow.lakefs

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import spock.lang.Requires
import spock.lang.Specification

/**
 * Verifies the s3-specific half of physical_path support.
 *
 * Under the scheme-agnostic design, S3 transfers go through nf-amazon's S3 NIO (symmetric with GCS via
 * nf-google) — that generic copy/read path is covered by the GCS physical_path tests in {@link LakeFSNioSpec}.
 * The only s3-specific code is reading the server checksum back, so this spec checks exactly that, plus the
 * one assumption it rests on: that an object uploaded by a DEFAULT AWS SDK v2 client (which is all nf-amazon
 * is — it sets no checksum config) is stored with a full-object crc64nvme once requestChecksumCalculation is
 * set to when_required — precisely what {@link NextflowLakeFSPlugin} does. A default client is therefore a
 * faithful stand-in for nf-amazon, so this needs no plugin loading and runs in the single-plugin test harness.
 *
 * Gated on AWS creds + a writable s3 prefix, e.g. AWS_S3_TEST_PREFIX=s3://p13-cr-data-assets/test
 */
@Requires({ System.getenv('AWS_ACCESS_KEY_ID') && System.getenv('AWS_S3_TEST_PREFIX') })
class S3PhysicalUploaderSpec extends Specification {

    def 'a default-client PUT stores crc64nvme when requestChecksumCalculation=when_required, and crc64nvmeHex reads it'() {
        given: 'the global SDK setting the plugin applies, so S3 falls back to its server-side crc64nvme default'
        def previous = System.getProperty('aws.requestChecksumCalculation')
        System.setProperty('aws.requestChecksumCalculation', 'when_required')
        and: 'a fresh object address under the writable test prefix'
        def address = "${System.getenv('AWS_S3_TEST_PREFIX').replaceAll('/$', '')}/nf-lakefs-it/crc64-${UUID.randomUUID()}.bin"
        def uri = URI.create(address)
        def bucket = uri.authority
        def key = uri.path.replaceFirst('^/', '')
        def s3 = S3Client.create()   // a default client — exactly what nf-amazon builds (no checksum config)

        when: 'upload with the default client, the same way nf-amazon would'
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes('hello crc64nvme'.bytes))

        then: 'our helper reads back a full-object crc64nvme (16 hex) — not crc32 (8 hex) or an md5 ETag (32 hex)'
        S3PhysicalUploader.crc64nvmeHex(address) ==~ /[0-9a-f]{16}/

        cleanup:
        try { s3?.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) } catch (ignored) {}
        s3?.close()
        if (previous == null) System.clearProperty('aws.requestChecksumCalculation')
        else System.setProperty('aws.requestChecksumCalculation', previous)
    }
}
