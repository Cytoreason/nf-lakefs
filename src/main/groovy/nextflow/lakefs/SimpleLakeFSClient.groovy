package nextflow.lakefs

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.NoSuchFileException
import java.time.Duration

@Deprecated(since = "0.2.0", forRemoval = true)
class SimpleLakeFSClient {
    private HttpClient httpClient
    private String baseUrl
    private String authHeader
    private JsonSlurper jsonSlurper = new JsonSlurper()
    Duration readTimeout

    private static final Logger log = LoggerFactory.getLogger(SimpleLakeFSClient.class)

    SimpleLakeFSClient(lakeFSConfig, HttpClient httpClient) {
        this.baseUrl = lakeFSConfig.apiUrl.endsWith('/') ?
                lakeFSConfig.apiUrl.substring(0, lakeFSConfig.apiUrl.length() - 1) :
                lakeFSConfig.apiUrl

        // Create Basic Auth header
        String credentials = "${lakeFSConfig.accessKey}:${lakeFSConfig.secretKey}"
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes())
        this.readTimeout = lakeFSConfig.readTimeoutSeconds != null ? Duration.ofSeconds(lakeFSConfig.readTimeoutSeconds as Long) : Duration.ofSeconds(60)

        this.authHeader = "Basic ${encodedCredentials}"

        this.httpClient = httpClient
    }

    private HttpRequest.Builder createRequestBuilder(String endpoint) {
//        log.error("***url = ${baseUrl}${endpoint}")
        return HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}${endpoint}"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(readTimeout)
    }

    private def sendRequest(HttpRequest request) throws NoSuchFileException {
        HttpResponse<String> response
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute request: ${e.message}", e)
        }
        if (response.statusCode() >= 400) {
            if (response.statusCode() == 404) {
                throw new NoSuchFileException(response.uri().toString())
            }
            throw new RuntimeException("HTTP ${response.statusCode()}: ${response.body()}")
        }

        return response.body() ? jsonSlurper.parseText(response.body()) : [:]

    }

    StagingLocation getStagingLocation(String repo, String ref, String objectPath) {
        String endpoint = "/repositories/${repo}/branches/${ref}/staging/backing"
//        String endpoint = "/api/v1/repositories/${repo}/refs/${ref}/objects/ls"
        String encodedPath = URLEncoder.encode(objectPath, "UTF-8")

        HttpRequest request = createRequestBuilder("${endpoint}?path=${encodedPath}")
                .GET()
                .build()

        def response = sendRequest(request)

        // Convert response to StagingLocation-like object
        return new StagingLocation(
                token: response.token,
                physicalAddress: response.physical_address
        )
    }

    void linkPhysicalAddress(String repository, String branch, String objectPath, StagingMetadata stagingMetadata) {
        String endpoint = "/repositories/${repository}/branches/${branch}/staging/backing"
        String encodedPath = URLEncoder.encode(objectPath, "UTF-8")

        def requestBody = [
                staging      : [
                        physical_address: stagingMetadata.stagingLocation?.physicalAddress
                ],
                checksum     : stagingMetadata.checksum,
                size_bytes   : stagingMetadata.sizeBytes,
                user_metadata: stagingMetadata.userMetadata
        ]

        String jsonBody = new JsonBuilder(requestBody).toString()

        HttpRequest request = createRequestBuilder("${endpoint}?path=${encodedPath}")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()

        sendRequest(request)
    }

    Object headObject(String repo, String ref, String objectPath) {
        String endpoint = "/repositories/${repo}/refs/${ref}/objects"
        String encodedPath = URLEncoder.encode(objectPath, "UTF-8")

        HttpRequest request = createRequestBuilder("${endpoint}?path=${encodedPath}")
                .HEAD()
                .build()
        return sendRequest(request)
    }
    ObjectStats getObjectStats(String repo, String ref, String objectPath) {
        if (objectPath.endsWith("/") || objectPath.isEmpty()) {
            // Handle directory-like path
            def results = listObjects(repo, ref, objectPath).results

            if (!results || results.isEmpty() || results[0].path != objectPath) {
                return new ObjectStats(
                        path: objectPath,
                        pathType: ObjectStats.PathTypeEnum.COMMON_PREFIX,
                        mtime: 0,
                        checksum: "",
                        physicalAddress: ""
                )
            }
            throw new UnsupportedOperationException("Directory listing not fully supported")
        } else {
            // Handle file path
            String endpoint = "/repositories/${repo}/refs/${ref}/objects/stat"
            String encodedPath = URLEncoder.encode(objectPath, "UTF-8")

            HttpRequest request = createRequestBuilder("${endpoint}?path=${encodedPath}")
                    .GET()
                    .build()

            def response = sendRequest(request)

            return new ObjectStats(
                    path: response.path,
                    pathType: ObjectStats.PathTypeEnum.from(response.path_type),
                    physicalAddress: response.physical_address,
                    checksum: response.checksum,
                    mtime: response.mtime,
                    sizeBytes: response.size_bytes
            )
        }
    }

    ObjectStatsList listObjects(String repo, String ref, String prefix) {
        String endpoint = "/repositories/${repo}/refs/${ref}/objects/ls"
        def params = []

        if (prefix) {
            params.add("prefix=${URLEncoder.encode(prefix, 'UTF-8')}")
        }
        params.add("delimiter=${URLEncoder.encode('/', 'UTF-8')}")
        params.add("user_metadata=${URLEncoder.encode('false', 'UTF-8')}")

        String queryString = params.join("&")
        String fullEndpoint = queryString ? "${endpoint}?${queryString}" : endpoint

        HttpRequest request = createRequestBuilder(fullEndpoint)
                .GET()
                .build()

        def response = sendRequest(request)

        // Convert response to ObjectStatsList-like object
        def results = response.results?.collect { item ->
            new ObjectStats(
                    path: item.path,
                    pathType: ObjectStats.PathTypeEnum.from(item.path_type),
                    physicalAddress: item.physical_address,
                    checksum: item.checksum,
                    mtime: item.mtime,
                    sizeBytes: item.size_bytes
            )
        } ?: []

        return new ObjectStatsList(
                results: results,
                pagination: new Pagination(
                        hasMore: response.pagination?.has_more ?: false,
                        nextOffset: response.pagination?.next_offset,
                        results: response.pagination?.results ?: 0,
                        maxPerPage: response.pagination?.max_per_page ?: 1000
                )
        )
    }

    String deleteObject(String repo, String branch, String objectPath) {
        String endpoint = "/repositories/${repo}/branches/${branch}/objects"
        String encodedPath = URLEncoder.encode(objectPath, "UTF-8")

        HttpRequest request = createRequestBuilder("${endpoint}?path=${encodedPath}")
                .DELETE()
                .build()

        def response = sendRequest(request)
        return response.message
    }
}

// Supporting classes to match the original API structure
class StagingLocation {
    String token
    String physicalAddress
}

class StagingMetadata {
    StagingLocation stagingLocation
    String checksum
    Long sizeBytes
    Map userMetadata
}


class ObjectStats {
    String path
    PathTypeEnum pathType
    String physicalAddress
    String checksum
    Long mtime
    Long sizeBytes

    enum PathTypeEnum {
        OBJECT, COMMON_PREFIX

        static from(str) {
            if (str == "object") return OBJECT
            else return COMMON_PREFIX
        }
    }
}

class ObjectStatsList {
    List<ObjectStats> results
    Pagination pagination
}

class Pagination {
    Boolean hasMore
    String nextOffset
    Integer results
    Integer maxPerPage
}
