package dev.securenotes.backup

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

@Serializable enum class BackupMode { DISABLED, LEGACY, PAIR }
@Serializable enum class BackupProblem { IO, ACCESS, MANIFEST_TOO_LARGE, PHRASE_MISSING, PHRASE_CHANGED, ACTIVE_DAMAGED, INVALID_CONFIGURATION, BUSY, RESTART_REQUIRED, SELECT_AGAIN, ATTACHMENT_DAMAGED }
@Serializable data class BackupError(val problem: BackupProblem, val step: String, val diagnostic: String = "", val revision: Long? = null)
@Serializable data class BackupSlot(val uri: String, val name: String, val length: Long, val sha256: String, val nameConfirmed: Boolean = true)
@Serializable enum class SlotCheck { VERIFIED, INCOMPLETE, DAMAGED }
/** A completed full check, tied to the exact index digests it read; a later save makes it history. */
@Serializable data class FullCheck(val at: Long, val digests: List<String>, val slots: List<SlotCheck>)
/** A pair's attachment directory, created when its first attachment is saved. Its name is always provider-confirmed. */
@Serializable data class BackupFolder(val uri: String, val name: String)
/** An index document an unfinished setup created; as for active slots, its name may be unconfirmed. */
@Serializable data class SetupDocument(val uri: String, val name: String, val nameConfirmed: Boolean = true)
/**
 * A new pair that has not been activated. It is kept apart from the active pair, which keeps saving meanwhile, and
 * lists every directory and index document it created, recorded before the provider is asked their names, so
 * discarding it removes exactly those and the attachment files its records name.
 */
@Serializable data class PendingSetup(
    val folder: String,
    val pairTag: String,
    val timestamp: String,
    /** The phrase the setup was started or last resumed under; a different one needs review before it continues. */
    val phraseHash: String,
    /** The folder permission was taken for this setup; discarding releases it unless the active pair uses it. */
    val ownsGrant: Boolean,
    val attachmentFolder: BackupFolder? = null,
    val slots: List<SetupDocument> = emptyList(),
    val created: List<String> = emptyList(),
) {
    fun validate() {
        require(folder.isNotBlank() && dev.securenotes.document.validId(pairTag) && timestamp.isNotBlank() && validDigest(phraseHash))
        require(created.all { it.isNotBlank() } && created.distinct().size == created.size)
        require(slots.size <= 2 && slots.all { it.uri in created && it.name.isNotBlank() })
        attachmentFolder?.let { require(it.uri in created && it.name.isNotBlank()) }
    }
}
/**
 * Device record of one attachment file in a pair: plaintext identity, document, and the expected ciphertext. Measurements
 * are committed before the document is opened for writing; [complete] only after it was written and read back.
 */
@Serializable data class AttachmentFile(
    val id: String, val size: Long, val sha256: String,
    val uri: String, val name: String, val length: Long, val digest: String,
    val complete: Boolean = false,
)
enum class BackupAdmission {
    ACCEPTED, BUSY, LOCKED, RESTART_REQUIRED, UNAVAILABLE, PHRASE_REVIEW_REQUIRED, SELECT_AGAIN, INELIGIBLE, FAILED;
    val accepted: Boolean get() = this == ACCEPTED
}
/**
 * One atomic secret, deliberately excluded from the exported manifest. Version 2 added [pairTag] and [attachmentFolder];
 * version 1 records still load. A pair with a [pairTag] saves attachments once each; one without saves whole files.
 */
