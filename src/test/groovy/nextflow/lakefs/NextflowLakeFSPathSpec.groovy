package nextflow.lakefs

import nextflow.Global
import nextflow.file.FileHelper
import spock.lang.Specification
import spock.lang.Unroll

class NextflowLakeFSPathSpec extends Specification {
    def setupSpec() {
        // Initialize the NextflowLakeFSPathFactory with a dummy config for testing
        // This is needed because FileHelper.asPath internally uses the factory
        // and the factory expects Global.config to be set.
        Global.config = [
                lakefs: [
                        apiUrl   : "http://localhost:8000/api/v1/",
                        accessKey: "test-key",
                        secretKey: "test-secret"
                ]
        ]
    }


//    @Unroll
//    def 'should convert to uri string' () {
//
//        expect:
//        FileHelper.asPath(PATH).toUriString() == STR
//
//
//        where:
//        _ | PATH                | STR
//        _ | 'lakefs://foo/main'          | 'lakefs://foo/main/'
//        _ | 'lakefs://foo/bar'      | 'lakefs://foo/bar/'
//        _ | 'lakefs://foo/bar/b a r'    | 'lakefs://foo/bar/b a r'
//        _ | 'lakefs://f o o/bar'    | 'lakefs://f o o/bar/'
//        _ | 'lakefs://f_o_o/bar'    | 'lakefs://f_o_o/bar/'
//
//    }

//    @Unroll
//    def 'should convert to string' () {
//
//        expect:
//        FileHelper.asPath(PATH).toString() == STR
//
//        where:
//        _ | PATH                | STR
//        _ | 'lakefs://infra-testing/main/output/meta_entity/local_meta_entity_dataset.parquet'          | '/foo/'
//        _ | 'lakefs://foo/bar'      | '/foo/bar/'
//        _ | 'lakefs://foo/bar/b a r'    | '/foo/bar/b a r'
//        _ | 'lakefs://f o o/bar'    | '/f o o/bar/'
//        _ | 'lakefs://f_o_o/bar'    | '/f_o_o/bar/'
//
//    }

    def 'should check equals and hashcode' () {
        given:
        def path1 = FileHelper.asPath('lakefs://foo/some/foo.txt')
        def path2 = FileHelper.asPath('lakefs://foo/some/foo.txt')
        def path3 = FileHelper.asPath('lakefs://foo/some/bar.txt')
        def path4 = FileHelper.asPath('lakefs://bar/some/foo.txt')

        expect:
        path1 == path2
        path1 != path3
        path3 != path4
        and:
        path1.hashCode() == path2.hashCode()
        path1.hashCode() != path3.hashCode()
        path3.hashCode() != path4.hashCode()
    }

    @Unroll
    def 'should determine bucket name' () {
        def path = FileHelper.asPath(URI_PATH)
        expect:
        path.fileSystem.repository == BUCKET
        path.fileSystem.ref == REF

        where:
        URI_PATH            | BUCKET | REF
//        'lakefs://'            | null | null
//        'lakefs://foo'         | 'foo' | null
//        'lakefs://foo/'        | 'foo' | null
        'lakefs://foo/bar'     | 'foo' | "bar"
        'lakefs://infra-testing/main/output/meta_entity/local_meta_entity_dataset.parquet' | "infra-testing" | "main"
    }

    @Unroll
    def 'should normalise path' () {
        expect:
        FileHelper.asPath(PATH).normalize() == FileHelper.asPath(EXPECTED)

        where:
        PATH                        | EXPECTED
        'lakefs://foo/ref/.'                  | 'lakefs://foo/ref/'
        'lakefs://foo/x/y/z.txt'        | 'lakefs://foo/x/y/z.txt'
        'lakefs://foo/x/y/./z.txt'      | 'lakefs://foo/x/y/z.txt'
        'lakefs://foo/x/y/../z.txt'     | 'lakefs://foo/x/z.txt'
        'lakefs://foo/ref/x/y/../../z.txt'  | 'lakefs://foo/ref/z.txt'
        'lakefs://foo/x/y//z.txt'       | 'lakefs://foo/x/y/z.txt'
        'lakefs://foo/x/./z.txt'          | 'lakefs://foo/x/z.txt'
    }

    @Unroll
    def 'should relativize path correctly' () {
        expect:
        def filePath = FileHelper.asPath(PATH)
        def folderPath = FileHelper.asPath(RELATIVE)
        folderPath.relativize(filePath).toString() == EXPECTED

        where:
        PATH                            | RELATIVE | EXPECTED
        'lakefs://foo/x/y/z.txt'        | 'lakefs://foo/x/y'  | '/z.txt'
//        'lakefs://foo/x/y/./z.txt'      | 'lakefs://foo/x/y/' | 'z.txt'
//        'lakefs://foo/x/y/../z.txt'     | 'lakefs://foo/x/' | 'z.txt'
//        'lakefs://foo/x/y/../../z.txt'  | 'lakefs://foo/z.txt'
//        'lakefs://foo/x/y//z.txt'       | 'lakefs://foo/x/y/' | '/foo/x/y/z.txt'
//        'lakefs://foo/./z.txt'          | 'lakefs://foo/z.txt'

    }

}
