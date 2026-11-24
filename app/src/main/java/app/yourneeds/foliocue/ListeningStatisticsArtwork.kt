package app.yourneeds.foliocue

import android.content.Context
import java.io.File
import java.io.InputStream

internal fun statisticsCoverSources(context: Context, profile: String, key: String,
                                   catalog: OrbitCatalog?): List<() -> InputStream?> {
    val folder = File(context.filesDir, "books/$profile")
    val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    // A remembered server edition is only usable inside the selected profile's folder.
    val edition = if (preferences.getString("edition.folder.$key", null) == folder.absolutePath)
        preferences.getString("edition.$key", null)?.split(':') else null
    val bookId = if (key.startsWith("orbit:")) key.removePrefix("orbit:").toLongOrNull()?.takeIf { it > 0 }
        else edition?.takeIf { it.size == 2 }?.get(0)?.toLongOrNull()?.takeIf { it > 0 }
    return buildList {
        if (catalog != null && bookId != null) add { catalog.audiobookCover(bookId) }
        add {
            val file = folder.listFiles()?.firstOrNull { it.extension == "epub" && keyOf(it.absolutePath) == key }
            file?.let(::Publication)?.let { book -> (book.audiobookCover ?: book.cover)?.let(book::open) }
        }
        if (catalog != null && bookId != null) add { catalog.cover(bookId) }
    }
}