@Serializable data class BackupConfiguration(
    val version: Int = 2,
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
    /** Keys the pair's attachment key and file records in device settings. */
    val pairTag: String? = null,
    val attachmentFolder: BackupFolder? = null,
    val check: FullCheck? = null,
    val setup: PendingSetup? = null,
) {
    fun validate() {
        require(version in 1..2)
        setup?.let { it.validate(); require(it.pairTag != pairTag) }
        when (mode) {
            BackupMode.DISABLED -> require(slots.isEmpty() && folder == null && legacyUri == null && pairTag == null && attachmentFolder == null && check == null)
            BackupMode.LEGACY -> require(!legacyUri.isNullOrBlank() && slots.isEmpty() && pairTag == null && attachmentFolder == null && check == null)
            BackupMode.PAIR -> {
                require(!folder.isNullOrBlank() && slots.size == 2 && active in 0..1)
                require(slots.map { it.uri }.distinct().size == 2)
                require(slots.all { it.uri.isNotBlank() && it.name.isNotBlank() && it.length > 0 && validDigest(it.sha256) })
                require(validDigest(phraseHash) && validDigest(fingerprint))
                pairTag?.let { require(dev.securenotes.document.validId(it)) }
                attachmentFolder?.let { require(pairTag != null && it.uri.isNotBlank() && it.name.isNotBlank() && slots.none { slot -> slot.uri == it.uri }) }
                check?.let { require(it.digests.size == 2 && it.digests.all(::validDigest) && it.slots.size == 2) }
            }
        }
    }
    /** The last full check read exactly the files that are recorded now. */
    val checkIsCurrent: Boolean get() = check != null && check.digests == slots.map { it.sha256 }
    val grant: String? get() = if (mode == BackupMode.LEGACY) legacyUri else folder
    val managedUris: Set<String> get() = slots.map { it.uri }.toSet() + listOfNotNull(legacyUri, attachmentFolder?.uri) + setup?.created.orEmpty()
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
    /** Why the last full check could not finish; it changed nothing. */
    val checkError: BackupError? = null,
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
/** An attachment's plaintext identity as captured in a snapshot. */
data class BackupAttachment(val id: String, val size: Long, val sha256: String)
/** Metadata captured once per save; [manifest] is the source's own content and is opaque to the coordinator. */
class BackupSnapshot(val content: BackupInspection, val attachments: List<BackupAttachment>, val manifest: Any? = null)
/** An attachment of the captured snapshot was removed or replaced meanwhile; a later attempt captures current metadata. */
class SnapshotChanged : Exception()
interface BackupSource {
    val revision: Long
    suspend fun inspect(): BackupInspection
    /** A whole version-3 file, for pairs created before attachments were saved separately. */
    suspend fun prepare(progress: (String) -> Unit): PreparedBackup
    suspend fun capture(progress: (String) -> Unit): BackupSnapshot
    /** Encrypts one attachment of [snapshot] under the pair's key, checking its plaintext length and SHA-256. */
    suspend fun prepareAttachment(snapshot: BackupSnapshot, attachment: BackupAttachment, key: ByteArray): PreparedBackup
    /** A version-4 index naming [files] (attachment ID to actual filename) in [folder]. */
    suspend fun prepareIndex(snapshot: BackupSnapshot, key: ByteArray, folder: String?, files: Map<String, String>): PreparedBackup
    /** Authenticates and validates a backup file with this vault's phrase, checking any attachments it contains. */
    suspend fun checkIndex(input: InputStream): CheckedIndex
    /** Authenticates one attachment file and checks its plaintext length and SHA-256, discarding the plaintext. */
    suspend fun checkAttachment(input: InputStream, key: ByteArray, attachment: BackupAttachment)
}
/** What a checked backup file names: for a version-4 index, its separate attachment files and their key. */
class CheckedIndex(val attachments: List<BackupAttachment>, val files: Map<String, String>?, val key: ByteArray?)
interface BackupSettingsStore {
    suspend fun load(): BackupConfiguration
    /** Call [beginWrite] after session/access checks, immediately before the atomic write. */
    suspend fun save(configuration: BackupConfiguration, beginWrite: () -> Unit)
    suspend fun key(pairTag: String): ByteArray?
    suspend fun saveKey(pairTag: String, key: ByteArray, beginWrite: () -> Unit)
    suspend fun files(pairTag: String): Map<String, AttachmentFile>
    suspend fun saveFile(pairTag: String, file: AttachmentFile, beginWrite: () -> Unit)
    /** Removes a pair's key and file records; its files stay where they are. */
    suspend fun forget(pairTag: String)
}
/** A direct child of a backup directory; [size] is null when the provider does not report one. */
data class BackupChild(val uri: String, val name: String, val size: Long?, val directory: Boolean = false)
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
    /** Creates a document in [folder], a granted tree or a directory created under one. */
    fun create(folder: String, name: String): String
    fun createDirectory(folder: String, name: String): String
    fun children(directory: String): List<BackupChild>
    /** The document's size from its own descriptor; null when unknown. Throws FileNotFoundException when it is missing. */
    fun measure(uri: String): Long?
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
