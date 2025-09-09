package nextflow.lakefs

import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.NoSuchFileException
import java.util.concurrent.Flow

class SimpleLakeFSClientSpec extends Specification {

    def mockHttpClient = Mock(HttpClient)
    def mockHttpResponse = Mock(HttpResponse)
    def client

    def setup() {
        def config = [
                apiUrl   : "http://localhost:8000/api/v1/",
                accessKey: "test-key",
                secretKey: "test-secret"
        ]
        // Assuming a test-only constructor that accepts a mock client
        client = new SimpleLakeFSClient(config, mockHttpClient)
    }

    def "constructor should correctly initialize baseUrl and authHeader"() {
        given:
        def config = [
                apiUrl   : apiUrl,
                accessKey: "my-key",
                secretKey: "my-secret"
        ]
        def expectedAuth = "Basic " + Base64.getEncoder().encodeToString("my-key:my-secret".getBytes())

        when:
        def testClient = new SimpleLakeFSClient(config, mockHttpClient)

        then:
        testClient.baseUrl == "http://lakefs.example.com/api/v1"
        testClient.authHeader == expectedAuth

        where:
        apiUrl << ["http://lakefs.example.com/api/v1", "http://lakefs.example.com/api/v1/"]
    }

    def "getStagingLocation should return a valid staging location on success"() {
        given:
        def repo = "my-repo"
        def ref = "main"
        def path = "data/file.txt"
        def expectedUri = "http://localhost:8000/api/v1/repositories/my-repo/branches/main/staging/backing?path=data%2Ffile.txt"
        def responseBody = '{"token": "some-jwt-token", "physical_address": "s3://bucket/path/to/staged/file"}'

        // A variable to hold the captured request
        HttpRequest capturedRequest

        when:
        def result = client.getStagingLocation(repo, ref, path)

        then:
        // Use a closure to capture the arguments passed to the mock.
        // The closure must return the stubbed response.
        1 * mockHttpClient.send(_, _ as HttpResponse.BodyHandler) >> { HttpRequest request, handler ->
            capturedRequest = request // Capture the argument
            mockHttpResponse.statusCode() >> 200
            mockHttpResponse.body() >> responseBody
            return mockHttpResponse
        }

        and: "the captured request is correct"
        capturedRequest.uri().toString() == expectedUri
        capturedRequest.method() == "GET"
        capturedRequest.headers().firstValue("Authorization").get() == "Basic dGVzdC1rZXk6dGVzdC1zZWNyZXQ="

        and: "the result object is correct"
        result instanceof StagingLocation
        result.token == "some-jwt-token"
        result.physicalAddress == "s3://bucket/path/to/staged/file"
    }

    def "linkPhysicalAddress should send a correct PUT request"() {
        given:
        def repo = "my-repo"
        def branch = "main"
        def path = "data/new-file.txt"
        def expectedUri = "http://localhost:8000/api/v1/repositories/my-repo/branches/main/staging/backing?path=data%2Fnew-file.txt"

        def stagingMetadata = new StagingMetadata(
                stagingLocation: new StagingLocation(physicalAddress: "s3://bucket/staged/123"),
                checksum: "abc-checksum-123",
                sizeBytes: 1024L,
                userMetadata: [key1: "value1"]
        )

        HttpRequest capturedRequest

        when:
        client.linkPhysicalAddress(repo, branch, path, stagingMetadata)

        then:
        // Use the same closure-based capture technique
        1 * mockHttpClient.send(_, _ as HttpResponse.BodyHandler) >> { HttpRequest request, handler ->
            capturedRequest = request
            mockHttpResponse.statusCode() >> 201
            mockHttpResponse.body() >> ""
            return mockHttpResponse
        }

        and: "the captured request is correct"
        capturedRequest.uri().toString() == expectedUri
        capturedRequest.method() == "PUT"

        // Inspect the request body
        def bodyPublisher = capturedRequest.bodyPublisher().get()
        def subscriber = new BodySubscriber()
        bodyPublisher.subscribe(subscriber)
        def requestBody = new String(subscriber.getBodyBytes(), StandardCharsets.UTF_8)
        def jsonBody = new JsonSlurper().parseText(requestBody)

        jsonBody.staging.physical_address == "s3://bucket/staged/123"
        jsonBody.checksum == "abc-checksum-123"
        jsonBody.size_bytes == 1024
        jsonBody.user_metadata.key1 == "value1"
    }

    def "getObjectStats for a file should return correct stats"() {
        given:
        def path = "path/to/file.txt"
        def responseBody = '''
        {
            "path": "path/to/file.txt",
            "path_type": "object",
            "physical_address": "s3://bucket/data/123",
            "checksum": "xyz-checksum",
            "mtime": 1672531200,
            "size_bytes": 4096
        }
        '''
        mockHttpResponse.statusCode() >> 200
        mockHttpResponse.body() >> responseBody

        when:
        def stats = client.getObjectStats("my-repo", "main", path)

        then:
        1 * mockHttpClient.send(_ as HttpRequest, _) >> mockHttpResponse

        and:
        stats.path == "path/to/file.txt"
        stats.pathType == ObjectStats.PathTypeEnum.OBJECT
        stats.physicalAddress == "s3://bucket/data/123"
        stats.checksum == "xyz-checksum"
        stats.mtime == 1672531200L
        stats.sizeBytes == 4096L
    }

