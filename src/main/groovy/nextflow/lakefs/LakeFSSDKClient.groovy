package nextflow.lakefs

import io.lakefs.clients.sdk.ApiClient
import io.lakefs.clients.sdk.ApiException
import io.lakefs.clients.sdk.BranchesApi
import io.lakefs.clients.sdk.CommitsApi
import io.lakefs.clients.sdk.ObjectsApi
import io.lakefs.clients.sdk.RepositoriesApi
import io.lakefs.clients.sdk.StagingApi
import io.lakefs.clients.sdk.TagsApi
import io.lakefs.clients.sdk.model.BranchCreation
import io.lakefs.clients.sdk.model.CommitCreation
import io.lakefs.clients.sdk.model.ObjectStats
import io.lakefs.clients.sdk.model.ObjectStatsList
import io.lakefs.clients.sdk.model.StagingLocation
import io.lakefs.clients.sdk.model.StagingMetadata
import io.lakefs.clients.sdk.model.TagCreation
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
    TagsApi tagsApi
    CommitsApi commitsApi
    RepositoriesApi repositoriesApi

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
        this.tagsApi = new TagsApi(apiClient)
        this.commitsApi = new CommitsApi(apiClient)
        this.repositoriesApi = new RepositoriesApi(apiClient)
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
            // 404 = branch not found
            // 400 = invalid branch name (e.g., "1.2.3" - branch names can't start with a digit)
            if (e.getCode() == 404 || e.getCode() == 400) {
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

    /**
     * Deletes a branch from the repository.
     *
     * @param repo The repository name
     * @param branch The branch name to delete
     */
    void deleteBranch(String repo, String branch) {
        branchesApi.deleteBranch(repo, branch).execute()
        log.info("Deleted branch '$branch' from repository '$repo'")
    }

    /**
     * Commits staged changes on a branch.
     *
     * @param repo The repository name
     * @param branch The branch name
     * @param message The commit message
     * @return The commit ID
     */
    String commit(String repo, String branch, String message) {
        def commitCreation = new CommitCreation()
        commitCreation.setMessage(message)
        def commit = commitsApi.commit(repo, branch, commitCreation).execute()
        log.info("Created commit '${commit.id}' on branch '$branch' in repository '$repo'")
        return commit.id
    }

    /**
     * Checks if a tag exists in the repository using the Tags API.
     *
     * @param repo The repository name
     * @param tag The tag name
     * @return true if the tag exists, false otherwise
     */
    boolean tagExists(String repo, String tag) {
        try {
            tagsApi.getTag(repo, tag).execute()
            return true
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return false
            }
            throw e
        }
    }

    /**
     * Creates a tag in the repository pointing to a specific ref.
     *
     * @param repo The repository name
     * @param tagName The tag name to create
     * @param ref The reference (branch, commit, or another tag) to point to
     */
    void createTag(String repo, String tagName, String ref) {
        def tagCreation = new TagCreation()
        tagCreation.setId(tagName)
        tagCreation.setRef(ref)
        tagsApi.createTag(repo, tagCreation).execute()
        log.info("Created tag '$tagName' pointing to '$ref' in repository '$repo'")
    }

    /**
     * Deletes a tag from the repository.
     *
     * @param repo The repository name
     * @param tagName The tag name to delete
     */
    void deleteTag(String repo, String tagName) {
        tagsApi.deleteTag(repo, tagName).execute()
        log.info("Deleted tag '$tagName' from repository '$repo'")
    }

    /**
     * Checks if a reference (branch, tag, or commit) exists in the repository.
     * First checks if it's a branch, then a tag, then falls back to checking
     * via listObjects for commit IDs.
     *
     * @param repo The repository name
     * @param ref The reference (branch name, tag name, or commit ID)
     * @return true if the reference exists, false otherwise
     */
    boolean refExists(String repo, String ref) {
        // Check if it's a branch
        if (branchExists(repo, ref)) {
            if (log.isDebugEnabled())
                log.debug("Reference '$ref' exists as a branch in repository '$repo'")
            return true
        }

        // Check if it's a tag
        if (tagExists(repo, ref)) {
            if (log.isDebugEnabled())
                log.debug("Reference '$ref' exists as a tag in repository '$repo'")
            return true
        }

        // Fall back to listObjects for commit IDs
        try {
            objectsApi.listObjects(repo, ref)
                    .amount(0)
                    .execute()
            if (log.isDebugEnabled())
                log.debug("Reference '$ref' exists as a commit in repository '$repo'")
            return true
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return false
            }
            throw e
        }
    }

    String getRepositoryStorageNamespace(String repo) {
        return repositoriesApi.getRepository(repo).execute().storageNamespace
    }
}
