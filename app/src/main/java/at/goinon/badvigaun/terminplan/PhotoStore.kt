package at.goinon.badvigaun.terminplan

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Stores scan sources inside the app so gallery permissions and camera cache expiry cannot break them. */
class PhotoStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "schedule_photos")

    suspend fun save(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val target = File(directory, "${UUID.randomUUID()}.img")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Das Foto konnte nicht geöffnet werden." }
                target.outputStream().use(input::copyTo)
            }
            target.absolutePath
        }.getOrNull()
    }

    fun deleteUnreferenced(items: List<ScheduleItem>) {
        val referenced = items.mapNotNull { it.photoPath }.map(::File).map { it.absolutePath }.toSet()
        directory.listFiles()?.forEach { file -> if (file.absolutePath !in referenced) file.delete() }
    }

    fun deleteAll() {
        directory.listFiles()?.forEach(File::delete)
        directory.delete()
    }
}
