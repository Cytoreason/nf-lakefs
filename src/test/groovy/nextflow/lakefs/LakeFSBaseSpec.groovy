package nextflow.lakefs

import io.lakefs.clients.sdk.ApiException
import io.lakefs.clients.sdk.ObjectsApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Paths

/**
 *
 * @author rpohes@gmail.com
 */
trait LakeFSBaseSpec {

    static final Logger log = LoggerFactory.getLogger(LakeFSBaseSpec)

    abstract ObjectsApi getLakeFSClient()

    NextflowLakeFSPath lakeFSpath(String path) {
        return (NextflowLakeFSPath) NextflowLakeFSPathFactory.parse(path)
    }


    def createObject(String path, String content) {
        Files.write(lakeFSpath(path), content.bytes)
    }

    boolean existsPath(String repo, String ref, String objectPath) {
        try {
            log.debug "Check blob path exists '$repo/$ref/$objectPath'"
            lakeFSClient.headObject(repo, ref, objectPath).execute()
            return true
        } catch (ApiException ignored) {
            return false
        }

    }

    void deleteObject(String repo, String ref, String objectPath) {
        log.debug "Deleting blob object '$repo/$ref/$objectPath'"
        lakeFSClient.deleteObject(repo, ref, objectPath).execute()
    }


    String readObject(String path) {
        log.debug "Reading blob object '$path'"
        readObject(Paths.get(path) as NextflowLakeFSPath)
    }

    String readObject(NextflowLakeFSPath path) {
        log.debug "Reading blob object '$path'"
        return lakeFSClient
                .getObject(path.repository(),path.ref(),path.objectPath).execute()
                .getText()
    }


    String randomText(int size) {
        def result = new StringBuilder()
        while( result.size() < size ) {
            result << UUID.randomUUID().toString() << '\n'
        }
        return result.toString()
    }

    // Stream random bytes straight to disk for large files. Unlike randomText (which builds the whole
    // payload as an in-memory String, capped at ~2 GiB and int-sized), this takes a long size and never
    // holds more than the working buffer in memory, so it can produce files larger than 2 GiB.
    java.nio.file.Path writeRandomFile(java.nio.file.Path file, long size) {
        def buf = new byte[8 * 1024 * 1024]   // 8 MiB working buffer
        def rnd = new Random(42)              // fixed seed = reproducible content
        long written = 0
        file.withOutputStream { out ->
            while (written < size) {
                rnd.nextBytes(buf)
                int n = (int) Math.min((long) buf.length, size - written)
                out.write(buf, 0, n)
                written += n
            }
        }
        return file
    }

    String readChannel(SeekableByteChannel sbc, int buffLen )  {
        def buffer = new ByteArrayOutputStream()
        ByteBuffer bf = ByteBuffer.allocate(buffLen)
        while((sbc.read(bf))>0) {
            bf.flip()
            buffer.write(bf.array(), 0, bf.limit())
            bf.clear()
        }

        buffer.toString()
    }

    void writeChannel( SeekableByteChannel channel, String content, int buffLen ) {

        def bytes = content.getBytes()
        ByteBuffer buf = ByteBuffer.allocate(buffLen)
        int i=0
        while( i < bytes.size()) {

            def len = Math.min(buffLen, bytes.size()-i)
            buf.clear()
            buf.put(bytes, i, len)
            buf.flip()
            channel.write(buf)

            i += len
        }

    }

}