    def "getObjectStats for a directory should return a common prefix"() {
        given: "A path that looks like a directory"
        def path = "path/to/dir/"
        def expectedUri = "http://localhost:8000/api/v1/repositories/my-repo/refs/main/objects/ls?prefix=path%2Fto%2Fdir%2F&delimiter=%2F&user_metadata=false"
        def responseBody = '{"results": [], "pagination": {"has_more": false}}'
        HttpRequest capturedRequest

        when:
        def stats = client.getObjectStats("my-repo", "main", path)

        then: "The interaction calls the 'ls' endpoint"
        1 * mockHttpClient.send(_, _) >> { HttpRequest request, handler ->
            capturedRequest = request
            mockHttpResponse.statusCode() >> 200
            mockHttpResponse.body() >> responseBody
            return mockHttpResponse
        }

        and: "the correct URI was called"
        capturedRequest.uri().toString() == expectedUri

        and: "The result is a COMMON_PREFIX object"
        stats.path == path
        stats.pathType == ObjectStats.PathTypeEnum.COMMON_PREFIX
        stats.mtime == 0
    }

    def "listObjects should return a list of object stats"() {
        given:
        def prefix = "data/"
        def expectedUri = "http://localhost:8000/api/v1/repositories/my-repo/refs/main/objects/ls?prefix=data%2F&delimiter=%2F&user_metadata=false"
        def responseBody = '''
        {
            "results": [
                {
                    "path": "data/file1.txt",
                    "path_type": "object",
                    "physical_address": "s3://bucket/data/f1",
                    "checksum": "abc",
                    "mtime": 1,
                    "size_bytes": 100
                },
                {
                    "path": "data/subdir/",
                    "path_type": "common_prefix",
                    "mtime": 2,
                    "size_bytes": 0
                }
            ],
            "pagination": {
                "has_more": true,
                "next_offset": "offset123",
                "results": 2,
                "max_per_page": 1000
            }
        }
        '''
        HttpRequest capturedRequest

        when:
        def list = client.listObjects("my-repo", "main", prefix)

        then:
        1 * mockHttpClient.send(_, _) >> { HttpRequest request, handler ->
            capturedRequest = request
            mockHttpResponse.statusCode() >> 200
            mockHttpResponse.body() >> responseBody
            return mockHttpResponse
        }

        and: "the correct URI was called"
        capturedRequest.uri().toString() == expectedUri

        and: "the results are parsed correctly"
        list.results.size() == 2
        list.results[0].path == "data/file1.txt"
        list.results[0].pathType == ObjectStats.PathTypeEnum.OBJECT
        list.results[1].path == "data/subdir/"
        list.results[1].pathType == ObjectStats.PathTypeEnum.COMMON_PREFIX
        list.pagination.hasMore == true
        list.pagination.nextOffset == "offset123"
    }

    @Unroll
    def "sendRequest should throw RuntimeException for HTTP status #statusCode"() {
        given:
        def request = HttpRequest.newBuilder().uri(URI.create("http://dummy")).build()

        // Stub the mock client's send method before the action
        1 * mockHttpClient.send(request, _) >> {
            mockHttpResponse.statusCode() >> statusCode
            mockHttpResponse.body() >> '{"error": "not found"}'
            return mockHttpResponse
        }

        when:
        // This test calls a private method. This is generally discouraged, but if
        // necessary, this is how it's done.
        client.sendRequest(request)

        then:
        def e = thrown(RuntimeException)
        e.message.contains("HTTP ${statusCode}:")

        where:
        statusCode << [400, 500]

    }

    @Unroll
    def "sendRequest should throw NoSuchFileException for HTTP status 404"() {
        given:
        def request = HttpRequest.newBuilder().uri(URI.create("http://dummy")).build()

        // Stub the mock client's send method before the action
        1 * mockHttpClient.send(request, _) >> {
            mockHttpResponse.statusCode() >> statusCode
            mockHttpResponse.body() >> '{"error": "not found"}'
            return mockHttpResponse
        }

        when:
        // This test calls a private method. This is generally discouraged, but if
        // necessary, this is how it's done.
        client.sendRequest(request)

        then:
        def e = thrown(NoSuchFileException)
//        e.message.contains("HTTP ${statusCode}:")

        where:
        statusCode << [404]

    }
}

// Helper class to read the body from a BodyPublisher for testing
class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
    private Flow.Subscription subscription
    private byte[] bodyBytes

    @Override
    void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    @Override
    void onNext(ByteBuffer item) {
        bodyBytes = new byte[item.remaining()]
        item.get(bodyBytes)
    }

    @Override
    void onError(Throwable throwable) {
        throwable.printStackTrace()
    }

    @Override
    void onComplete() {}

    byte[] getBodyBytes() {
        return bodyBytes
    }
}