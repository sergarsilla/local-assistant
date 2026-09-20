package dev.localassistant.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies a user-picked model file into app storage so it stays available without the original. */
class ModelImporter(private val context: Context, private val store: ModelStore) {

    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val (name, size) = describe(uri)
        if (ModelFormat.of(name) == null) {
            return@withContext Result.failure(IllegalArgumentException(name))
        }
        val part = File(store.directory, "$name.part")
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open $name" }
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        Transfers.update(TransferState.Running(name, done, size))
                    }
                }
            }
            check(part.renameTo(File(store.directory, name))) { "Could not move the file into place" }
            store.recordSource(name, "imported")
            Transfers.clear(name)
            name
        }.onFailure {
            part.delete()
            Transfers.update(TransferState.Failed(name, it.message ?: it.javaClass.simpleName))
        }.also { store.refresh() }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "model") to -1L
    }
}
