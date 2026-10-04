package dev.securenotes.storage

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest

fun atomicWrite(file: File, bytes: ByteArray) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
}
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class Vault(val context: Context, val directory: File, root: ByteArray, val checkAccess: () -> Unit) : Closeable {
    val id = directory.name
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sortKey = stringPreferencesKey("sort")
    private val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") }
    private val fileKey = Crypto.subkey(root, "files:$id")
    private val keyFile = File(directory, "files.key")
    private val keyset = if (keyFile.exists()) Crypto.open(fileKey, keyFile.readBytes(), id.toByteArray()) else Crypto.newStreamingKey().also {
        atomicWrite(keyFile, Crypto.seal(fileKey, it, id.toByteArray()))
    }
    val streaming = Crypto.streaming(keyset)
    val blobs = File(directory, "blobs").apply { mkdirs() }
    private val dbKey = Crypto.subkey(root, "database:$id")
    val database: NotesDatabase = Room.databaseBuilder(context, NotesDatabase::class.java, File(directory, "notes.db").absolutePath)
        .openHelperFactory(SupportOpenHelperFactory(dbKey.copyOf()))
        .addMigrations(MIGRATION_1_2)
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.query("PRAGMA temp_store=MEMORY").use { it.moveToFirst() }
                db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
            }
        }).build()
    val dao get() = database.dao()
    private val ioThread = HandlerThread("AttachmentIO").apply { start() }
    private val descriptors = mutableSetOf<ParcelFileDescriptor>()
    internal val descriptorCount: Int get() = synchronized(descriptors) { descriptors.size }
    @Volatile private var closed = false

    fun blob(id: String): File { require(validId(id)); return File(blobs, id) }
    fun read(id: String): InputStream { checkAccess(); return streaming.newDecryptingStream(blob(id).inputStream(), id.toByteArray()) }
    fun write(id: String, input: InputStream, maxBytes: Long = Long.MAX_VALUE, checkpoint: () -> Unit = {}): Pair<Long, String> {
        require(validId(id))
        val temporary = File(blobs, "$id.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            streaming.newEncryptingStream(temporary.outputStream(), id.toByteArray()).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkAccess(); checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(count.toLong() <= maxBytes - size) { "Attachment length mismatch" }
                    out.write(buffer, 0, count); digest.update(buffer, 0, count); size += count
                }
                buffer.fill(0)
            }
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            check(temporary.renameTo(blob(id))) { "Cannot finish import" }
            return size to digest.digest().hex()
        } finally { temporary.delete() }
    }
    fun descriptor(id: String, size: Long, extraCheck: () -> Unit = {}): ParcelFileDescriptor {
        checkAccess()
        val file = RandomAccessFile(blob(id), "r")
        val channel = streaming.newSeekableDecryptingChannel(file.channel, id.toByteArray())
        val holder = java.util.concurrent.atomic.AtomicReference<ParcelFileDescriptor?>()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val result = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size
                override fun onRead(offset: Long, count: Int, data: ByteArray): Int {
                    try {
                        checkAccess(); extraCheck(); check(!closed)
                        channel.position(offset)
                        val buffer = ByteBuffer.wrap(data, 0, count)
                        var total = 0
                        while (buffer.hasRemaining()) { val read = channel.read(buffer); if (read < 0) break; total += read }
                        return total
                    } catch (_: Exception) { throw ErrnoException("read", OsConstants.EIO) }
                }
                override fun onRelease() {
                    try { channel.close() } finally {
                        file.close()
                        synchronized(descriptors) { released.set(true); holder.get()?.let(descriptors::remove) }
                    }
                }
            }, Handler(ioThread.looper),
        )
        synchronized(descriptors) { holder.set(result); if (!released.get()) descriptors.add(result) }
        return result
    }
    fun decodeImage(id: String, size: Long, maxDimension: Int): android.graphics.Bitmap {
        val source = android.graphics.ImageDecoder.createSource(java.util.concurrent.Callable {
            android.content.res.AssetFileDescriptor(descriptor(id, size), 0, size)
        })
        return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val ratio = (maxDimension.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    suspend fun sort(): SortOrder = runCatching { SortOrder.valueOf(preferences.data.first()[sortKey] ?: "EDITED") }.getOrDefault(SortOrder.EDITED)
    suspend fun setSort(sort: SortOrder) { preferences.edit { it[sortKey] = sort.name } }
    // The old preference is read only when upgrading an existing vault.
    suspend fun noteOrder(): List<String>? = dao.secret("note-order")?.let {
        documentJson.decodeFromString<List<String>>(it.toString(Charsets.UTF_8))
    }
    suspend fun setNoteOrder(ids: List<String>) {
        dao.putSecret(SecretRow("note-order", documentJson.encodeToString(ids).toByteArray()))
    }
    suspend fun orderedNotes(): List<Note> {
        val all = dao.notes().map { it.decode() }
        val stored = noteOrder()
        if (stored == null) return sortedNotes(all, sort()).also { setNoteOrder(it.map(Note::id)) }
        val byId = all.associateBy(Note::id)
        val ids = stored.filter { it in byId }.distinct()
        val missing = all.filter { it.id !in ids }.sortedWith(compareByDescending<Note> { it.createdAt }.thenBy { it.id })
        val ordered = missing + ids.map { byId.getValue(it) }
        if (ordered.map(Note::id) != stored) setNoteOrder(ordered.map(Note::id))
        return ordered
    }
    /** The root wrapped by the recovery passphrase; generation-local so restore publishes it with the generation pointer. */
    val recoveryFile get() = File(directory, "recovery.key")
    suspend fun passphrase(): String? = dao.secret("passphrase")?.toString(Charsets.UTF_8)
    suspend fun passphraseConfirmed(): Boolean = dao.secret("passphrase-confirmed") != null
    override fun close() {
        closed = true
        synchronized(descriptors) { descriptors.forEach { runCatching { it.close() } }; descriptors.clear() }
        database.close(); scope.cancel(); ioThread.quitSafely(); dbKey.fill(0); fileKey.fill(0); keyset.fill(0)
    }
}
