package nextflow.lakefs

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit coverage for S3ChecksumReader.bucketAndKey — no AWS needed.
 *
 * Regression guard: nf-amazon's S3Path.toUri() renders standard AWS as "s3:///<bucket>/<key>" (triple slash,
 * bucket as the first PATH segment), which is what the physical_path link path actually passes. The original
 * parser read uri.authority and produced a null bucket -> GetObjectAttributes failed with
 * "Parameter 'Bucket' must not be null". Both URI shapes must parse correctly.
 */
class S3ChecksumReaderParseSpec extends Specification {

    @Unroll
    def 'parses bucket=#bucket key=#key from #addr'() {
        expect:
        S3ChecksumReader.bucketAndKey(addr) == [bucket, key]

        where:
        addr                                         || bucket   | key
        's3:///my-bucket/a/b/c.bin'                  || 'my-bucket' | 'a/b/c.bin'   // nf-amazon S3Path.toUri(), no endpoint
        's3://my-bucket/a/b/c.bin'                   || 'my-bucket' | 'a/b/c.bin'   // plain s3 URI (authority style)
        's3://my-bucket/object'                      || 'my-bucket' | 'object'
        's3:///my-bucket/object'                     || 'my-bucket' | 'object'
    }

    def 'throws on an address without a key'() {
        when:
        S3ChecksumReader.bucketAndKey('s3://only-bucket')
        then:
        thrown(IllegalArgumentException)
    }
}
