package nextflow.lakefs

import io.lakefs.clients.sdk.ApiException
import io.lakefs.clients.sdk.BranchesApi
import io.lakefs.clients.sdk.ObjectsApi
import io.lakefs.clients.sdk.TagsApi
import io.lakefs.clients.sdk.model.BranchCreation
import io.lakefs.clients.sdk.model.ObjectStatsList
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

    def 'branchExists should return false for invalid branch name (HTTP 400)'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        def result = client.branchExists('repo', '1.2.3')

        then:
        1 * mockBranchesApi.getBranch('repo', '1.2.3') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(400, 'Bad Request') }
        }
        result == false
    }

    def 'branchExists should throw exception for non-404/400 errors'() {
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

    def 'tagExists should return true when tag exists'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockTagsApi = Mock(TagsApi)
        client.tagsApi = mockTagsApi

        when:
        def result = client.tagExists('repo', 'v1.0.0')

        then:
        1 * mockTagsApi.getTag('repo', 'v1.0.0') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> new Ref()
        }
        result == true
    }

    def 'tagExists should return false when tag does not exist'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockTagsApi = Mock(TagsApi)
        client.tagsApi = mockTagsApi

        when:
        def result = client.tagExists('repo', 'non-existent-tag')

        then:
        1 * mockTagsApi.getTag('repo', 'non-existent-tag') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        result == false
    }

    def 'tagExists should throw exception for non-404 errors'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockTagsApi = Mock(TagsApi)
        client.tagsApi = mockTagsApi

        when:
        client.tagExists('repo', 'v1.0.0')

        then:
        1 * mockTagsApi.getTag('repo', 'v1.0.0') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> { throw new ApiException(500, 'Internal Server Error') }
        }
        thrown(ApiException)
    }

    def 'refExists should return true when ref exists as branch'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        def result = client.refExists('repo', 'main')

        then:
        1 * mockBranchesApi.getBranch('repo', 'main') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> new Ref()
        }
        result == true
    }

    def 'refExists should return true when ref exists as tag'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        def mockTagsApi = Mock(TagsApi)
        client.branchesApi = mockBranchesApi
        client.tagsApi = mockTagsApi

        when:
        def result = client.refExists('repo', 'v1.0.0')

        then:
        1 * mockBranchesApi.getBranch('repo', 'v1.0.0') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        1 * mockTagsApi.getTag('repo', 'v1.0.0') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> new Ref()
        }
        result == true
    }

    def 'refExists should return true for version-like tag when branch check returns 400'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        def mockTagsApi = Mock(TagsApi)
        client.branchesApi = mockBranchesApi
        client.tagsApi = mockTagsApi

        when:
        def result = client.refExists('repo', '1.2.3')

        then:
        // Branch check returns 400 for invalid branch names like "1.2.3"
        1 * mockBranchesApi.getBranch('repo', '1.2.3') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(400, 'Bad Request') }
        }
        // Then it checks if it's a tag
        1 * mockTagsApi.getTag('repo', '1.2.3') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> new Ref()
        }
        result == true
    }

    def 'refExists should return true when ref exists as commit ID'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        def mockTagsApi = Mock(TagsApi)
        def mockObjectsApi = Mock(ObjectsApi)
        client.branchesApi = mockBranchesApi
        client.tagsApi = mockTagsApi
        client.objectsApi = mockObjectsApi

        when:
        def result = client.refExists('repo', 'abc123def456')

        then:
        1 * mockBranchesApi.getBranch('repo', 'abc123def456') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        1 * mockTagsApi.getTag('repo', 'abc123def456') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        1 * mockObjectsApi.listObjects('repo', 'abc123def456') >> Mock(ObjectsApi.APIlistObjectsRequest) {
            amount(0) >> it
            execute() >> new ObjectStatsList()
        }
        result == true
    }

    def 'refExists should return false when ref does not exist'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        def mockTagsApi = Mock(TagsApi)
        def mockObjectsApi = Mock(ObjectsApi)
        client.branchesApi = mockBranchesApi
        client.tagsApi = mockTagsApi
        client.objectsApi = mockObjectsApi

        when:
        def result = client.refExists('repo', 'non-existent')

        then:
        1 * mockBranchesApi.getBranch('repo', 'non-existent') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        1 * mockTagsApi.getTag('repo', 'non-existent') >> Mock(TagsApi.APIgetTagRequest) {
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        1 * mockObjectsApi.listObjects('repo', 'non-existent') >> Mock(ObjectsApi.APIlistObjectsRequest) {
            amount(0) >> it
            execute() >> { throw new ApiException(404, 'Not Found') }
        }
        result == false
    }

    def 'refExists should throw exception for non-404 errors'() {
        given:
        def client = new LakeFSSDKClient([
            apiUrl: 'http://localhost:8000/api/v1',
            accessKey: 'test',
            secretKey: 'test'
        ])
        def mockBranchesApi = Mock(BranchesApi)
        client.branchesApi = mockBranchesApi

        when:
        client.refExists('repo', 'main')

        then:
        1 * mockBranchesApi.getBranch('repo', 'main') >> Mock(BranchesApi.APIgetBranchRequest) {
            execute() >> { throw new ApiException(500, 'Internal Server Error') }
        }
        thrown(ApiException)
    }
}
