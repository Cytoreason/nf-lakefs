package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.trace.TraceObserverFactoryV2
import nextflow.trace.TraceObserverV2

/**
 * Registers {@link LakeFSObserver} when `lakefs.autoCommit` is enabled, so pipelines don't need
 * to implement their own `workflow.onComplete` handler to commit their lakeFS output branch.
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
class LakeFSObserverFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        final opts = session.config?.get('lakefs') as Map
        final config = new LakeFSConfig(opts != null ? opts : Collections.emptyMap(), System.getenv())
        if (!config.autoCommit)
            return Collections.emptyList()
        return [new LakeFSObserver()]
    }
}
