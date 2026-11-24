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
    private var playerRequest by mutableIntStateOf(0)
    override fun attachBaseContext(newBase: Context) {
        val language = newBase.getSharedPreferences("display", MODE_PRIVATE).getString("language", "").orEmpty()
        val config = Configuration(newBase.resources.configuration)
        if (language.isNotEmpty()) config.setLocale(Locale.forLanguageTag(language))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var tab by rememberSaveable { mutableStateOf(ShelfTab.Library) }
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
    SyncPendingEditions(readerPrefs, folder, catalog as? OrbitCatalog)
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
    fun openDetail(detail: BookDetail, mode: Mode, local: Boolean) {
        perform(R.string.book_error) {
            opened = withContext(Dispatchers.IO) {
                val publication = if (local && !detail.preferLocal) Publication(detail.localFile) else detail.publication ?: error("Publication")
                OpenBook(detail.localFile, publication, mode, detail.onlineBook,
                    if (local) null else runCatching { detail.onlineBook?.progress() }.getOrNull())
            }
        }
    }
    fun showDetails(entry: CatalogEntry, resume: Boolean = false) {
        perform(R.string.stream_error) {
            val detail = withContext(Dispatchers.IO) {
                val orbit = catalog as? OrbitCatalog
                val online = orbit?.let { OnlineBook(it, it.details(entry.url)) }
                val file = File(folder, keyOf(online?.let { "${entry.url}#${it.detail.fileId}" } ?: entry.url.toString()) + ".epub")
                if (online != null) OfflineBooks.associate(readerPrefs,file,online.detail)
                BookDetail(entry,online,file,online?.let { it.catalog.publication(it.detail,entry.title) }
                    ?: file.takeIf(File::isFile)?.let(::Publication), coverKey = coverKey(entry), coverLoader = { cover(entry) })
            }
            details = detail
            if (resume && detail.publication != null) opened = withContext(Dispatchers.IO) {
                OpenBook(detail.localFile, detail.publication,savedMode(readerPrefs,detail.localFile,detail.publication),detail.onlineBook,
                    runCatching { detail.onlineBook?.progress() }.getOrNull())
            }
        }
    }
    fun localDetail(book: OpenBook): BookDetail {
        val publication = book.publication
        val online = OfflineBooks.online(readerPrefs,book.file,catalog as? OrbitCatalog)
        return BookDetail(CatalogEntry(publication.title,book.file.toURI(),true,publication.author),online,book.file,publication,
            preferLocal = true, coverLoader = { publication.cover?.let(publication::open) })
    }
    fun navigate(target: ShelfTab) {
        query = ""
        details = null
        problem = null
        tab = target
    }
    fun select(profile: Profile?) {
        if (selected?.id != profile?.id) AudilegePlayback.stop()
        selected = profile
        display.edit().putString("profile",profile?.id).apply()
        history = emptyList()
        navigate(ShelfTab.Library)
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
    BackHandler(enabled = !form && !busy && opened == null && (statistics || settings || details != null || tab != ShelfTab.Library || history.size > 1)) {
        when {
            statistics -> statistics = false
            settings -> settings = false
            details != null -> { details = null; problem = null }
            history.size > 1 && tab != ShelfTab.Downloads && tab != ShelfTab.Profile -> history = history.dropLast(1)
            else -> navigate(ShelfTab.Library)
        }
    }

    MaterialTheme(colorScheme = appAppearanceColors(accent)) {
        val book = opened
        if (book == null) SystemBarAppearance(activity)
        if (book != null) {
            LaunchedEffect(book.file, folder) {
                val detail = details?.takeIf { it.localFile == book.file }
                book.mode?.let { readerPrefs.edit().putInt("${keyOf(book.file.absolutePath)}.mode",it.ordinal).apply() }
                val edit = display.edit().putString("last.${folder.name}",book.file.name)
                    .putString("last.title.${folder.name}",book.publication.title)
                    .putString("last.author.${folder.name}",book.publication.author)
                    .putInt("last.sections.${folder.name}",book.publication.spine.size)
                if (detail?.onlineBook != null && !detail.preferLocal) edit.putString("last.online.${folder.name}",detail.entry.url.toString())
                else if (book.online == null || detail?.preferLocal == true) edit.remove("last.online.${folder.name}")
                edit.apply()
            }
            key(book.file) { BookReader(activity,book.file,book.publication,book.mode,accent,book.online,book.serverProgress,covers,scope,
                resumeSession = book.resumeSession) {
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

        val current = history.lastOrNull().takeIf { tab == ShelfTab.Library || tab == ShelfTab.Discover }
        val local = library.map { item ->
            val caption = if (tab == ShelfTab.Downloads) stringResource(R.string.shelf_offline_size,
                Formatter.formatFileSize(context,item.file.length())) else ""
            ShelfItem(item.file.absolutePath,item.publication.title,item.publication.author,
                { item.publication.cover?.let(item.publication::open) }, { details = localDetail(item); problem = null },caption)
        }
        val remote = current?.page?.entries.orEmpty().map { entry ->
            ShelfItem(coverKey(entry),entry.title,entry.author,{ cover(entry) },{
                if (entry.book) showDetails(entry)
                else perform(R.string.catalog_error) {
                    val page = withContext(Dispatchers.IO) { requireNotNull(catalog).page(entry.url) }
                    query = ""
                    history = history + CatalogScreen(entry.title,entry.url,page)
                }
            })
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
        val lastName = display.getString("last.${folder.name}",null)
        val lastLocal = library.firstOrNull { it.file.name == lastName }
        val onlineUrl = display.getString("last.online.${folder.name}",null)?.let { runCatching { URI(it) }.getOrNull() }
        val recentItem = when {
            onlineUrl != null && catalog is OrbitCatalog -> {
                val entry = history.flatMap { it.page.entries }.firstOrNull { it.url == onlineUrl }
                    ?: CatalogEntry(display.getString("last.title.${folder.name}","").orEmpty(),onlineUrl,true,display.getString("last.author.${folder.name}","").orEmpty())
                ShelfItem(coverKey(entry),entry.title,entry.author,{ cover(entry) },{
                    if (lastLocal != null && current == null && problem != null) {
                        val d = localDetail(lastLocal)
                        details = d
                        openDetail(d,savedMode(readerPrefs,d.localFile,d.publication),true)
                    } else showDetails(entry,resume = true)
                })
            }
            lastLocal != null -> local.firstOrNull { it.key == lastLocal.file.absolutePath }?.copy(open = {
                val d = localDetail(lastLocal)
                details = d
                openDetail(d,savedMode(readerPrefs,d.localFile,d.publication),true)
            })
            else -> null
        }
        val recent = recentItem?.let { entry ->
            val lastFile = lastName?.let { File(folder,it) }
            val mode = lastFile?.let { savedMode(readerPrefs,it,lastLocal?.publication) }
            val lastKey = lastFile?.let { keyOf(it.absolutePath) }
            val index = lastKey?.let { id ->
                watchPreference(readerPrefs,setOf("$id.chapter")) { readerPrefs.getInt("$id.chapter",0) }
            }
            val sections = display.getInt("last.sections.${folder.name}",0)
            val savedChapter = lastKey?.takeIf { readerPrefs.getInt("$it.shelfChapterIndex",-1) == index }
                ?.let { readerPrefs.getString("$it.shelfChapter",null) }
            val chapter = savedChapter ?: if (index != null && sections > 0) stringResource(R.string.shelf_section,index+1,sections) else ""
            val percent = lastKey?.let { id ->
                watchPreference(readerPrefs,setOf("$id.shelfPercent")) { readerPrefs.getFloat("$id.shelfPercent",-1f).toInt() }
            }?.takeIf { it in 0..100 }?.toFloat()
            RecentBook(entry,mode?.let { modeAction(it,true) } ?: R.string.shelf_continue_read,chapter,
                percent?.div(100f) ?: if (index != null && sections > 0) (index.toFloat()/sections).coerceIn(0f,1f) else null)
        }
        val next = current?.page?.next
        val more: (() -> Unit)? = if (current != null && next != null) ({
            perform(R.string.catalog_error) {
                val page = withContext(Dispatchers.IO) { requireNotNull(catalog).page(next) }
                if (history.lastOrNull() == current) history = history.dropLast(1) + current.copy(page = CatalogPage(
                    (current.page.entries + page.entries).distinctBy { it.url }, page.next?.takeUnless { it == next }))
            }
        }) else null
        LaunchedEffect(query, next, busy, problem, current?.url) {
            if (query.isNotBlank() && !busy && problem == null && more != null) {
                kotlinx.coroutines.delay(350)
                more()
            }
        }
        val renderDetail: @Composable (BookDetail) -> Unit = { d ->
            key(d.localFile) {
                DetailScreen(d,covers,readerPrefs,busy,problem,storageRevision,
                    close = { details = null; problem = null },
                    open = { mode,offline -> openDetail(d,mode,offline) },
                    download = {
                        perform(R.string.book_error) {
                            val publication = withContext(Dispatchers.IO) {
                                val online = d.onlineBook
                                if (online != null) {
                                    online.catalog.download(online.detail,d.localFile)
                                    OfflineBooks.associate(readerPrefs,d.localFile,online.detail)
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
        FolioDrawer(stringResource(R.string.app),listOf(
            MenuItem(R.string.shelf_profile, icon = R.drawable.shelf_profile) { if (!busy) navigate(ShelfTab.Profile) },
            MenuItem(R.string.import_epub, icon = R.drawable.menu_upload) { if (!busy) startImport() },
            MenuItem(R.string.listening_statistics, icon = R.drawable.menu_statistics) { if (!busy) statistics = true }
        ), brand = true, profile = selected,
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
                                if (tab == ShelfTab.Profile) Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
                                    FolioBar(stringResource(R.string.shelf_profile),menu)
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(stringResource(R.string.profiles),style = MaterialTheme.typography.titleLarge)
                                        OutlinedButton(onClick = { select(null) },enabled = !busy) { Text(stringResource(R.string.local)) }
                                        profiles.forEach { profile ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                TextButton(onClick = { select(profile) },enabled = !busy,modifier = Modifier.weight(1f)) {
                                                    Column { Text(profile.label); Text(profile.user,style = MaterialTheme.typography.bodySmall) }
                                                }
                                                TextButton(onClick = { profileForm(profile) },enabled = !busy) { Text(stringResource(R.string.edit)) }
                                            }
                                        }
                                        Button(onClick = { profileForm(null) },enabled = !busy) { Text(stringResource(R.string.add)) }
                                    }
                                } else ShelfContent(tab,items,recent,covers,busy,list,
                                    setList = { list = it; display.edit().putBoolean("shelf.list",it).apply() },
                                    openMenu = menu,more = more,seeAll = { navigate(ShelfTab.Discover) },
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
                                            if (selected == null && tab == ShelfTab.Library && query.isBlank()) {
                                                AudilegeWordmark(Modifier.padding(vertical = 8.dp).widthIn(max = 218.dp).fillMaxWidth())
                                            }
                                            Text(stringResource(when { tab == ShelfTab.Downloads -> R.string.shelf_empty_downloads
                                                selected == null -> R.string.shelf_empty
                                                else -> R.string.empty }))
                                            if (selected == null && tab != ShelfTab.Downloads) Button(onClick = { profileForm(null) }) {
                                                Text(stringResource(R.string.shelf_connect))
                                            }
                                            OutlinedButton(onClick = { startImport() }) { Text(stringResource(R.string.shelf_local_import)) }
                                        }
                                    },query = query,setQuery = { query = it })
                            }
                            if (detail != null) {
                                if (split) VerticalDivider()
                                Box(Modifier.weight(if (split) .52f else 1f)) { renderDetail(detail) }
                            }
                        }
                        MiniPlayer(covers) { opened = it }
                        if (!rail && (detail == null || split)) ShelfTabs(tab,!busy) { navigate(it) }
                    }
                    }
                }
            }
        }
    }
}
