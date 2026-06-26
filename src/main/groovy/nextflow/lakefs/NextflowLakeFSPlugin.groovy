
package nextflow.lakefs

import groovy.transform.CompileStatic
import nextflow.file.FileHelper
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * Implements the NextflowLakeFSPlugin plugins entry point
 *
 * @author rpohes@gmail.com
 */
@CompileStatic
class NextflowLakeFSPlugin extends BasePlugin {

    NextflowLakeFSPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

    @Override
    void start() {
        super.start()
        // Make the AWS SDK v2 (used by nf-amazon for s3:// physical_path transfers) send no upload checksum,
        // so S3 applies its server-side full-object crc64nvme — uniform with the signed_url path, which is
        // forced to crc64nvme on its presigned PUT. JVM-global on purpose: it also upgrades Nextflow's other
        // S3 transfers from CRC32 to crc64nvme, which is benign (crc64nvme is AWS's newer, stronger default).
        // Set before any s3 client is built; respect an explicit override if the user already set it.
        if (System.getProperty("aws.requestChecksumCalculation") == null)
            System.setProperty("aws.requestChecksumCalculation", "when_required")
        // log the resolved value so a misfire (e.g. nf-amazon built its S3 client before this ran) is diagnosable
        org.slf4j.LoggerFactory.getLogger(NextflowLakeFSPlugin)
                .debug("nf-lakefs: aws.requestChecksumCalculation=" + System.getProperty("aws.requestChecksumCalculation"))
        FileHelper.getOrInstallProvider(NextflowLakeFSFileSystemProvider)
    }
}
