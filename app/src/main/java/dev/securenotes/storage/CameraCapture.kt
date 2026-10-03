package dev.securenotes.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.securenotes.document.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable private data class Capture(val id: String, val noteId: String, val blockId: String)

/** Destination lives in SQLCipher; only a non-sensitive completion flag is readable while locked. */
class CameraCapture(private val context: Context, private val repository: NotesRepository) {
    private val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    private val resultFile = File(context.noBackupFilesDir, "camera-result")
    val revision = MutableStateFlow(0)
    private suspend fun pending(): Capture? = repository.access { v ->
        v.dao.secret("pending-camera")?.let { documentJson.decodeFromString<Capture>(it.toString(Charsets.UTF_8)) }
    }
    private fun file(capture: Capture): File { require(validId(capture.id)); return File(directory, "${capture.id}.jpg") }
    private fun uri(capture: Capture) = FileProvider.getUriForFile(context, "${context.packageName}.camera", file(capture))
    suspend fun prepare(note: Note): Uri {
        check(pending() == null) { "A capture is pending" }
        repository.flush()
        repository.save(note, preserveEmpty = true)
        val capture = Capture(newId(), note.id, newId())
        file(capture).createNewFile(); resultFile.delete()
        try { repository.access { it.dao.putSecret(SecretRow("pending-camera", documentJson.encodeToString(capture).toByteArray())) } }
        catch (e: Exception) { file(capture).delete(); throw e }
        return uri(capture)
    }
    fun result(success: Boolean) {
        atomicWrite(resultFile, byteArrayOf(if (success) 1 else 0))
        revision.value++
    }
    fun hasResult() = resultFile.exists()
    suspend fun hasPending() = pending() != null
    suspend fun cleanOrphans() {
        val keep = pending()?.let { file(it).name }
        directory.listFiles()?.filter { it.name != keep && it.extension == "jpg" && validId(it.nameWithoutExtension) }?.forEach { it.delete() }
        if (keep == null) resultFile.delete()
    }
    suspend fun finish(): Note? {
        val capture = pending() ?: return null
        if (!hasResult()) return null
        val success = android.util.AtomicFile(resultFile).readFully().firstOrNull() == 1.toByte()
        val initial = repository.notes.value.firstOrNull { it.id == capture.noteId }
        var updated: Note? = null
        if (success && initial != null) {
            check(file(capture).length() > 0) { "Camera returned no photo" }
            // Stable IDs make recovery safe if the process dies immediately after import commits.
            updated = repository.import(initial, uri(capture), "image/jpeg", capture.id, capture.blockId)
        } else if (initial?.isEmpty == true) repository.delete(initial.id)
        repository.access { it.dao.deleteSecret("pending-camera") }
        context.revokeUriPermission(uri(capture), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        file(capture).delete(); resultFile.delete()
        return updated
    }
}
