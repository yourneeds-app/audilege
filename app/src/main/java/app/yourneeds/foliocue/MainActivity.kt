package app.yourneeds.foliocue

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    internal val appVolume by lazy { AppVolumeControl(AndroidVolumeBackend(this)) }
    private var releaseVolumeKeys: (() -> Unit)? = null
    override fun onResume() { super.onResume(); appVolume.resume() }
    override fun onPause() { appVolume.pause(); super.onPause() }
    override fun onDestroy() { releaseVolumeKeys?.invoke(); releaseVolumeKeys = null; super.onDestroy() }
    private var playerRequest by mutableIntStateOf(0)
    override fun attachBaseContext(newBase: Context) {
        val language = newBase.getSharedPreferences("display", MODE_PRIVATE).getString("language", "").orEmpty()
        val config = Configuration(newBase.resources.configuration)
        if (language.isNotEmpty()) config.setLocale(Locale.forLanguageTag(language))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        releaseVolumeKeys = installVolumeKeys(window, appVolume)
        enableEdgeToEdge()
        if (intent.action == PlaybackService.OPEN_PLAYER) playerRequest++
        setContent { FolioCue(this, playerRequest) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == PlaybackService.OPEN_PLAYER) playerRequest++
    }
}

internal fun keyOf(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal data class OpenBook(
    val file: File, val publication: Publication, val mode: Mode? = null, val online: OnlineBook? = null,
    val serverProgress: org.json.JSONObject? = null, val resumeSession: Boolean = false,
)
internal data class BookDetail(
    val entry: CatalogEntry, val onlineBook: OnlineBook?, val localFile: File, val publication: Publication?,
    val companion: CatalogEntry? = null,
    val preferLocal: Boolean = false, val coverKey: String = localFile.absolutePath, val coverLoader: (() -> InputStream?)? = null,
)
private data class CatalogScreen(val title: String, val url: URI, val page: CatalogPage)

@Composable
private fun FolioCue(activity: MainActivity, playerRequest: Int) {
    val context = LocalContext.current
    val store = remember { Profiles(context) }
    val scope = rememberCoroutineScope()
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val display = remember { context.getSharedPreferences("display", Context.MODE_PRIVATE) }
    val readerPrefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    val updates = remember { AppUpdates(store, installed, scope, readerPrefs) }
    LaunchedEffect(updates) { updates.check(automatic = true) }
    var accent by remember { mutableStateOf(Accent.entries.getOrElse(readerPrefs.getInt("accent", 0)) { Accent.Teal }) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var statistics by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<BookDetail?>(null) }
    var storageRevision by remember { mutableIntStateOf(0) }
    val covers = remember { CoverStore() }
    var profiles by remember { mutableStateOf(store.list()) }
    var selected by remember { mutableStateOf(profiles.firstOrNull { it.id == display.getString("profile", null) }) }
    var tab by rememberSaveable { mutableStateOf(ShelfTab.Home) }
    var shelfSort by rememberSaveable { mutableStateOf(display.getInt("shelf.order", 0)) }
    var category by rememberSaveable { mutableStateOf("") }
    var facetAuthor by rememberSaveable { mutableStateOf("") }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var queueItems by remember { mutableStateOf<List<QueueTitle>>(emptyList()) }
    var queueError by remember { mutableStateOf(false) }
    var facetNarrator by rememberSaveable { mutableStateOf("") }
    var shelfStatus by rememberSaveable { mutableStateOf(ShelfStatus.All) }
    var facetSeries by rememberSaveable { mutableStateOf("") }
    var libraryCategory by rememberSaveable { mutableStateOf("") }
    var podcastShow by rememberSaveable { mutableStateOf("") }
    var podcastOrder by rememberSaveable { mutableStateOf(display.getInt("podcast.order", 0)) }
    var query by rememberSaveable { mutableStateOf("") }
    var list by rememberSaveable { mutableStateOf(display.getBoolean("shelf.list", false)) }
    var form by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var history by remember { mutableStateOf(listOf<CatalogScreen>()) }
    var opened by remember { mutableStateOf<OpenBook?>(null) }
    var busy by remember { mutableStateOf(false) }
    var catalogLoading by remember(selected) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val folder = remember(selected) { File(context.filesDir, "books/${selected?.id ?: "local"}") }
    var library by remember(folder) { mutableStateOf(emptyList<OpenBook>()) }
    LaunchedEffect(folder, opened, storageRevision) {
        if (opened == null) library = withContext(Dispatchers.IO) {
            folder.listFiles().orEmpty().filter { it.extension == "epub" }
                .mapNotNull { file -> runCatching { OpenBook(file, Publication(file)) }.getOrNull() }
                .sortedBy { it.publication.title.lowercase() }
        }
    }
    LaunchedEffect(playerRequest) {
        if (playerRequest > 0) AudilegePlayback.activeBook?.takeIf { it.file.parentFile == folder }?.let { opened = it }
    }
    val previousQueueFile = AudilegePlayback.advancedFrom
    LaunchedEffect(previousQueueFile) {
        if (previousQueueFile != null && opened?.file == previousQueueFile && opened?.mode == Mode.Listen) {
            opened = AudilegePlayback.activeBook
            details = null
        }
    }
    fun perform(failure: Int, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try { task() } catch (failure: CancellationException) {
                throw failure
            } catch (exception: Exception) {
                problem = (exception as? CatalogAccessException)?.messageId ?: failure
            } finally { busy = false }
        }
    }
    fun from(profile: Profile): LibraryCatalog = if (profile.accountLogin) {
        OrbitCatalog(profile, store.credential(profile), readSession = { store.credential(profile) }) { store.renew(profile,it) }
    } else Catalog(profile, store.credential(profile))
    val catalog = remember(selected) { selected?.let { runCatching { from(it) }.getOrNull() } }
    val orbit = catalog as? OrbitCatalog
    fun updateQueue(action: String? = null, id: Long? = null, remove: Boolean = false) {
        queueOpen = true
        queueError = false
        perform(R.string.stream_error) {
            try { queueItems = withContext(Dispatchers.IO) { requireNotNull(orbit).queue(action,id,remove) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { queueError = true }
        }
    }
    val streams = remember(orbit, folder) { orbit?.let {
        context.getSharedPreferences("reading-shelf.${folder.name}.${it.statisticsIdentity}", Context.MODE_PRIVATE)
    } }
    SyncPendingEditions(readerPrefs, folder, orbit)
    SyncListeningStatistics(selected, catalog as? OrbitCatalog)
    val migrationPage = history.lastOrNull()?.page
    LaunchedEffect(folder, catalog, migrationPage) {
        if (catalog is OrbitCatalog && migrationPage != null) {
            val matched = withContext(Dispatchers.IO) {
                OfflineBooks.matchExisting(readerPrefs, folder, migrationPage.entries)
            }
            if (matched) storageRevision++
        }
    }
    fun coverKey(entry: CatalogEntry) = "${selected?.id}:${entry.url}"
    fun cover(entry: CatalogEntry): InputStream? = when (catalog) {
        is OrbitCatalog -> entry.url.path.substringAfterLast('/').toLongOrNull()?.let(catalog::cover)
        is Catalog -> entry.coverUrl?.let(catalog::cover)
        else -> null
    }
    fun openDetail(detail: BookDetail, mode: Mode, local: Boolean, refreshProgress: Boolean = !local) {
        perform(R.string.book_error) {
            opened = withContext(Dispatchers.IO) {
                val publication = if (local && !detail.preferLocal) Publication(detail.localFile) else detail.publication ?: error("Publication")
                OpenBook(detail.localFile, publication, mode, detail.onlineBook,
                    if (refreshProgress) runCatching { detail.onlineBook?.progress() }.getOrNull() else null)
            }
        }
    }
    fun showDetails(entry: CatalogEntry, resumeIn: Mode? = null) {
        perform(R.string.stream_error) {
            val detail = withContext(Dispatchers.IO) {
                val orbit = catalog as? OrbitCatalog
                val online = orbit?.let { OnlineBook(it, it.details(entry.url, if (resumeIn != null) entry.editionFileId else null)) }
                val file = File(folder, keyOf(online?.let { "${entry.url}#${it.detail.fileId}" } ?: entry.url.toString()) + ".epub")
                if (online != null) {
                    OfflineBooks.associate(readerPrefs,file,online.detail)
                    OfflineBooks.rememberPodcast(readerPrefs,file,entry)
                }
                val companion = entry.companionId?.let { id ->
                    history.asSequence().flatMap { it.page.entries.asSequence() }
                        .firstOrNull { it.url.path.substringAfterLast('/').toLongOrNull()==id }
                        ?: runCatching { orbit?.companionEntry(id) }.getOrNull()
                }
                BookDetail(entry,online,file,online?.let { it.catalog.publication(it.detail,entry.title) }
                    ?: file.takeIf(File::isFile)?.let(::Publication), companion = companion, coverKey = coverKey(entry), coverLoader = { cover(entry) })
            }
            details = detail
            if (resumeIn != null && detail.publication != null) opened = withContext(Dispatchers.IO) {
                OpenBook(detail.localFile, detail.publication, if (detail.publication.spine.none { it.overlay.isNotBlank() }) Mode.Read else resumeIn, detail.onlineBook,
                    runCatching { detail.onlineBook?.progress() }.getOrNull())
            }
        }
    }
    fun localDetail(book: OpenBook): BookDetail {
        val publication = book.publication
        val online = OfflineBooks.online(readerPrefs,book.file,catalog as? OrbitCatalog)
        return BookDetail(OfflineBooks.localEntry(readerPrefs,book.file,publication),online,book.file,publication,
            preferLocal = true, coverLoader = { publication.cover?.let(publication::open) })
    }
    fun navigate(target: ShelfTab) {
        if (target == ShelfTab.Home && history.size > 1) history = history.take(1)
        podcastShow = ""
        facetAuthor = ""
        facetSeries = ""
        facetNarrator = ""
        query = ""
        details = null
        problem = null
        tab = target
    }
    fun browseFacet(author: String = "", series: String = "", narrator: String = "") {
        navigate(ShelfTab.Library)
        libraryCategory = ""
        if (history.size > 1) history = history.take(1)
        facetAuthor = author
        facetSeries = series
        facetNarrator = narrator
        shelfStatus = ShelfStatus.All
    }
    fun openPodcasts(show: String) {
        if (tab == ShelfTab.Library) libraryCategory = "podcast" else {
            navigate(ShelfTab.Discover)
            category = "podcast"
        }
        podcastShow = show
    }
    fun select(profile: Profile?) {
        if (selected?.id != profile?.id) AudilegePlayback.stop()
        selected = profile
        libraryCategory = ""
        shelfStatus = ShelfStatus.All
        queueOpen = false
        queueItems = emptyList()
        display.edit().putString("profile",profile?.id).apply()
        history = emptyList()
        navigate(ShelfTab.Home)
        reload++
    }
    LaunchedEffect(selected, reload) {
        val profile = selected ?: return@LaunchedEffect
        if (history.isNotEmpty()) return@LaunchedEffect
        catalogLoading = true
        try {
            val page = withContext(Dispatchers.IO) { (catalog ?: from(profile)).page(profile.catalog) }
            history = listOf(CatalogScreen(profile.label,profile.catalog,page))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            problem = (exception as? CatalogAccessException)?.messageId ?: R.string.catalog_error
        } finally { catalogLoading = false }
    }
    val importBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) perform(R.string.book_error) {
            val book = withContext(Dispatchers.IO) {
                val file = File(folder,keyOf(uri.toString())+".epub")
                context.contentResolver.openInputStream(uri)?.use { savePublication(it,file) } ?: error("EPUB")
                OpenBook(file,Publication(file))
            }
            details = localDetail(book)
            opened = book
        }
    }
    fun startImport() = importBook.launch(arrayOf("application/epub+zip","application/octet-stream"))
    fun profileForm(profile: Profile?) {
        editing = profile
        form = true
        problem = null
    }
    BackHandler(enabled = !form && !busy && opened == null && (statistics || settings || details != null || tab != ShelfTab.Home || history.size > 1)) {
        when {
            statistics -> statistics = false
            settings -> settings = false
            details != null -> { details = null; problem = null }
            podcastShow.isNotEmpty() -> podcastShow = ""
            history.size > 1 && tab != ShelfTab.Downloads && tab != ShelfTab.Profile -> history = history.dropLast(1)
            else -> navigate(ShelfTab.Home)
        }
    }

    MaterialTheme(colorScheme = appAppearanceColors(accent)) {
        val book = opened
        if (book == null) AppVolumeFeedback()
        if (book == null) SystemBarAppearance(activity)
        if (book != null) {
            LaunchedEffect(book.file, folder) {
                val detail = details?.takeIf { it.localFile == book.file }
                val bookKey = keyOf(book.file.absolutePath)
                readerPrefs.edit().apply {
                    putLong("$bookKey.shelfOpenedAt", System.currentTimeMillis())
                    book.mode?.let { putInt("$bookKey.mode", it.ordinal) }
                }.apply()
                if (streams != null && book.online != null) rememberStream(streams, book.file, book.online,
                    book.publication.title, book.publication.author, book.publication.spine.size, detail?.entry?.primaryFileId)
                val edit = display.edit().putString("last.${folder.name}",book.file.name)
                    .putString("last.title.${folder.name}",book.publication.title)
                    .putString("last.author.${folder.name}",book.publication.author)
                    .putInt("last.sections.${folder.name}",book.publication.spine.size)
                if (detail?.onlineBook != null && !detail.preferLocal) edit.putString("last.online.${folder.name}",detail.entry.url.toString())
                else if (book.online == null || detail?.preferLocal == true) edit.remove("last.online.${folder.name}")
                edit.apply()
            }
            key(book.file) { BookReader(activity,book.file,book.publication,book.mode,accent,book.online,book.serverProgress,covers,scope,
                resumeSession = book.resumeSession, backLabel = if (details != null && !settings && !statistics && !form)
                    R.string.reader_back else R.string.back) {
                val bookKey = keyOf(book.file.absolutePath)
                val index = readerPrefs.getInt("$bookKey.chapter",0)
                val chapterPath = book.publication.spine.getOrNull(index)?.path
                val chapterTitle = book.publication.contents.firstOrNull { it.path == chapterPath }?.label
                readerPrefs.edit().putInt("$bookKey.shelfChapterIndex",index).putString("$bookKey.shelfChapter",chapterTitle).apply()
                opened = null
            } }
            return@MaterialTheme
        }
        if (statistics) {
            ListeningStatisticsScreen(context, selected, catalog as? OrbitCatalog) { statistics = false }
            return@MaterialTheme
        }
        if (settings) {
            SettingsScreen(readerPrefs,updates,accent,
                setAccent = { accent = it; readerPrefs.edit().putInt("accent",it.ordinal).apply() },
                onProfiles = { settings = false; navigate(ShelfTab.Profile) }
            ) { settings = false }
            return@MaterialTheme
        }
        if (form) {
            ProfileForm(editing,busy,problem,dismiss = {
                form = false
                navigate(ShelfTab.Profile)
            }) { profile,password ->
                perform(if (profile.accountLogin) R.string.catalog_error else R.string.setup_error) {
                    val page = withContext(Dispatchers.IO) {
                        when {
                            password.isEmpty() -> from(profile).page(profile.catalog).also { store.save(profile,store.credential(profile)) }
                            profile.accountLogin -> {
                                val connected = OrbitCatalog.signIn(profile,password)
                                connected.page(profile.catalog).also { store.save(profile,connected.credential()) }
                            }
                            else -> Catalog(profile,password).page(profile.catalog).also { store.save(profile,password) }
                        }
                    }
                    profiles = store.list()
                    select(profile)
                    history = listOf(CatalogScreen(profile.label,profile.catalog,page))
                    form = false
                }
            }
            return@MaterialTheme
        }

        val current = history.lastOrNull().takeIf { tab == ShelfTab.Home || tab == ShelfTab.Library || tab == ShelfTab.Discover }
        val local = library.map { item ->
            val entry = OfflineBooks.localEntry(readerPrefs, item.file, item.publication)
            val caption = if (tab == ShelfTab.Downloads) stringResource(R.string.shelf_offline_size,
                Formatter.formatFileSize(context,item.file.length())) else ""
            ShelfItem(item.file.absolutePath,item.publication.title,item.publication.author,
                { item.publication.cover?.let(item.publication::open) }, { details = localDetail(item); problem = null },caption,
                entry.category, entry.added ?: item.file.lastModified(), entry.showKey, entry.showTitle,
                percent = readerPrefs.getFloat("${keyOf(item.file.absolutePath)}.shelfPercent", 0f).toDouble())
        }
        val remote = current?.page?.entries.orEmpty().map { entry ->
            ShelfItem(coverKey(entry),entry.title,entry.author,{ cover(entry) },{
                if (entry.book) showDetails(entry)
                else perform(R.string.catalog_error) {
                    val page = withContext(Dispatchers.IO) { requireNotNull(catalog).page(entry.url) }
                    query = ""
                    libraryCategory = ""
                    if (tab == ShelfTab.Home) tab = ShelfTab.Library
                    history = history + CatalogScreen(entry.title,entry.url,page)
                }
            }, category = entry.category, added = entry.added, showKey = entry.showKey.ifBlank { entry.showTitle.ifBlank { entry.author } },
                showTitle = entry.showTitle.ifBlank { entry.author }, series = entry.series, volume = entry.volume,
                bookId = if (entry.book) entry.url.path.substringAfterLast('/').toLongOrNull() else null, companionId = entry.companionId,
                authorAction = entry.author.takeIf { entry.book && entry.category != "podcast" && it.isNotBlank() }?.let { name -> { browseFacet(author = name) } },
                seriesAction = entry.series.takeIf(String::isNotBlank)?.let { name -> { browseFacet(series = name) } },
                narrator = entry.narrator, played = entry.played,
                percent = entry.editionFileId?.let { edition ->
                    val key = keyOf(File(folder,keyOf("${entry.url}#$edition") + ".epub").absolutePath)
                    if (readerPrefs.contains("$key.sync.pending") || entry.readingPercent == null)
                        readerPrefs.getFloat("$key.shelfPercent",0f).toDouble() else entry.readingPercent
                } ?: entry.readingPercent ?: 0.0,
                narratorAction = entry.narrator.takeIf(String::isNotBlank)?.let { name -> { browseFacet(narrator = name) } })
        }
        val duplicateCopies = remember(current,library,catalog,storageRevision) {
            val remoteEntries = current?.page?.entries.orEmpty().filter { it.book }
            val ids = remoteEntries.mapNotNull { it.url.path.substringAfterLast('/').toLongOrNull() }.toSet()
            val opdsFilenames = if (catalog is Catalog) remoteEntries.map { keyOf(it.url.toString())+".epub" }.toSet() else emptySet()
            library.filter { it.file.name in opdsFilenames ||
                OfflineBooks.online(readerPrefs,it.file,catalog as? OrbitCatalog)?.detail?.id?.let(ids::contains) == true
            }.map { it.file.absolutePath }.toSet()
        }
        val items = (remote + local.filterNot { it.key in duplicateCopies }).distinctBy { it.key }
        val recent = if (tab == ShelfTab.Home && query.isBlank()) key(folder, catalog) {
            val entries = remember(history) { history.flatMap { it.page.entries } }
            continueReadingBooks(readerPrefs, display, streams, folder, library, entries, selected, orbit, reload, ::cover) { localBook, entry, mode ->
                if (localBook != null) {
                    val detail = localDetail(localBook)
                    details = detail
                    openDetail(detail, mode, local = true, refreshProgress = detail.onlineBook != null && current != null)
                } else if (entry != null) showDetails(entry, resumeIn = mode)
            }
        } else emptyList()
        val next = current?.page?.next
        val more: (() -> Unit)? = if (current != null && next != null) ({
            perform(R.string.catalog_error) {
                val page = withContext(Dispatchers.IO) { requireNotNull(catalog).page(next) }
                if (history.lastOrNull() == current) history = history.dropLast(1) + current.copy(page = CatalogPage(
                    (current.page.entries + page.entries).distinctBy { it.url }, page.next?.takeUnless { it == next }))
            }
        }) else null
        LaunchedEffect(query, next, busy, problem, current?.url) {
            if (!busy && problem == null && more != null) {
                kotlinx.coroutines.delay(350)
                more()
            }
        }
        val renderDetail: @Composable (BookDetail) -> Unit = { d ->
            key(d.localFile) {
                DetailScreen(d,covers,readerPrefs,busy,problem,storageRevision,
                    close = { details = null; problem = null },
                    openCompanion = { showDetails(it) },
                    browseAuthor = { browseFacet(author = it) },
                    browseSeries = { browseFacet(series = it) },
                    browseNarrator = { browseFacet(narrator = it) },
                    enqueue = d.entry.url.path.substringAfterLast('/').toLongOrNull()
                        ?.takeIf { orbit?.supportsSharedQueue == true && d.entry.category in setOf("audio","audilege","podcast") }
                        ?.let { id -> { updateQueue("queue",id) } },
                    open = { mode,offline -> openDetail(d,mode,offline) },
                    download = {
                        perform(R.string.book_error) {
                            val publication = withContext(Dispatchers.IO) {
                                val online = d.onlineBook
                                if (online != null) {
                                    online.catalog.download(online.detail,d.localFile)
                                    OfflineBooks.associate(readerPrefs,d.localFile,online.detail)
                                    OfflineBooks.rememberPodcast(readerPrefs,d.localFile,d.entry)
                                    d.publication
                                } else {
                                    requireNotNull(catalog).download(d.entry.url,d.localFile)
                                    Publication(d.localFile)
                                }
                            }
                            details = d.copy(publication = publication)
                            storageRevision++
                        }
                    },
                    remove = {
                        perform(R.string.load_error) {
                            if (AudilegePlayback.activeBook?.file == d.localFile) AudilegePlayback.stop()
                            withContext(Dispatchers.IO) { check(d.localFile.delete() || !d.localFile.exists()) }
                            if (d.preferLocal) details = null else if (d.onlineBook == null) details = d.copy(publication = null)
                            storageRevision++
                        }
                    })
            }
        }
        if (queueOpen) QueueDialog(queueItems,busy,queueError,close={ queueOpen=false },
            retry={ updateQueue() }, promote={ updateQueue("promote",it) }, remove={ updateQueue("queue",it,true) },
            open={ id ->
                queueOpen=false
                scope.launch {
                    try {
                        val entry = withContext(Dispatchers.IO) { orbit?.companionEntry(id) } ?: return@launch
                        showDetails(entry, Mode.Listen)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { queueOpen=true;queueError=true }
                }
            })
        FolioDrawer(stringResource(R.string.app),listOfNotNull(
            MenuItem(R.string.shelf_start, selected = tab == ShelfTab.Home, icon = R.drawable.shelf_start) { navigate(ShelfTab.Home) },
            MenuItem(R.string.shelf_home, selected = tab == ShelfTab.Library, icon = R.drawable.shelf_book) { navigate(ShelfTab.Library) },
            MenuItem(R.string.shelf_categories, selected = tab == ShelfTab.Discover && category != "podcast", icon = R.drawable.shelf_grid) { category = ""; navigate(ShelfTab.Discover) },
            if (history.any { screen -> screen.page.entries.any { it.category == "podcast" } })
                MenuItem(R.string.shelf_podcasts, selected = tab == ShelfTab.Discover && category == "podcast",
                    icon = R.drawable.shelf_headphones) { openPodcasts("") } else null,
            if (orbit?.supportsSharedQueue == true)
                MenuItem(R.string.queue_title, icon = R.drawable.shelf_list) { if (!busy) updateQueue() } else null,
            MenuItem(R.string.listening_statistics, icon = R.drawable.menu_statistics) { if (!busy) statistics = true }
        ), brand = true, profileItem = MenuItem(R.string.profile_and_server, selected = tab == ShelfTab.Profile,
                icon = R.drawable.shelf_profile, supporting = selected?.label ?: stringResource(R.string.local)) {
                if (!busy) navigate(ShelfTab.Profile)
            },
            bottomItem = MenuItem(R.string.app_settings, icon = R.drawable.menu_settings) { if (!busy) settings = true }
        ) { menu ->
            Surface(Modifier.fillMaxSize(),color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val split = maxWidth >= 720.dp && tab != ShelfTab.Profile
                    val rail = maxWidth >= 840.dp
                    val detail = details
                    Row(Modifier.fillMaxSize()) {
                    if (rail) ShelfRail(tab,!busy) { navigate(it) }
                    Column {
                        Row(Modifier.weight(1f)) {
                            if (detail == null || split) Box(Modifier.weight(if (split && detail != null) .48f else 1f)) {
                                if (tab == ShelfTab.Profile) ProfilesScreen(profiles, selected, busy,
                                    openMenu = menu, select = ::select, edit = ::profileForm,
                                    remove = { profile ->
                                        perform(R.string.profile_remove_error) {
                                            val wasActive = selected?.id == profile.id
                                            if (wasActive) AudilegePlayback.stop()
                                            withContext(Dispatchers.IO) {
                                                removeProfileBooks(File(context.filesDir, "books"), profile.id)
                                                store.remove(profile)
                                            }
                                            profiles = store.list()
                                            if (wasActive) {
                                                select(null)
                                                navigate(ShelfTab.Profile)
                                            }
                                        }
                                    }, problem = problem)
                                else {
                                    val selectedCategory = when (tab) {
                                        ShelfTab.Library -> libraryCategory
                                        ShelfTab.Discover -> category
                                        else -> ""
                                    }
                                    ShelfContent(tab,items,recent,covers,busy,
                                        if (selectedCategory == "podcast") podcastOrder else shelfSort, {
                                            if (selectedCategory == "podcast") {
                                                podcastOrder = it; display.edit().putInt("podcast.order",it).apply()
                                            } else { shelfSort = it; display.edit().putInt("shelf.order",it).apply() }
                                        },
                                        selectedCategory, {
                                            if (tab == ShelfTab.Library) libraryCategory = it else category = it
                                            podcastShow = ""
                                        }, list,
                                        showKey = podcastShow, openPodcasts = ::openPodcasts,
                                        setList = { list = it; display.edit().putBoolean("shelf.list",it).apply() },
                                        openMenu = menu, importEpub = ::startImport, more = more,seeAll = { libraryCategory = ""; navigate(ShelfTab.Library) },
                                        header = {
                                            Column {
                                                updates.available?.let { release -> TextButton(onClick = { settings = true }) {
                                                    Text(stringResource(R.string.release_available,release.version))
                                                } }
                                                if (history.size > 1 && current != null) TextButton(onClick = { history = history.dropLast(1); query = "" },enabled = !busy) {
                                                    Text(stringResource(R.string.back)+" · "+current.title)
                                                }
                                                if (busy || (catalogLoading && tab != ShelfTab.Downloads)) LinearProgressIndicator(Modifier.fillMaxWidth())
                                                if (tab != ShelfTab.Downloads && detail == null) problem?.let { message ->
                                                    Text(stringResource(message),color = MaterialTheme.colorScheme.error)
                                                    TextButton(onClick = {
                                                        if (message == R.string.session_expired) profileForm(selected)
                                                        else { problem = null; history = emptyList(); reload++ }
                                                    },enabled = !busy) { Text(stringResource(R.string.retry)) }
                                                }
                                            }
                                        },empty = {
                                            if (!catalogLoading || tab == ShelfTab.Downloads) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                if (selected == null && tab == ShelfTab.Home && query.isBlank()) {
                                                    AudilegeWordmark(Modifier.padding(vertical = 8.dp).widthIn(max = 218.dp).fillMaxWidth())
                                                }
                                                Text(stringResource(when { tab == ShelfTab.Downloads -> R.string.shelf_empty_downloads
                                                    selected == null -> R.string.shelf_empty
                                                    else -> R.string.empty }))
                                                if (selected == null && tab != ShelfTab.Downloads) Button(onClick = { profileForm(null) }) {
                                                    Text(stringResource(R.string.shelf_connect))
                                                }
                                                if (tab != ShelfTab.Library) OutlinedButton(onClick = { startImport() }) { Text(stringResource(R.string.shelf_local_import)) }
                                            }
                                        },query = query,setQuery = { query = it },
                                        facetAuthor = facetAuthor, facetSeries = facetSeries,
                                        facetNarrator = facetNarrator,
                                        clearFacet = { facetAuthor = ""; facetSeries = ""; facetNarrator = "" },
                                        status = shelfStatus,setStatus = { shelfStatus = it })
                                }
                            }
                            if (detail != null) {
                                if (split) VerticalDivider()
                                Box(Modifier.weight(if (split) .52f else 1f)) { renderDetail(detail) }
                            }
                        }
                        MiniPlayer(covers, readerPrefs) { opened = it }
                        if (!rail && (detail == null || split)) ShelfTabs(tab,!busy) { navigate(it) }
                    }
                    }
                }
            }
        }
    }
}
