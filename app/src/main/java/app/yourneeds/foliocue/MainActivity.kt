package app.yourneeds.foliocue

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val language = newBase.getSharedPreferences("display", MODE_PRIVATE).getString("language", "").orEmpty()
        val config = Configuration(newBase.resources.configuration)
        if (language.isNotEmpty()) config.setLocale(Locale.forLanguageTag(language))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FolioCue(this)
        }
    }
}

internal fun keyOf(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal data class OpenBook(
    val file: File, val publication: Publication, val mode: Mode? = null, val online: OnlineBook? = null,
    val serverProgress: org.json.JSONObject? = null,
)
internal data class BookDetail(val entry: CatalogEntry, val onlineBook: OnlineBook?, val localFile: File, val publication: Publication?)
private data class CatalogScreen(val title: String, val url: URI, val page: CatalogPage)

@Composable
private fun FolioCue(activity: MainActivity) {
    val context = LocalContext.current
    val store = remember { Profiles(context) }
    val scope = rememberCoroutineScope()
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val updates = remember { AppUpdates(store, installed, scope) }
    LaunchedEffect(updates) { updates.check(automatic = true) }
    val display = remember { context.getSharedPreferences("display", Context.MODE_PRIVATE) }
    val readerPrefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    var paper by remember { mutableStateOf(readerPrefs.paper()) }
    var accent by remember { mutableStateOf(Accent.entries.getOrElse(readerPrefs.getInt("accent", 0)) { Accent.Teal }) }
    var settings by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<BookDetail?>(null) }
    var storageRevision by remember { mutableIntStateOf(0) }
    val covers = remember { CoverStore() }
    var profiles by remember { mutableStateOf(store.list()) }
    var selected by remember { mutableStateOf(profiles.firstOrNull { it.id == display.getString("profile", null) }) }
    var chooser by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var history by remember { mutableStateOf(listOf<CatalogScreen>()) }
    var opened by remember { mutableStateOf<OpenBook?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var localOnly by remember { mutableStateOf(false) }
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

    fun perform(failure: Int, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try { task() } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                problem = when (exception) {
                    is CatalogAccessException -> exception.messageId
                    else -> failure
                }
            } finally { busy = false }
        }
    }

    fun from(profile: Profile): LibraryCatalog = if (profile.accountLogin) {
        OrbitCatalog(profile, store.credential(profile)) { store.renew(profile, it) }
    } else Catalog(profile, store.credential(profile))
    val catalog = remember(selected) { selected?.let { runCatching { from(it) }.getOrNull() } }

    fun showDetails(entry: CatalogEntry) {
        perform(R.string.stream_error) {
            details = withContext(Dispatchers.IO) {
                val orbit = catalog as? OrbitCatalog
                val online = orbit?.let { OnlineBook(it, it.details(entry.url)) }
                val file = File(folder, keyOf(online?.let { "${entry.url}#${it.detail.fileId}" } ?: entry.url.toString()) + ".epub")
                BookDetail(entry, online, file, online?.let { it.catalog.publication(it.detail, entry.title) }
                    ?: file.takeIf(File::isFile)?.let(::Publication))
            }
        }
    }

    fun select(profile: Profile?) {
        selected = profile
        display.edit().putString("profile", profile?.id).apply()
        history = emptyList()
        problem = null
        chooser = false
        localOnly = false
    }

    LaunchedEffect(selected, reload) {
        val profile = selected ?: return@LaunchedEffect
        if (history.isNotEmpty()) return@LaunchedEffect
        busy = true
        try {
            val page = withContext(Dispatchers.IO) { (catalog ?: from(profile)).page(profile.catalog) }
            history = listOf(CatalogScreen(profile.label, profile.catalog, page))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            problem = (exception as? CatalogAccessException)?.messageId ?: R.string.catalog_error
        } finally { busy = false }
    }

    val importBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        perform(R.string.book_error) {
            val book = withContext(Dispatchers.IO) {
                val file = File(folder, keyOf(uri.toString()) + ".epub")
                val stream = context.contentResolver.openInputStream(uri) ?: error("EPUB")
                stream.use { savePublication(it, file) }
                OpenBook(file, Publication(file))
            }
            opened = book
        }
    }

    BackHandler(enabled = settings || (!form && !busy && (opened != null || details != null || chooser || history.size > 1))) {
        when {
            opened != null -> opened = null
            settings -> settings = false
            details != null -> { details = null; problem = null }
            chooser -> chooser = false
            history.size > 1 -> history = history.dropLast(1)
        }
    }

    MaterialTheme(colorScheme = appearanceColors(paper, accent)) {
    val book = opened
    if (book != null) {
        LaunchedEffect(book.file) {
            if (book.online == null && details?.onlineBook == null) display.edit()
                .putString("last.${folder.name}", book.file.name).remove("last.online.${folder.name}").apply()
            details?.takeIf { it.onlineBook != null }?.let {
                display.edit().putString("last.online.${folder.name}", it.entry.url.toString())
                    .putString("last.title.${folder.name}", it.entry.title).apply()
            }
        }
        BookReader(activity, book.file, book.publication, book.mode, accent, book.online, book.serverProgress, scope) {
            opened = null
            paper = readerPrefs.paper()
        }
        return@MaterialTheme
    }

    if (settings) {
        SettingsScreen(readerPrefs, updates, paper, accent,
            { paper = it; readerPrefs.edit().putInt("paper", it.ordinal).apply() },
            { accent = it; readerPrefs.edit().putInt("accent", it.ordinal).apply() },
        ) { settings = false }
        return@MaterialTheme
    }

    details?.let { detail ->
        DetailScreen(detail, covers, busy, problem, storageRevision,
            close = { if (!busy) { details = null; problem = null } },
            open = { mode, offline ->
                perform(R.string.book_error) {
                    opened = withContext(Dispatchers.IO) {
                        val publication = if (offline) Publication(detail.localFile)
                            else detail.publication ?: error("Publication")
                        val online = detail.onlineBook
                        OpenBook(detail.localFile, publication, mode, online,
                            if (offline) null else runCatching { online?.progress() }.getOrNull())
                    }
                }
            },
            download = {
                perform(R.string.book_error) {
                    val publication = withContext(Dispatchers.IO) {
                        val online = detail.onlineBook
                        if (online != null) online.catalog.download(online.detail, detail.localFile)
                        else (catalog ?: error("Catalog")).download(detail.entry.url, detail.localFile)
                        if (online == null) Publication(detail.localFile) else detail.publication
                    }
                    details = detail.copy(publication = publication)
                    storageRevision++
                }
            },
            remove = {
                perform(R.string.load_error) {
                    withContext(Dispatchers.IO) { check(detail.localFile.delete() || !detail.localFile.exists()) }
                    if (detail.onlineBook == null) {
                        if (detail.entry.url.scheme == "file") details = null
                        else details = detail.copy(publication = null)
                    }
                    storageRevision++
                }
            },
        )
        return@MaterialTheme
    }

    if (form) {
        ProfileForm(editing, busy, problem, dismiss = {
            form = false
            problem = null
            chooser = true
        }) { profile, password ->
            perform(R.string.setup_error) {
                val page = withContext(Dispatchers.IO) {
                    when {
                        password.isEmpty() -> from(profile).page(profile.catalog).also {
                            store.save(profile, store.credential(profile))
                        }
                        profile.accountLogin -> {
                            val catalog = OrbitCatalog.signIn(profile, password)
                            catalog.page(profile.catalog).also { store.save(profile, catalog.credential()) }
                        }
                        else -> Catalog(profile, password).page(profile.catalog).also { store.save(profile, password) }
                    }
                }
                profiles = store.list()
                select(profile)
                history = listOf(CatalogScreen(profile.label, profile.catalog, page))
                form = false
            }
        }
        return@MaterialTheme
    }

    FolioDrawer(stringResource(R.string.app), listOf(
        MenuItem(R.string.shelf, !localOnly) { localOnly = false },
        MenuItem(R.string.downloads, localOnly) { localOnly = true },
        MenuItem(R.string.import_epub) {
            if (!busy) importBook.launch(arrayOf("application/epub+zip", "application/octet-stream"))
        },
        MenuItem(R.string.profiles) { if (!busy) chooser = true },
        MenuItem(R.string.app_settings) { settings = true },
    ), brand = true) { menu ->
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.safeDrawingPadding().widthIn(max = 1120.dp).padding(horizontal = 18.dp).fillMaxWidth()) {
            FolioBar(stringResource(if (localOnly) R.string.downloads else R.string.shelf), menu) {
                IconButton(onClick = { settings = true }) {
                    Icon(Icons.Default.Settings, stringResource(R.string.app_settings))
                }
                if (selected == null && !localOnly) {
                    TextButton(enabled = !busy, onClick = { editing = null; problem = null; form = true }) {
                        Text(stringResource(R.string.add))
                    }
                }
            }
            updates.available?.let { release ->
                FilledTonalButton(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.release_available, release.version))
                }
            }
            val current = history.lastOrNull().takeUnless { localOnly }
            if (!localOnly && history.size > 1) TextButton(enabled = !busy, onClick = { history = history.dropLast(1) }) {
                Text(stringResource(R.string.back))
            }
            Text(current?.title ?: selected?.label ?: stringResource(R.string.local),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.connecting), modifier = Modifier.padding(top = 8.dp))
            }
            if (!localOnly) problem?.let { message ->
                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                if (selected != null && !busy) {
                    TextButton(onClick = {
                        if (message == R.string.session_expired) {
                            editing = selected
                            problem = null
                            form = true
                        } else {
                            history = emptyList()
                            problem = null
                            reload++
                        }
                    }) { Text(stringResource(if (message == R.string.session_expired) R.string.use_account else R.string.retry)) }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(155.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                val lastOnline = display.getString("last.online.${folder.name}", null)
                if (lastOnline != null && !localOnly && catalog is OrbitCatalog) fullWidth {
                    val title = display.getString("last.title.${folder.name}", "").orEmpty()
                    Button(enabled = !busy, onClick = { showDetails(CatalogEntry(title, URI(lastOnline), true)) }) {
                        Text(stringResource(R.string.resume_book, title))
                    }
                }
                val last = library.firstOrNull { it.file.name == display.getString("last.${folder.name}", null) }
                if (last != null && lastOnline == null && !localOnly) fullWidth {
                    Button(enabled = !busy, onClick = { opened = last }) {
                        Text(stringResource(R.string.resume_book, last.publication.title))
                    }
                }
                items(current?.page?.entries.orEmpty()) { entry ->
                    LibraryTile(entry.title, entry.author, !busy, covers, "${selected?.id}:${entry.url}", load = {
                        if (entry.book) (catalog as? OrbitCatalog)?.cover(entry.url.path.substringAfterLast('/').toLong())
                        else null
                    }) {
                        if (entry.book) showDetails(entry)
                        else perform(R.string.catalog_error) {
                            val page = withContext(Dispatchers.IO) { (catalog ?: error("Catalog")).page(entry.url) }
                            history = history + CatalogScreen(entry.title, entry.url, page)
                        }
                    }
                }
                current?.page?.next?.let { next ->
                    fullWidth {
                        TextButton(enabled = !busy, onClick = {
                            val profile = selected ?: return@TextButton
                            perform(R.string.catalog_error) {
                                val page = withContext(Dispatchers.IO) { (catalog ?: from(profile)).page(next) }
                                history = history.dropLast(1) + current.copy(
                                    page = CatalogPage((current.page.entries + page.entries).distinct(), page.next?.takeUnless { it == next }))
                            }
                        }) { Text(stringResource(R.string.more)) }
                    }
                }
                if (library.isNotEmpty() && selected != null && !localOnly) {
                    fullWidth { Text(stringResource(R.string.local), style = MaterialTheme.typography.titleMedium) }
                }
                items(library, key = { it.file.name }) { item ->
                    val publication = item.publication
                    LibraryTile(publication.title, publication.author, !busy, covers, item.file.absolutePath, {
                        publication.cover?.let(publication::open)
                    }) {
                        details = BookDetail(CatalogEntry(publication.title, item.file.toURI(), true, publication.author),
                            null, item.file, publication)
                    }
                }
                if (!busy && (problem == null || localOnly) && library.isEmpty() && current?.page?.entries.isNullOrEmpty()
                    && current?.page?.next == null) {
                    fullWidth { Text(stringResource(R.string.empty)) }
                }
            }
        }
        }
    }
    }

    if (chooser) AlertDialog(
        onDismissRequest = { chooser = false },
        title = { Text(stringResource(R.string.profiles)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { select(null) }) { Text(stringResource(R.string.local)) }
                profiles.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { select(profile) }, modifier = Modifier.weight(1f)) {
                            Column {
                                Text(profile.label)
                                Text(profile.user, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { editing = profile; form = true; chooser = false }) {
                            Text(stringResource(R.string.edit))
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.language), modifier = Modifier.padding(top = 10.dp))
                Row {
                    listOf("" to R.string.system, "de" to R.string.german, "en" to R.string.english)
                        .forEach { (key, title) ->
                            TextButton(onClick = { display.edit().putString("language", key).apply(); activity.recreate() }) {
                                Text(stringResource(title))
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { editing = null; problem = null; form = true; chooser = false }) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = { chooser = false }) { Text(stringResource(R.string.close)) } },
    )
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}
