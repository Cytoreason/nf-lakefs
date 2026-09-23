package nextflow.lakefs

import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.script.ScriptBinding
import spock.lang.Specification

import java.nio.file.Paths

class LakeFSObserverSpec extends Specification {

    private NextflowLakeFSPath lakeFSPath(LakeFSSDKClient mockClient, String repository = 'my-repo', String branch = 'my-branch') {
        def provider = new NextflowLakeFSFileSystemProvider()
        provider.lakeFSClient = mockClient
        def fs = new NextflowLakeFSFileSystem(provider, URI.create("lakefs://$repository/$branch/"), repository, branch)
        return new NextflowLakeFSPath(fs, '')
    }

    def 'onFlowCreate should throw when params.lakefs_commit_message is missing'() {
        given:
        def observer = new LakeFSObserver()
        def session = Mock(Session) { getParams() >> new ScriptBinding.ParamsMap([:]) }

        when:
        observer.onFlowCreate(session)

        then:
        def e = thrown(AbortOperationException)
        e.message.contains('lakefs_commit_message')
    }

    def 'onFlowCreate should not throw when params.lakefs_commit_message is present'() {
        given:
        def observer = new LakeFSObserver()
        def session = Mock(Session) { getParams() >> new ScriptBinding.ParamsMap([lakefs_commit_message: 'hello']) }

        when:
        observer.onFlowCreate(session)

        then:
        noExceptionThrown()
    }

    def 'onFlowComplete should skip when the workflow did not succeed'() {
        given:
        def observer = new LakeFSObserver()
        def mockClient = Mock(LakeFSSDKClient)
        def session = Mock(Session) {
            getParams() >> new ScriptBinding.ParamsMap([lakefs_commit_message: 'hello'])
            isSuccess() >> false
            getOutputDir() >> lakeFSPath(mockClient)
        }
        observer.onFlowCreate(session)

        when:
        observer.onFlowComplete()

        then:
        0 * mockClient.commit(*_)
    }

    def 'onFlowComplete should skip when the output dir is not a lakefs:// path'() {
        given:
        def observer = new LakeFSObserver()
        def session = Mock(Session) {
            getParams() >> new ScriptBinding.ParamsMap([lakefs_commit_message: 'hello'])
            isSuccess() >> true
            getOutputDir() >> Paths.get('/tmp/results')
        }
        observer.onFlowCreate(session)

        when:
        observer.onFlowComplete()

        then:
        noExceptionThrown()
    }

    def 'onFlowComplete should not attempt a commit when params.lakefs_commit_message is missing, even if onFlowCreate\'s exception was swallowed'() {
        // Nextflow's observer notification is best-effort: an exception thrown from onFlowCreate does not
        // reliably abort the run, so the workflow may still run to completion and invoke onFlowComplete.
        given:
        def observer = new LakeFSObserver()
        def mockClient = Mock(LakeFSSDKClient)
        def session = Mock(Session) {
            getParams() >> new ScriptBinding.ParamsMap([:])
            isSuccess() >> true
            getOutputDir() >> lakeFSPath(mockClient)
        }
        try {
            observer.onFlowCreate(session)
        } catch (AbortOperationException ignored) {
            // simulate the runtime swallowing the exception and letting the workflow proceed
        }

        when:
        observer.onFlowComplete()

        then:
        noExceptionThrown()
        0 * mockClient.commit(*_)
    }

    def 'onFlowComplete should commit the branch backing the output dir when the workflow succeeded'() {
        given:
        def observer = new LakeFSObserver()
        def mockClient = Mock(LakeFSSDKClient)
        def session = Mock(Session) {
            getParams() >> new ScriptBinding.ParamsMap([lakefs_commit_message: 'my commit message'])
            isSuccess() >> true
            getOutputDir() >> lakeFSPath(mockClient, 'my-repo', 'my-branch')
        }
        observer.onFlowCreate(session)

        when:
        observer.onFlowComplete()

        then:
        1 * mockClient.commit('my-repo', 'my-branch', 'my commit message') >> 'commit-id-789'
    }
}
