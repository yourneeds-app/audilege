package app.yourneeds.foliocue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ProfilesScreen(profiles: List<Profile>, selected: Profile?, busy: Boolean,
                            openMenu: () -> Unit, select: (Profile?) -> Unit, edit: (Profile?) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        FolioBar(stringResource(R.string.profile_and_server), openMenu)
        LazyColumn(Modifier.weight(1f).widthIn(max = AudilegeLayout.contentWidth).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = AudilegeLayout.pageInset, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(profiles.sortedByDescending { it.id == selected?.id }, key = { it.id }) { profile ->
                ProfileCard(profile, profile.id == selected?.id, busy,
                    select = { select(profile) }, edit = { edit(profile) })
            }
            item {
                FilledTonalButton(onClick = { edit(null) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(painterResource(R.drawable.profile_add), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add))
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape,
                    border = BorderStroke(1.dp, if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileSymbol(R.drawable.shelf_download)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.local), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.profile_local_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (selected == null) ActiveProfileBadge()
                        else OutlinedButton(onClick = { select(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.profile_use_local))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile, active: Boolean, busy: Boolean, select: () -> Unit, edit: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape,
        colors = CardDefaults.outlinedCardColors(containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .35f)
            else MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(if (active) 1.5.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileSymbol(R.drawable.shelf_profile)
                Column(Modifier.weight(1f)) {
                    Text(profile.label, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (profile.accountLogin) R.string.profile_bookorbit else R.string.profile_opds),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // The default label is the username: show it once, not on two adjacent lines.
            if (!profile.label.trim().equals(profile.user.trim(), ignoreCase = true)) {
                ProfileDetail(R.drawable.shelf_profile, stringResource(R.string.profile_username), profile.user)
            }
            ProfileDetail(R.drawable.profile_server, stringResource(R.string.profile_server_label),
                runCatching { profile.api.toString().removeSuffix("/api/v1") }.getOrDefault(profile.server))
            if (active) ActiveProfileBadge()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val editButton: @Composable (Modifier) -> Unit = { modifier ->
                    OutlinedButton(onClick = edit, enabled = !busy, modifier = modifier.heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.profile_edit), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_edit_short))
                    }
                }
                val useButton: @Composable (Modifier) -> Unit = { modifier ->
                    FilledTonalButton(onClick = select, enabled = !busy, modifier = modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.profile_use))
                    }
                }
                if (!active && maxWidth < 310.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    editButton(Modifier.fillMaxWidth())
                    useButton(Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    editButton(Modifier.weight(1f))
                    if (!active) useButton(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfileSymbol(icon: Int) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(painterResource(icon), null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ProfileDetail(icon: Int, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActiveProfileBadge() {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.profile_check), null, Modifier.size(16.dp))
            Text(stringResource(R.string.profile_active), style = MaterialTheme.typography.labelMedium)
        }
    }
}
