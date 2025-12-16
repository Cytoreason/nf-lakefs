package nextflow.lakefs

import spock.lang.Specification

import java.nio.file.NoSuchFileException

class NextflowLakeFSFileSystemProviderSpec extends Specification {

    def 'ensureBranchExists should not throw when branch exists'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = false

        when:
        provider.ensureBranchExists('repo', 'main')

        then:
        1 * mockClient.branchExists('repo', 'main') >> true
        noExceptionThrown()
    }

    def 'ensureBranchExists should throw NoSuchFileException when branch does not exist and autoCreateBranch is false'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = false

        when:
        provider.ensureBranchExists('repo', 'non-existent')

        then:
        1 * mockClient.branchExists('repo', 'non-existent') >> false
        def e = thrown(NoSuchFileException)
        e.message.contains("Branch 'non-existent' does not exist")
        e.message.contains("Enable 'autoCreateBranch'")
    }

    def 'ensureBranchExists should create branch when autoCreateBranch is enabled'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = true
        provider.autoCreateBranchSource = 'main'

        when:
        provider.ensureBranchExists('repo', 'new-branch')

        then:
        1 * mockClient.branchExists('repo', 'new-branch') >> false
        1 * mockClient.createBranch('repo', 'new-branch', 'main')
        noExceptionThrown()
    }

    def 'ensureBranchExists should use custom source branch'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()
        def mockClient = Mock(LakeFSSDKClient)
        provider.lakeFSClient = mockClient
        provider.autoCreateBranch = true
        provider.autoCreateBranchSource = 'develop'

        when:
        provider.ensureBranchExists('repo', 'feature-branch')

        then:
        1 * mockClient.branchExists('repo', 'feature-branch') >> false
        1 * mockClient.createBranch('repo', 'feature-branch', 'develop')
    }

    def 'config should set autoCreateBranch and autoCreateBranchSource'() {
        given:
        def provider = new NextflowLakeFSFileSystemProvider()

        expect:
        provider.autoCreateBranch == false
        provider.autoCreateBranchSource == 'main'
    }
}
