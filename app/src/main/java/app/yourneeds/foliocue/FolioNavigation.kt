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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal data class MenuItem(val label: Int, val selected: Boolean = false, val action: () -> Unit)

@Composable
internal fun FolioDrawer(
    title: String, destinations: List<MenuItem>, brand: Boolean = false,
    content: @Composable (() -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (brand) Image(painterResource(R.drawable.audilege_mark), null, Modifier.size(56.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                destinations.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.label)) },
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
        }
    }) {
        content { scope.launch { drawer.open() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioBar(
    title: String, openMenu: () -> Unit, back: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (back == null) IconButton(onClick = openMenu) {
                Icon(Icons.Default.Menu, stringResource(R.string.open_menu))
            } else IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back))
            }
        },
        actions = {
            actions()
            if (back != null) IconButton(onClick = openMenu) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.open_menu))
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
