package dev.securenotes.backup

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

@Serializable enum class BackupMode { DISABLED, LEGACY, PAIR }
@Serializable enum class BackupProblem { IO, ACCESS, MANIFEST_TOO_LARGE, PHRASE_MISSING, PHRASE_CHANGED, ACTIVE_DAMAGED, INVALID_CONFIGURATION, BUSY, RESTART_REQUIRED, SELECT_AGAIN }
@Serializable data class BackupError(val problem: BackupProblem, val step: String, val diagnostic: String = "", val revision: Long? = null)
@Serializable data class BackupSlot(val uri: String, val name: String, val length: Long, val sha256: String, val nameConfirmed: Boolean = true)
enum class BackupAdmission {
    ACCEPTED, BUSY, LOCKED, RESTART_REQUIRED, UNAVAILABLE, PHRASE_REVIEW_REQUIRED, SELECT_AGAIN, INELIGIBLE, FAILED;
    val accepted: Boolean get() = this == ACCEPTED
}
/** One atomic secret, deliberately excluded from the exported manifest. */
@Serializable data class BackupConfiguration(
    val version: Int = 1,
    val mode: BackupMode = BackupMode.DISABLED,
    val folder: String? = null,
    val slots: List<BackupSlot> = emptyList(),
    val active: Int = 1,
    val phraseHash: String? = null,
    val fingerprint: String? = null,
    val savedAt: Long? = null,
    val pending: Boolean = false,
    val error: BackupError? = null,
    val legacyUri: String? = null,
    val legacyName: String? = null,
) {
    fun validate() {
        require(version == 1)
        when (mode) {
            BackupMode.DISABLED -> require(slots.isEmpty() && folder == null && legacyUri == null)
            BackupMode.LEGACY -> require(!legacyUri.isNullOrBlank() && slots.isEmpty())
            BackupMode.PAIR -> {
                require(!folder.isNullOrBlank() && slots.size == 2 && active in 0..1)
                require(slots.map { it.uri }.distinct().size == 2)
                require(slots.all { it.uri.isNotBlank() && it.name.isNotBlank() && it.length > 0 && validDigest(it.sha256) })
                require(validDigest(phraseHash) && validDigest(fingerprint))
            }
        }
    }
    val grant: String? get() = if (mode == BackupMode.LEGACY) legacyUri else folder
    val managedUris: Set<String> get() = slots.map { it.uri }.toSet() + listOfNotNull(legacyUri)
    fun afterRestore(restoredPhraseHash: String): BackupConfiguration {
        if (mode != BackupMode.PAIR) return this
        val nextError = if (phraseHash != restoredPhraseHash) BackupError(BackupProblem.PHRASE_CHANGED, "restore")
        else error?.takeUnless { it.problem in setOf(BackupProblem.MANIFEST_TOO_LARGE, BackupProblem.PHRASE_CHANGED, BackupProblem.PHRASE_MISSING) }
        return copy(pending = true, error = nextError)
    }
}
data class BackupStatus(
    val configuration: BackupConfiguration,
    val operation: String? = null,
    val error: BackupError? = configuration.error,
    val setupError: BackupError? = null,
    val restartRequired: Boolean = false,
    val grantAvailable: Boolean? = null,
) {
    val location: String? get() = when (configuration.mode) {
        BackupMode.DISABLED -> null
        BackupMode.LEGACY -> configuration.legacyName
        BackupMode.PAIR -> configuration.slots.joinToString("\n") { it.name }
    }
    val savedAt: Long? get() = configuration.savedAt
}
data class BackupInspection(val fingerprint: String, val phraseHash: String, val revision: Long, val empty: Boolean)
data class PreparedBackup(val file: File, val content: BackupInspection, val length: Long, val sha256: String) : Closeable {
    override fun close() { file.delete() }
}
interface BackupSource {
    val revision: Long
    suspend fun inspect(): BackupInspection
    suspend fun prepare(progress: (String) -> Unit): PreparedBackup
}
interface BackupSettingsStore {
    suspend fun load(): BackupConfiguration
    /** Call [beginWrite] after session/access checks, immediately before the atomic write. */
    suspend fun save(configuration: BackupConfiguration, beginWrite: () -> Unit)
}
data class BackupDocument(val uri: String, val name: String, val nameConfirmed: Boolean = true)
interface BackupOutput : Closeable {
    val stream: OutputStream
    fun truncate(length: Long)
    fun sync()
}
interface BackupFiles {
    fun hasGrant(folder: String): Boolean
    fun hasAnyGrant(folder: String): Boolean = hasGrant(folder)
    fun retain(folder: String)
    fun release(folder: String)
    fun create(folder: String, name: String): String
    fun displayName(uri: String): String?
    fun delete(uri: String)
    fun openWrite(uri: String, mode: String): BackupOutput
    fun openRead(uri: String): InputStream
    fun diagnostic(error: Throwable): String = error.causeChain().take(5).joinToString(" / ") { it.javaClass.simpleName }
}
/** Identity-based detection also handles provider exceptions with cyclic cause chains. */
internal fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) { yield(current); current = current.cause }
}
internal fun Throwable.hasSecurityCause() = causeChain().any { it is SecurityException }
internal fun BackupFiles.document(uri: String, requestedName: String): BackupDocument {
    val actual = try { displayName(uri)?.takeIf { it.isNotBlank() } }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { if (e.hasSecurityCause()) throw e; null }
    return BackupDocument(uri, actual ?: requestedName, actual != null)
}
class BackupFailure(val problem: BackupProblem) : Exception()
class ManifestTooLarge(val revision: Long) : Exception()
internal fun validDigest(value: String?) = value != null && value.matches(Regex("[0-9a-f]{64}"))
internal fun ByteArray.digestHex() = joinToString("") { "%02x".format(it) }
fun ciphertextDigest(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    return digest.digest().digestHex()
}
/** Every write is closed then streamed back, including truncating modes. */
internal fun BackupFiles.writeVerified(uri: String, artifact: PreparedBackup, step: (String) -> Unit) {
    var opened: BackupOutput? = null
    var mode = ""
    for (candidate in listOf("rwt", "wt", "w")) {
        step("open-$candidate")
        try { opened = openWrite(uri, candidate); mode = candidate; break }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (e.hasSecurityCause() || candidate == "w") throw e }
    }
    checkNotNull(opened).use { output ->
        step("write-$mode")
        artifact.file.inputStream().use { it.copyTo(output.stream, 64 * 1024) }
        step("flush"); output.stream.flush()
        if (mode == "w") { step("truncate"); output.truncate(artifact.length) }
        step("sync"); output.sync()
        step("close")
    }
    step("verify")
    verify(BackupSlot(uri, "", artifact.length, artifact.sha256))
}
internal fun BackupFiles.verify(slot: BackupSlot) {
    openRead(slot.uri).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var remaining = slot.length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw BackupFailure(BackupProblem.ACTIVE_DAMAGED)
            digest.update(buffer, 0, count); remaining -= count
        }
        if (input.read() != -1 || digest.digest().digestHex() != slot.sha256) throw BackupFailure(BackupProblem.ACTIVE_DAMAGED)
    }
}
