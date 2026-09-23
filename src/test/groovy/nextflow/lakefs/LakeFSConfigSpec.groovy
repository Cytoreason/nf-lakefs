package nextflow.lakefs

import spock.lang.Specification
import spock.lang.Unroll

class LakeFSConfigSpec extends Specification {

    def 'no-arg constructor should default autoCommit to false'() {
        expect:
        !new LakeFSConfig().autoCommit
    }

    @Unroll
    def 'should parse autoCommit=#value from opts'() {
        expect:
        new LakeFSConfig([autoCommit: value]).autoCommit == expected

        where:
        value       | expected
        true        | true
        false       | false
        null        | false
    }

    def 'should default autoCommit to false when not present in opts'() {
        expect:
        !new LakeFSConfig([:]).autoCommit
    }
}
