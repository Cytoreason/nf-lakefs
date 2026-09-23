package nextflow.lakefs

import nextflow.Global
import spock.lang.Specification

import java.nio.file.Paths

class LakeFSCommitSupportSpec extends Specification {

    def setup() {
        // Mock the LakeFSSDKClient to bypass ref existence check when creating a filesystem via a String path
        LakeFSSDKClient.metaClass.refExists = { String repo, String ref -> true }
    }

    def cleanup() {
        LakeFSSDKClient.metaClass = null
    }

    private NextflowLakeFSPath lakeFSPath(LakeFSSDKClient mockClient, String repository = 'repo', String branch = 'branch') {
        def provider = new NextflowLakeFSFileSystemProvider()
        provider.lakeFSClient = mockClient
        def fs = new NextflowLakeFSFileSystem(provider, URI.create("lakefs://$repository/$branch/"), repository, branch)
        return new NextflowLakeFSPath(fs, 'some/object.txt')
    }

    def 'commit(Path, message) should commit via the path\'s provider and return the commit id'() {
        given:
        def mockClient = Mock(LakeFSSDKClient)
        def path = lakeFSPath(mockClient, 'my-repo', 'my-branch')

        when:
        def commitId = LakeFSCommitSupport.commit(path, 'my commit message')

        then:
        1 * mockClient.commit('my-repo', 'my-branch', 'my commit message') >> 'commit-id-123'
        commitId == 'commit-id-123'
    }

    def 'commit(Path, message) should throw when path is not a lakefs:// path'() {
        when:
        LakeFSCommitSupport.commit(Paths.get('/tmp/foo.txt'), 'my commit message')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Not a lakefs:// path')
    }

    def 'commit(String, message) should resolve the path and commit via its provider'() {
        given:
        Global.config = [lakefs: [apiUrl: "http://localhost:8000/api/v1/", accessKey: "key", secretKey: "pass"]]
        def path = NextflowLakeFSPathFactory.create('lakefs://my-repo/my-branch/some/object.txt')
        def mockClient = Mock(LakeFSSDKClient)
        ((NextflowLakeFSFileSystemProvider) path.getFileSystem().provider()).lakeFSClient = mockClient

        when:
        def commitId = LakeFSCommitSupport.commit('lakefs://my-repo/my-branch/some/object.txt', 'my commit message')

        then:
        1 * mockClient.commit('my-repo', 'my-branch', 'my commit message') >> 'commit-id-456'
        commitId == 'commit-id-456'
    }
}
