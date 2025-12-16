package nextflow.lakefs

import io.lakefs.clients.sdk.ApiClient
import io.lakefs.clients.sdk.ApiException
import io.lakefs.clients.sdk.BranchesApi
import io.lakefs.clients.sdk.ObjectsApi
import io.lakefs.clients.sdk.StagingApi
import io.lakefs.clients.sdk.model.BranchCreation
import io.lakefs.clients.sdk.model.ObjectStats
import io.lakefs.clients.sdk.model.ObjectStatsList
import io.lakefs.clients.sdk.model.StagingLocation
import io.lakefs.clients.sdk.model.StagingMetadata
import nextflow.util.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.NoSuchFileException

class LakeFSSDKClient {

    @SuppressWarnings('unused')
    private static final Logger log = LoggerFactory.getLogger(LakeFSSDKClient.class)

    ObjectsApi objectsApi
    StagingApi stagingApi
    BranchesApi branchesApi

    LakeFSSDKClient(lakeFSConfig) {

        def readTimeout = Duration.of(lakeFSConfig.readTimeout as String, Duration.of('60s'))
        def connectTimeout = Duration.of(lakeFSConfig.connectTimeout as String, Duration.of('30s'))

        ApiClient apiClient = new ApiClient()
        apiClient.setBasePath(lakeFSConfig.apiUrl as String)
        apiClient.setUsername(lakeFSConfig.accessKey as String)
        apiClient.setPassword(lakeFSConfig.secretKey as String)
        apiClient.setReadTimeout(readTimeout.toMillis() as int)
        apiClient.setConnectTimeout(connectTimeout.toMillis() as int)

        this.objectsApi = new ObjectsApi(apiClient)
        this.stagingApi = new StagingApi(apiClient)
        this.branchesApi = new BranchesApi(apiClient)
    }

    StagingLocation getStagingLocation(String repo, String ref, String objectPath, boolean presign) {
        return stagingApi.getPhysicalAddress(repo, ref, objectPath)
                .presign(presign)
                .execute()
    }

    void linkPhysicalAddress(String repository, String branch, String objectPath, StagingMetadata stagingMetadata) {
        stagingApi.linkPhysicalAddress(repository, branch, objectPath, stagingMetadata).execute()
    }

    Object headObject(String repo, String ref, String objectPath) {
        return objectsApi.headObject(repo, ref, objectPath).execute()

    }

    ObjectStats getPathStats(String repo, String ref, String objectPath, boolean presign = false) {
        if (objectPath.endsWith("/") || objectPath.isEmpty()) {
            // Handle directory-like path
            def results = listObjects(repo, ref, objectPath, presign).results

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
            return getObjectStats(repo, ref, objectPath, presign)
        }
    }

    ObjectStats getObjectStats(String repo, String ref, String objectPath, boolean presign) {
        try {
            return objectsApi.statObject(repo, ref, objectPath)
                    .presign(presign)
                    .execute()
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                throw new NoSuchFileException("Object not found: $objectPath")
            }
            throw e
        }
    }


    ObjectStatsList listObjects(String repo, String ref, String prefix, boolean presign = false) {
        return objectsApi.listObjects(repo, ref)
                .prefix(prefix)
                .delimiter('/')
                .userMetadata(false)
                .presign(presign)
                .execute()

    }

    String deleteObject(String repo, String branch, String objectPath) {
        return objectsApi.deleteObject(repo, branch, objectPath).execute()
    }

    boolean branchExists(String repo, String branch) {
        try {
            branchesApi.getBranch(repo, branch).execute()
            return true
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return false
            }
            throw e
        }
    }

    void createBranch(String repo, String branch, String sourceBranch) {
        def branchCreation = new BranchCreation()
        branchCreation.setName(branch)
        branchCreation.setSource(sourceBranch)
        branchesApi.createBranch(repo, branchCreation).execute()
        log.info("Created branch '$branch' from '$sourceBranch' in repository '$repo'")
    }

    void createBranchIfNotExists(String repo, String branch, String sourceBranch) {
        if (!branchExists(repo, branch)) {
            createBranch(repo, branch, sourceBranch)
        }
    }
}