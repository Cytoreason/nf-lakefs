package nextflow.lakefs

import io.lakefs.clients.sdk.ApiException
import io.lakefs.clients.sdk.BranchesApi
import io.lakefs.clients.sdk.model.BranchCreation
import io.lakefs.clients.sdk.model.Ref
import spock.lang.Specification

class LakeFSSDKClientSpec extends Specification {

    def 'branchExists should return true when branch exists'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        def result = client.branchExists('repo', 'main')

        then:
        1 * mockBranchesApi.getBranch('repo', 'main') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> new Ref()
        }
        result == true
    }

    def 'branchExists should return false when branch does not exist'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        def result = client.branchExists('repo', 'non-existent')

        then:
        1 * mockBranchesApi.getBranch('repo', 'non-existent') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        result == false
    }

    def 'branchExists should throw exception for non-404 errors'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        client.branchExists('repo', 'main')

        then:
        1 * mockBranchesApi.getBranch('repo', 'main') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(500, 'Internal Server Error') }
        }
        thrown(ApiException)
    }

    def 'createBranch should create branch from source'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        client.createBranch('repo', 'new-branch', 'main')

        then:
        1 * mockBranchesApi.createBranch('repo', { BranchCreation bc ->
            bc.name == 'new-branch' && bc.source == 'main'
        }) >> Mock(BranchesApi.APIcreateBranchRequest) {
            execute() >> 'new-branch'
        }
    }

    def 'createBranchIfNotExists should create branch when it does not exist'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        client.createBranchIfNotExists('repo', 'new-branch', 'main')

        then:
        1 * mockBranchesApi.getBranch('repo', 'new-branch') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        1 * mockBranchesApi.createBranch('repo', _) >> Mock(BranchesApi.APIcreateBranchRequest) {
            execute() >> 'new-branch'
        }
    }

    def 'createBranchIfNotExists should not create branch when it exists'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        client.createBranchIfNotExists('repo', 'existing-branch', 'main')

        then:
        1 * mockBranchesApi.getBranch('repo', 'existing-branch') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> new Ref()
        }
        0 * mockBranchesApi.createBranch(_, _)
    }
}
