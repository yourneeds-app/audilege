package app.yourneeds.foliocue

import java.io.File
import java.io.InputStream

internal fun listeningCoverKey(file: File, book: Publication, online: OnlineBook?): String =
    "listen:${file.absolutePath}:${online?.detail?.id}:${online?.detail?.fileId}:${book.audioStream != null}:${online?.detail?.hasAudiobookCover}:${online?.detail?.coverRevision}"

internal fun listeningCoverSources(book: Publication, online: OnlineBook?): List<() -> InputStream?> = buildList {
    val serverCover: (() -> InputStream)? = online?.takeUnless { it.detail.hasAudiobookCover == false }?.let {
        { it.catalog.audiobookCover(it.detail.id) }
    }
    if (serverCover != null) add(serverCover)
    book.audiobookCover?.let { path -> add { book.open(path) } }
    if (book.audioStream == null) book.cover?.let { path -> add { book.open(path) } }
    online?.let { add { it.catalog.cover(it.detail.id) } }
    if (book.audioStream != null) book.cover?.let { path -> add { book.open(path) } }
}
