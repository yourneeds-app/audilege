package app.yourneeds.foliocue

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal data class MenuItem(val label: Int, val selected: Boolean = false, val icon: Int? = null,
                             val supporting: String? = null, val action: () -> Unit)

@Composable
internal fun FolioDrawer(
    title: String, destinations: List<MenuItem>, brand: Boolean = false,
    bottomItem: MenuItem? = null,
    content: @Composable (() -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet {
            Column(Modifier.fillMaxHeight().padding(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (brand) {
                        AudilegeWordmark(Modifier.width(215.dp))
                    } else {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                destinations.forEach { item ->
                    NavigationDrawerItem(
                        label = {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(stringResource(item.label))
                                item.supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        },
                        icon = item.icon?.let { drawable -> { Icon(painterResource(drawable), null) } },
                        selected = item.selected,
                        onClick = {
                            scope.launch {
                                drawer.close()
                                item.action()
                            }
                        },
                    )
                }
            }
            bottomItem?.let { item ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(label = { Text(stringResource(item.label)) }, selected = item.selected,
                    icon = item.icon?.let { drawable -> { Icon(painterResource(drawable), null) } },
                    onClick = { scope.launch { drawer.close(); item.action() } })
            }
            }
        }
    }) {
        content { scope.launch { drawer.open() } }
    }
}

@Composable
internal fun AudilegeWordmark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    Image(
        painter = painterResource(R.drawable.audilege_wordmark),
        contentDescription = stringResource(R.string.audilege_brand),
        modifier = modifier.aspectRatio(1672f / 472f),
        colorFilter = if (dark) ColorFilter.tint(Color(0xFFE4E1DF)) else null,
    )
}

@Composable
internal fun SystemBarAppearance(activity: MainActivity) {
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > .45f
    DisposableEffect(activity, lightSurface) {
        val window = activity.window
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightSurface
        controller.isAppearanceLightNavigationBars = lightSurface
        onDispose {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioBar(
    title: String, openMenu: (() -> Unit)? = null, back: (() -> Unit)? = null,
    overflow: List<MenuItem> = emptyList(), actions: @Composable RowScope.() -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (back != null) IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back))
            } else if (openMenu != null) IconButton(onClick = openMenu) {
                Icon(Icons.Default.Menu, stringResource(R.string.open_menu))
            }
        },
        actions = {
            actions()
            if (overflow.isNotEmpty()) Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.open_menu))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.appVolumeKeys()) {
                    AppVolumeFeedback()
                    overflow.forEach { item ->
                        DropdownMenuItem(text = { Text(stringResource(item.label)) }, onClick = {
                            expanded = false
                            item.action()
                        })
                    }
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
