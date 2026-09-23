package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.trace.TraceObserverV2
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Commits the branch backing the workflow's output directory (the `outputDir` config /
 * `-output-dir` CLI option) once the workflow completes successfully, using `params.commit_message`
 * as the commit message. Registered by {@link LakeFSObserverFactory} when `lakefs.autoCommit` is
 * enabled, so pipelines don't need to implement their own `workflow.onComplete` handler.
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
class LakeFSObserver implements TraceObserverV2 {

    static final String COMMIT_MESSAGE_PARAM = 'commit_message'

    private static final Logger log = LoggerFactory.getLogger(LakeFSObserver)

    private Session session

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        // Best-effort early warning: exceptions thrown from observer callbacks are notification-only
        // in Nextflow and do not reliably abort the run, so this is not the authoritative check --
        // onFlowComplete() below re-validates before ever attempting a commit.
        if (!commitMessage())
            throw new AbortOperationException(
                    "lakefs.autoCommit is enabled but params.${COMMIT_MESSAGE_PARAM} was not provided")
    }

    @Override
    void onFlowComplete() {
        if (!session.isSuccess()) {
            log.debug("Skipping lakeFS auto-commit: workflow did not complete successfully")
            return
        }
        final outputDir = session.getOutputDir()
        if (!(outputDir instanceof NextflowLakeFSPath)) {
            log.debug("Skipping lakeFS auto-commit: workflow output directory '$outputDir' is not a lakefs:// path")
            return
        }
        final message = commitMessage()
        if (!message) {
            log.error("lakeFS auto-commit skipped: lakefs.autoCommit is enabled but " +
                    "params.${COMMIT_MESSAGE_PARAM} was not provided -- pass e.g. --${COMMIT_MESSAGE_PARAM} " +
                    "'<message>' on the command line")
            return
        }
        LakeFSCommitSupport.commit(outputDir, message)
    }

    private String commitMessage() {
        return session.getParams().get(COMMIT_MESSAGE_PARAM) as String
    }
}
