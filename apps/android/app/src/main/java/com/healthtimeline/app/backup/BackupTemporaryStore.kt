package com.healthtimeline.app.backup

import android.content.Context
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes only backup work left by a previous process, not work created after [snapshot]. */
class BackupTemporaryStore(context: Context) {
    private val cacheDirectory = context.cacheDir
    private val safetyDirectory = File(context.filesDir, "safety_backups")

    class Snapshot internal constructor(internal val candidates: List<Candidate>)

    internal data class Candidate(
        val file: File,
        val owner: File,
        val canonicalOwner: File,
        val identity: Identity
    )

    internal data class Identity(val key: Any?, val directory: Boolean, val size: Long, val modified: Long)

    /** Call synchronously before the UI can start a new backup; this does not traverse staging trees. */
    fun snapshot(): Snapshot {
        val candidates = capture(cacheDirectory) { file, directory ->
            if (directory) STAGING_DIRECTORY.matches(file.name) else CACHE_FILE.matches(file.name)
        } + capture(safetyDirectory) { file, directory -> !directory && SAFETY_FILE.matches(file.name) }
        return Snapshot(candidates)
    }

    /** Run on an IO thread. Never scan again for newly created work or follow symbolic links. */
    fun cleanup(snapshot: Snapshot): Int = snapshot.candidates.count { candidate ->
        runCatching {
            if (!stillOwned(candidate)) return@runCatching false
            if (candidate.identity.directory) removeStagingTree(candidate.file)
            else Files.deleteIfExists(candidate.file.toPath())
        }.getOrDefault(false)
    }

    private fun capture(owner: File, accepts: (File, Boolean) -> Boolean): List<Candidate> = runCatching {
        if (!owner.isDirectory || Files.isSymbolicLink(owner.toPath())) return@runCatching emptyList()
        val canonicalOwner = owner.canonicalFile
        owner.listFiles().orEmpty().mapNotNull { file ->
            runCatching {
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if ((!attributes.isDirectory && !attributes.isRegularFile) || Files.isSymbolicLink(file.toPath()) ||
                    !accepts(file, attributes.isDirectory) || file.canonicalFile.parentFile != canonicalOwner) null
                else Candidate(file, owner, canonicalOwner, identity(attributes))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun stillOwned(candidate: Candidate): Boolean {
        if (Files.isSymbolicLink(candidate.owner.toPath()) || Files.isSymbolicLink(candidate.file.toPath()) ||
            candidate.owner.canonicalFile != candidate.canonicalOwner ||
            candidate.file.canonicalFile.parentFile != candidate.canonicalOwner) return false
        val current = identity(Files.readAttributes(candidate.file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
        // A replacement at the same name, or newly modified staging work, is not the captured work.
        return current == candidate.identity
    }

    private fun removeStagingTree(directory: File): Boolean {
        val root = directory.canonicalFile.toPath()
        fun inside(path: Path): Boolean = !Files.isSymbolicLink(path) && path.toFile().canonicalFile.toPath().startsWith(root)
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (inside(dir)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && inside(file)) Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                if (inside(dir)) Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun identity(attributes: BasicFileAttributes) = Identity(
        attributes.fileKey(), attributes.isDirectory, attributes.size(), attributes.lastModifiedTime().toMillis()
    )

    companion object {
        private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val STAGING_DIRECTORY = Regex("(?:portable-restore-|restore-|import-new-)$UUID_PATTERN")
        private val CACHE_FILE = Regex("(?:health-restore-|health-import-new-|health-backup-|health-v5-|safety-)-?[0-9]+\\.(?:zip|encrypted)")
        private val SAFETY_FILE = Regex("\\.before-replace-[0-9]+\\.tmp")
    }
}
