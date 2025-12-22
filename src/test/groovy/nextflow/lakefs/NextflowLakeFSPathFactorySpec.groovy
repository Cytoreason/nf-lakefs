package nextflow.lakefs

import nextflow.Global
import spock.lang.Specification


class NextflowLakeFSPathFactorySpec extends Specification {

    def setup() {
        // Mock the LakeFSSDKClient to bypass ref existence check
        LakeFSSDKClient.metaClass.refExists = { String repo, String ref -> true }
    }

    def cleanup() {
        // Reset metaClass
        LakeFSSDKClient.metaClass = null
    }

    def 'should parse lakefs paths'() {

        when:
        Global.config = [lakefs:[apiUrl:"http://localhost:8000/api/v1/", accessKey:"key", secretKey:"pass"]]
        def path = NextflowLakeFSPathFactory.parse(LAKEFS_PATH)
        then:
        path instanceof NextflowLakeFSPath
        with(path as NextflowLakeFSPath) {
            ((NextflowLakeFSFileSystem) getFileSystem()).getRepository() == BUCKET
            ref() == REF
            getObjectPath() == KEY
        }

        when:
        def str = NextflowLakeFSPathFactory.getUriString(path)
        then:
        str == LAKEFS_PATH


        where:
        LAKEFS_PATH                                             | BUCKET     | REF   | KEY
        'lakefs://cbcrg-eu/raw/x_r1.fq'                         | 'cbcrg-eu' | 'raw' | 'x_r1.fq'
//        'lakefs://cbcrg-eu/raw/**_R1*{fastq,fq,fastq.gz,fq.gz}' | 'cbcrg-eu' | 'raw' | '**_R1*{fastq,fq,fastq.gz,fq.gz}'

    }

    def 'should ignore double slashes'() {
        when:
        Global.config = [lakefs:[apiUrl:"http://localhost:8000/api/v1/", accessKey:"key", secretKey:"pass"]]
        def path = NextflowLakeFSPathFactory.parse('lakefs://cbcrg-eu/raw//x_r1.fq')
        then:
        NextflowLakeFSPathFactory.getUriString(path) == 'lakefs://cbcrg-eu/raw/x_r1.fq'
    }
}
