package nextflow.lakefs

import groovy.util.logging.Slf4j
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest
import software.amazon.awssdk.services.s3.model.ObjectAttributes

/**
 * Reads the server-computed crc64nvme of an S3 object via the AWS SDK v2.
 *
 * The actual transfer (upload/download) goes through nf-amazon's S3 NIO — symmetric with how GCS goes through
 * nf-google — so no custom S3 transfer code lives here. This only fetches the checksum to link into lakeFS,
 * because S3 (unlike GCS) exposes no checksum through NIO file attributes. The object must have been stored
 * with a crc64nvme, which we ensure by running the SDK with {@code requestChecksumCalculation=when_required}
 * so S3 applies its server-side crc64nvme default — see {@code NextflowLakeFSPlugin}.
 *
 * The SDK is provided at runtime by nf-amazon (which now bundles AWS SDK v2); it is a {@code compileOnly}
 * dependency here.
 */
@Slf4j
class S3PhysicalUploader {

    /** Return the object's full-object crc64nvme as hex (falls back to the unquoted ETag if none was stored). */
    static String crc64nvmeHex(String s3Address) {
        def (bucket, key) = bucketAndKey(s3Address)
        // don't validate the response checksum (that would need the CRT lib) — we only read the stored value
        def s3 = S3Client.builder()
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build()
        try {
            def attrs = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
                    .bucket(bucket).key(key)
                    .objectAttributes(ObjectAttributes.CHECKSUM)
                    .build())
            def b64 = attrs.checksum()?.checksumCRC64NVME()
            if (b64)
                return base64ToHex(b64)
            // No crc64nvme stored — e.g. the upload didn't run under requestChecksumCalculation=when_required.
            // Fall back to the ETag, but warn: for a multipart object the ETag is "<md5>-<parts>", which is not a
            // usable content checksum, so this signals a misconfiguration rather than a normal path.
            def etag = attrs.eTag()?.replaceAll('"', '')
            log.warn("No crc64nvme for s3://${bucket}/${key}; linking ETag '${etag}' instead. " +
                    "Check that the runtime nf-amazon uses AWS SDK v2 (Nextflow >= 25.10) and that " +
                    "aws.requestChecksumCalculation=when_required took effect.")
            return etag
        } finally {
            s3.close()
        }
    }

    private static List<String> bucketAndKey(String s3Address) {
        def uri = URI.create(s3Address)
        return [uri.authority, uri.path.replaceFirst('^/', '')]
    }

    private static String base64ToHex(String b64) {
        return Base64.decoder.decode(b64).collect { String.format('%02x', it & 0xff) }.join()
    }
}
