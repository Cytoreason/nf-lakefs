package nextflow.lakefs

import com.google.common.base.Preconditions
import groovy.transform.CompileStatic
import nextflow.file.TagAwareFile

import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * LakeFS path implementation
 */
@CompileStatic
class NextflowLakeFSPath implements Path, TagAwareFile {
    final NextflowLakeFSFileSystem lakeFSFileSystem
    final String objectPath
    Map<String, String> tags
    String contentType

    NextflowLakeFSFileSystemProvider.LakeFSFileAttributes cachedAttributes

    NextflowLakeFSPath(NextflowLakeFSFileSystem lakeFSFileSystem, String objectPath) {
        this.lakeFSFileSystem = lakeFSFileSystem
        def normalized = objectPath
                .replaceAll(/\/+/, "/")   // collapse multiple slashes
                .replaceFirst(/^\/+/, "") // remove leading slash(s)
        this.objectPath = normalized
    }

    String repository() {
        lakeFSFileSystem.repository
    }

    String ref() {
        lakeFSFileSystem.ref
    }

    @Override
    FileSystem getFileSystem() {
        return lakeFSFileSystem
    }

    @Override
    boolean isAbsolute() {
        return true
    }

    @Override
    Path getRoot() {
        return new NextflowLakeFSPath(lakeFSFileSystem, "")
    }

    @Override
    Path getFileName() {
//        Preconditions.checkArgument(objectPath == null, "file name " + ref + " " + objectPath)
        if (objectPath.isEmpty()) {
            return new NextflowLakeFSPath(lakeFSFileSystem, "")
        }
        def trimmedObjectPath = objectPath
        if (objectPath.endsWith("/")) { //directory
            trimmedObjectPath = objectPath.substring(0, objectPath.length() - 1)
        }
        final lastSlash = trimmedObjectPath.lastIndexOf('/')
        if (lastSlash == -1) {
            return Paths.get(trimmedObjectPath)
        } else {
            return Paths.get(trimmedObjectPath.substring(lastSlash + 1))
//            return new LakeFSPath(fileSystem, "", objectPath.substring(lastSlash + 1))
        }
    }

    @Override
    Path getParent() {
        if (objectPath.isEmpty()) {
            return null
        }

        final lastSlash = objectPath.lastIndexOf('/')
        if (lastSlash == -1) {
            return new NextflowLakeFSPath(lakeFSFileSystem, "")
        } else {
            return new NextflowLakeFSPath(lakeFSFileSystem, objectPath.substring(0, lastSlash + 1))
        }
    }

    @Override
    int getNameCount() {
        if (objectPath.isEmpty()) {
            return 0
        }

        int count = 1
        for (int i = 0; i < objectPath.length(); i++) {
            if (objectPath.charAt(i) == ('/' as char)) {
                count++
            }
        }

        return count
    }

    @Override
    Path getName(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index must be non-negative")
        }

        if (objectPath.isEmpty()) {
            throw new IllegalArgumentException("Path has no elements")
        }

        String[] parts = objectPath.split("/")
        if (index >= parts.length) {
            throw new IllegalArgumentException("Index " + index + " is out of bounds")
        }

