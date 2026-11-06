package gitviz

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Never descend into symbolic links, including links to directories outside the temporary repository. */
internal fun deleteTemporaryTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(dir: Path, exception: java.io.IOException?): FileVisitResult {
            if (exception != null) throw exception
            Files.delete(dir); return FileVisitResult.CONTINUE
        }
    })
}
