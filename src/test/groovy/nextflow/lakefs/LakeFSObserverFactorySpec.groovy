package nextflow.lakefs

import nextflow.Session
import spock.lang.Specification

class LakeFSObserverFactorySpec extends Specification {

    def 'should register no observer when lakefs.autoCommit is not enabled'() {
        given:
        def factory = new LakeFSObserverFactory()
        def session = Mock(Session) { getConfig() >> [lakefs: [apiUrl: 'http://localhost:8000/api/v1/']] }

        expect:
        factory.create(session).isEmpty()
    }

    def 'should register no observer when there is no lakefs config at all'() {
        given:
        def factory = new LakeFSObserverFactory()
        def session = Mock(Session) { getConfig() >> [:] }

        expect:
        factory.create(session).isEmpty()
    }

    def 'should register a LakeFSObserver when lakefs.autoCommit is enabled'() {
        given:
        def factory = new LakeFSObserverFactory()
        def session = Mock(Session) { getConfig() >> [lakefs: [apiUrl: 'http://localhost:8000/api/v1/', autoCommit: true]] }

        when:
        def observers = factory.create(session)

        then:
        observers.size() == 1
        observers[0] instanceof LakeFSObserver
    }
}
