package dev.securenotes.storage

import androidx.room.*
import dev.securenotes.document.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

@Entity(tableName = "notes")
data class NoteRow(@PrimaryKey val id: String, val title: String, val document: String, val createdAt: Long, val updatedAt: Long) {
    fun decode() = Note(id, title, documentJson.decodeFromString<Document>(document), createdAt, updatedAt)
    companion object { fun of(note: Note) = NoteRow(note.id, note.title, documentJson.encodeToString(note.document), note.createdAt, note.updatedAt) }
}
@kotlinx.serialization.Serializable
@Entity(tableName = "attachments", indices = [Index("noteId")])
data class AttachmentRow(
    @PrimaryKey val id: String, val noteId: String, val filename: String, val mime: String,
    val size: Long, val sha256: String, val indexed: Boolean = false, val nextPage: Int = 0,
)
@Entity(tableName = "sources", indices = [Index("noteId"), Index("attachmentId")])
data class SourceRow(@PrimaryKey val id: String, val noteId: String, val attachmentId: String?,
    val kind: String, val text: String, val offset: Int = 0)
@Entity(tableName = "postings", indices = [Index("term"), Index("length"), Index("sourceId")])
data class PostingRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sourceId: String, val term: String,
    val length: Int, val start: Int, val end: Int)
@Entity(tableName = "secrets") data class SecretRow(@PrimaryKey val name: String, val value: ByteArray)
@Entity(tableName = "imports") data class ImportRow(@PrimaryKey val id: String, val noteId: String,
    val uri: String, val filename: String, val mime: String)

@Dao interface NotesDao {
    @Query("SELECT * FROM notes") fun observe(): Flow<List<NoteRow>>
    @Query("SELECT * FROM notes") suspend fun notes(): List<NoteRow>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: String): NoteRow?
    @Upsert suspend fun put(row: NoteRow)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: String)
    @Query("SELECT * FROM attachments") suspend fun attachments(): List<AttachmentRow>
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun attachment(id: String): AttachmentRow?
    @Upsert suspend fun putAttachment(row: AttachmentRow)
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteAttachment(id: String)
    @Query("SELECT * FROM sources WHERE noteId = :noteId") suspend fun sources(noteId: String): List<SourceRow>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: String): SourceRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSource(row: SourceRow)
    @Insert suspend fun putPostings(rows: List<PostingRow>)
    @Query("DELETE FROM postings WHERE sourceId = :id") suspend fun deletePostings(id: String)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)
    @Query("SELECT DISTINCT term FROM postings WHERE term LIKE :prefix AND term > :after ORDER BY term LIMIT 512")
    suspend fun prefixTerms(prefix: String, after: String): List<String>
    @Query("SELECT DISTINCT term FROM postings WHERE length BETWEEN :lo AND :hi AND term > :after ORDER BY term LIMIT 512")
    suspend fun fuzzyTerms(lo: Int, hi: Int, after: String): List<String>
    @Query("SELECT * FROM postings WHERE term = :term AND id > :after ORDER BY id LIMIT 512")
    suspend fun postings(term: String, after: Long): List<PostingRow>
    @Query("SELECT value FROM secrets WHERE name = :name") suspend fun secret(name: String): ByteArray?
    @Upsert suspend fun putSecret(secret: SecretRow)
    @Query("DELETE FROM secrets WHERE name = :name") suspend fun deleteSecret(name: String)
    @Query("SELECT * FROM imports") suspend fun imports(): List<ImportRow>
    @Upsert suspend fun putImport(row: ImportRow)
    @Query("DELETE FROM imports WHERE id = :id") suspend fun deleteImport(id: String)
}
@Database(entities = [NoteRow::class, AttachmentRow::class, SourceRow::class, PostingRow::class, SecretRow::class, ImportRow::class], version = 1, exportSchema = true)
abstract class NotesDatabase : RoomDatabase() { abstract fun dao(): NotesDao }