        return new NextflowLakeFSPath(lakeFSFileSystem, parts[index])
    }

    @Override
    Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0) {
            throw new IllegalArgumentException("Begin index must be non-negative")
        }

        if (endIndex <= beginIndex) {
            throw new IllegalArgumentException("End index must be greater than begin index")
        }

        String[] parts = objectPath.split("/")
        if (endIndex > parts.length) {
            throw new IllegalArgumentException("End index is out of bounds")
        }

        StringBuilder subpath = new StringBuilder()
        for (int i = beginIndex; i < endIndex; i++) {
            if (i > beginIndex) {
                subpath.append('/')
            }
            subpath.append(parts[i])
        }

        return new NextflowLakeFSPath(lakeFSFileSystem, subpath.toString())
    }

    @Override
    boolean startsWith(Path other) {
        if (!(other instanceof NextflowLakeFSPath)) {
            return false
        }

        NextflowLakeFSPath otherPath = (NextflowLakeFSPath) other
        if (lakeFSFileSystem != otherPath.getFileSystem()) {
            return false
        }

        if (ref() != otherPath.ref()) {
            return false
        }

        return objectPath.startsWith(otherPath.objectPath)
    }

    @Override
    boolean startsWith(String other) {
        return startsWith(new NextflowLakeFSPath(lakeFSFileSystem, other))
    }

    @Override
    boolean endsWith(Path other) {
        if (!(other instanceof NextflowLakeFSPath)) {
            return false
        }

        NextflowLakeFSPath otherPath = (NextflowLakeFSPath) other

        // If other path is absolute, all components must match
        if (otherPath.isAbsolute()) {
            return equals(otherPath)
        }

        // Otherwise, check if this path ends with the other path's components
        String[] thisParts = objectPath.split("/")
        String[] otherParts = otherPath.objectPath.split("/")

        if (otherParts.length > thisParts.length) {
            return false
        }

        for (int i = 0; i < otherParts.length; i++) {
            int thisIndex = thisParts.length - otherParts.length + i
            if (thisParts[thisIndex] != otherParts[i]) {
                return false
            }
        }

        return true
    }

    @Override
    boolean endsWith(String other) {
        return endsWith(new NextflowLakeFSPath(lakeFSFileSystem, other))
    }

    @Override
    Path normalize() {
        def normalizedPath = Path.of(objectPath).normalize().toString()
        // Path is already normalized
        return new NextflowLakeFSPath(lakeFSFileSystem, normalizedPath)
    }


    @Override
    Path resolve(Path other) {
        Preconditions.checkArgument(other instanceof NextflowLakeFSPath, "other must be an instance of %s", NextflowLakeFSPath.class.getName())
        if (other.isAbsolute()) {
            return other
        }

        if (!(other instanceof NextflowLakeFSPath)) {
            throw new IllegalArgumentException("Cannot resolve path of different type")
        }

        NextflowLakeFSPath otherPath = (NextflowLakeFSPath) other

        if (otherPath.ref() && otherPath.ref() != ref()) {
            // If the other path has a different reference, use it fully
            return otherPath
        }

        if (otherPath.objectPath.isEmpty()) {
            return this
        }

        String newPath = objectPath.isEmpty() ? otherPath.objectPath :
                objectPath.endsWith('/') ? objectPath + otherPath.objectPath :
                        objectPath + '/' + otherPath.objectPath

        return new NextflowLakeFSPath(lakeFSFileSystem, newPath)
    }

    @Override
    Path resolve(String other) {
        if (other.isEmpty()) {
            return this
        }

        if (other.startsWith('/')) {
            // Absolute path within same ref
            return new NextflowLakeFSPath(lakeFSFileSystem, other.substring(1))
        }

        String newPath = objectPath.isEmpty() ? other :
                objectPath.endsWith('/') ? objectPath + other :
                        objectPath + '/' + other

        return new NextflowLakeFSPath(lakeFSFileSystem, newPath)
    }

    @Override
    Path resolveSibling(Path other) {
        Preconditions.checkArgument(other instanceof NextflowLakeFSPath, "other must be an instance of %s", NextflowLakeFSPath.class.getName())
        Path parent = getParent()
        return parent == null ? other : parent.resolve(other)
    }

    @Override
    Path resolveSibling(String other) {
        Path parent = getParent()
        return parent == null ? new NextflowLakeFSPath(lakeFSFileSystem, other) : parent.resolve(other)
    }

    @Override
    Path relativize(Path other) {
        if (!(other instanceof NextflowLakeFSPath)) {
            throw new IllegalArgumentException("Cannot relativize path of different type")
        }

        NextflowLakeFSPath otherPath = (NextflowLakeFSPath) other

        if (otherPath.fileSystem != lakeFSFileSystem || otherPath.ref() != ref()) {
            throw new IllegalArgumentException("Cannot relativize path with different file system or reference")
        }

        if (objectPath == otherPath.objectPath) {
            return new NextflowLakeFSPath(lakeFSFileSystem, "")
        }

        if (objectPath.isEmpty()) {
            return new NextflowLakeFSPath(lakeFSFileSystem, otherPath.objectPath)
        }

        if (!otherPath.objectPath.startsWith(objectPath)) {
            throw new IllegalArgumentException("Cannot relativize paths that don't have hierarchical relationship ${otherPath.objectPath} ${objectPath}")
        }

        String relativePath = otherPath.objectPath.substring(objectPath.length())
        return Paths.get(relativePath)
    }

    @Override
    URI toUri() {
//        String encodedRef = ref ? URLEncoder.encode(ref, 'UTF-8') : ""
        String encodedPath = objectPath ? URLEncoder.encode(objectPath, 'UTF-8').replace('%2F', '/') : ""

        String path = ""
        if (encodedPath) {
            path += encodedPath
        }

        return new URI("${fileSystem.provider().getScheme()}://${repository()}/${ref()}/${path}")
    }

    @Override
    Path toAbsolutePath() {
        return this
    }

    @Override
    Path toRealPath(LinkOption... options) {
        return this
    }

    @Override
    File toFile() {
        throw new UnsupportedOperationException("toFile not supported by lakeFS provider")
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        return null
    }

    @Override
    Iterator<Path> iterator() {
        List<Path> paths = []

        if (ref()) {
            paths.add(new NextflowLakeFSPath(lakeFSFileSystem, ""))
        }

        if (objectPath) {
            String[] parts = objectPath.split('/')
            StringBuilder currentPath = new StringBuilder()

            for (String part : parts) {
                if (currentPath.length() > 0) {
                    currentPath.append('/')
                }
                currentPath.append(part)
                paths.add(new NextflowLakeFSPath(lakeFSFileSystem, currentPath.toString()))
            }
        }

        return paths.iterator()
    }

    @Override
    int compareTo(Path other) {
        if (!(other instanceof NextflowLakeFSPath)) {
            throw new ClassCastException("Cannot compare different path types")
        }

        NextflowLakeFSPath otherPath = (NextflowLakeFSPath) other

        int cmp = repository() <=> otherPath.repository()
        if (cmp != 0) return cmp

        cmp = ref() <=> otherPath.ref()
        if (cmp != 0) return cmp

        return objectPath <=> otherPath.objectPath
    }

    @Override
    String toString() {
//        if (objectPath.isEmpty()) {
//            return "/"
//        } else {
            return "${objectPath}"
//        }
    }

    @Override
    boolean equals(Object obj) {
        if (!(obj instanceof NextflowLakeFSPath)) {
            return false
        }

        NextflowLakeFSPath other = (NextflowLakeFSPath) obj
        return repository() == other.repository() &&
                ref() == other.ref() &&
                objectPath == other.objectPath
    }

    @Override
    int hashCode() {
        return Objects.hash(repository(), ref(), objectPath)
    }

    @Override
    void setTags(Map<String, String> tags) {
        this.tags = tags
    }

    @Override
    void setContentType(String type) {
        this.contentType = type
    }

    @Override
    void setStorageClass(String storageClass) {
        throw new UnsupportedOperationException()
    }
}
