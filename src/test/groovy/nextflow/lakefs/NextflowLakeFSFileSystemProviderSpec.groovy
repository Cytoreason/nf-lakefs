package nextflow.lakefs

import spock.lang.Specification

import java.nio.file.NoSuchFileException

class NextflowLakeFSFileSystemProviderSpec extends Specification {

    def 'ensureRefExists should not throw when ref exists (branch)'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = false

        when:
        provider.ensureRefExists('repo', 'main')

        then:
        1 * mockClient.refExists('repo', 'main') >> true
        noExceptionThrown()
    }

    def 'ensureRefExists should not throw when ref exists (tag)'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = false

        when:
        provider.ensureRefExists('repo', 'v1.0.0')

        then:
        1 * mockClient.refExists('repo', 'v1.0.0') >> true
        noExceptionThrown()
    }

    def 'ensureRefExists should not throw when ref exists (commit ID)'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = false

        when:
        provider.ensureRefExists('repo', 'abc123def456')

        then:
        1 * mockClient.refExists('repo', 'abc123def456') >> true
        noExceptionThrown()
    }

    def 'ensureRefExists should throw NoSuchFileException when ref does not exist and autoCreateBranch is false'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = false

        when:
        provider.ensureRefExists('repo', 'non-existent')

        then:
        1 * mockClient.refExists('repo', 'non-existent') >> false
        def e = thrown(NoSuchFileException)
        e.message.contains("Reference 'non-existent' does not exist")
        e.message.contains("Enable 'autoCreateBranch'")
    }

    def 'ensureRefExists should create branch when autoCreateBranch is enabled and ref does not exist'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = true
        provider.autoCreateBranchSource = 'main'

        when:
        provider.ensureRefExists('repo', 'new-branch')

        then:
        1 * mockClient.refExists('repo', 'new-branch') >> false
        1 * mockClient.tagExists('repo', 'main') >> false
        1 * mockClient.branchExists('repo', 'main') >> true
        1 * mockClient.createBranch('repo', 'new-branch', 'main')
        noExceptionThrown()
    }

    def 'ensureRefExists should use custom source branch'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = true
        provider.autoCreateBranchSource = 'develop'

        when:
        provider.ensureRefExists('repo', 'feature-branch')

        then:
        1 * mockClient.refExists('repo', 'feature-branch') >> false
        1 * mockClient.tagExists('repo', 'develop') >> false
        1 * mockClient.branchExists('repo', 'develop') >> true
        1 * mockClient.createBranch('repo', 'feature-branch', 'develop')
    }

    def 'ensureRefExists should throw IllegalArgumentException when autoCreateBranchSource is a tag'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = true
        provider.autoCreateBranchSource = 'v1.0.0'

        when:
        provider.ensureRefExists('repo', 'new-branch')

        then:
        1 * mockClient.refExists('repo', 'new-branch') >> false
        1 * mockClient.tagExists('repo', 'v1.0.0') >> true
        0 * mockClient.createBranch(_, _, _)
        def e = thrown(IllegalArgumentException)
        e.message.contains("'v1.0.0' is a tag, not a branch")
        e.message.contains("Tags are immutable references")
    }

    def 'ensureRefExists should throw NoSuchFileException when autoCreateBranchSource does not exist'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = true
        provider.autoCreateBranchSource = 'non-existent-branch'

        when:
        provider.ensureRefExists('repo', 'new-branch')

        then:
        1 * mockClient.refExists('repo', 'new-branch') >> false
        1 * mockClient.tagExists('repo', 'non-existent-branch') >> false
        1 * mockClient.branchExists('repo', 'non-existent-branch') >> false
        0 * mockClient.createBranch(_, _, _)
        def e = thrown(NoSuchFileException)
        e.message.contains("branch 'non-existent-branch' does not exist")
    }

    def 'validateAutoCreateBranchSource should throw IllegalArgumentException when source is a tag'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranchSource = 'release-tag'

        when:
        provider.validateAutoCreateBranchSource('repo')

        then:
        1 * mockClient.tagExists('repo', 'release-tag') >> true
        def e = thrown(IllegalArgumentException)
        e.message.contains("'release-tag' is a tag, not a branch")
    }

    def 'validateAutoCreateBranchSource should throw NoSuchFileException when source branch does not exist'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranchSource = 'missing-branch'

        when:
        provider.validateAutoCreateBranchSource('repo')

        then:
        1 * mockClient.tagExists('repo', 'missing-branch') >> false
        1 * mockClient.branchExists('repo', 'missing-branch') >> false
        def e = thrown(NoSuchFileException)
        e.message.contains("branch 'missing-branch' does not exist")
    }

    def 'validateAutoCreateBranchSource should not throw when source is a valid branch'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranchSource = 'main'

        when:
        provider.validateAutoCreateBranchSource('repo')

        then:
        1 * mockClient.tagExists('repo', 'main') >> false
        1 * mockClient.branchExists('repo', 'main') >> true
        noExceptionThrown()
    }

    def 'config should set autoCreateBranch and autoCreateBranchSource'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()

        expect:
        provider.autoCreateBranch == false
        provider.autoCreateBranchSource == 'main'
    }

    def 'getLinkMetadata (signed_url) extracts crc32c from x-goog-hash and returns it as hex'() {
        given: 'GCS upload response headers (crc32c base64 h3UyDA== == hex 8775320c)'
        def headers = [
                'x-goog-hash'                 : 'crc32c=h3UyDA==; md5=lkEaTXSekK6TTpoaPHMGgA==',
                'x-goog-stored-content-length': '20971520',
                'ETag'                        : '"96411a4d749e90ae934e9a1a3c730680"',
        ]

        when:
        def meta = NextflowLakeFSFileSystemProvider.CloudProvidersSpecificFactories.getLinkMetadata(headers)

        then:
        meta.checksum == '8775320c'
        meta.size == 20971520L
    }

    def 'getLinkMetadata (signed_url) matches the x-goog-hash header case-insensitively'() {
        given:
        def headers = [
                'X-Goog-Hash'                 : 'crc32c=h3UyDA==',
                'x-goog-stored-content-length': '20971520',
        ]

        when:
        def meta = NextflowLakeFSFileSystemProvider.CloudProvidersSpecificFactories.getLinkMetadata(headers)

        then:
        meta.checksum == '8775320c'
    }

    def 'getLinkMetadata (signed_url) falls back to the unquoted ETag when x-goog-hash is absent'() {
        given:
        def headers = [
                'x-goog-stored-content-length': '20971520',
                'ETag'                        : '"96411a4d749e90ae934e9a1a3c730680"',
        ]

        when:
        def meta = NextflowLakeFSFileSystemProvider.CloudProvidersSpecificFactories.getLinkMetadata(headers)

        then:
        meta.checksum == '96411a4d749e90ae934e9a1a3c730680'
        meta.size == 20971520L
    }
}
