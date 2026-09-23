package nextflow.lakefs

import nextflow.Global
import spock.lang.Specification

class LakeFSExtensionSpec extends Specification {

    def setup() {
        LakeFSSDKClient.metaClass.refExists = { String repo, String ref -> true }
    }

    def cleanup() {
        LakeFSSDKClient.metaClass = null
    }

    def 'lakefsCommit should resolve the path and commit via its provider'() {
        given:
        Global.config = [lakefs: [apiUrl: "http://localhost:8000/api/v1/", accessKey: "key", secretKey: "pass"]]
        def path = NextflowLakeFSPathFactory.create('lakefs://ext-repo/ext-branch/some/object.txt')
        def mockClient = Mock(LakeFSSDKClient)
        ((NextflowLakeFSFileSystemProvider) path.getFileSystem().provider()).lakeFSClient = mockClient
        def extension = new LakeFSExtension()

        when:
        def commitId = extension.lakefsCommit('lakefs://ext-repo/ext-branch/some/object.txt', 'my commit message')

        then:
        1 * mockClient.commit('ext-repo', 'ext-branch', 'my commit message') >> 'commit-id-ext'
        commitId == 'commit-id-ext'
    }
}
