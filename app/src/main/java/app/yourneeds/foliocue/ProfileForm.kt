package app.yourneeds.foliocue

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ProfileForm(
    original: Profile?,
    busy: Boolean,
    problem: Int?,
    dismiss: () -> Unit,
    save: (Profile, String) -> Unit,
) {
    var server by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(original?.server.orEmpty())) }
    var user by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(original?.user.orEmpty())) }
    var label by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(original?.label.orEmpty())) }
    var password by remember { mutableStateOf(TextFieldValue()) }
    var showPassword by remember { mutableStateOf(false) }
    val passwordMask = remember { PasswordVisualTransformation() }
    var local by rememberSaveable { mutableStateOf(original?.localHttp == true) }
    var accountLogin by rememberSaveable { mutableStateOf(original?.accountLogin ?: true) }
    var validated by rememberSaveable { mutableStateOf(false) }
    val username = user.text.trim()
    val showLocalOption = offersLocalHttp(server.text)
    val allowLocalHttp = local && showLocalOption
    val address = profileServerAddress(server.text, allowLocalHttp)
    val draft = remember(address, username, label.text, allowLocalHttp, accountLogin) {
        Profile(label = label.text.trim().ifEmpty { username }, server = address, user = username,
            localHttp = allowLocalHttp, accountLogin = accountLogin)
    }
    val catalog = remember(draft) { runCatching { draft.catalog }.getOrNull() }
    val sameAccount = original?.takeIf {
        it.user == username && runCatching { it.catalog }.getOrNull() == catalog && catalog != null
    }
    val serverError = validated && catalog == null
    val userError = validated && !validProfileUsername(username)
    val passwordError = validated && password.text.isEmpty() && sameAccount == null

    val serverFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val labelFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        if (busy) return
        validated = true
        when {
            catalog == null -> serverFocus.requestFocus()
            !validProfileUsername(username) -> userFocus.requestFocus()
            password.text.isEmpty() && sameAccount == null -> passwordFocus.requestFocus()
            else -> {
                keyboard?.hide()
                focus.clearFocus()
                showPassword = false
                save(sameAccount?.let { draft.copy(id = it.id) } ?: draft, password.text)
            }
        }
    }

    BackHandler(enabled = !busy, onBack = dismiss)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.back)) }
                Text(stringResource(if (original == null) R.string.add else R.string.edit),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(if (accountLogin) R.string.account_hint else R.string.opds_hint),
                    style = MaterialTheme.typography.bodyMedium)

                ProfileField(
                    server, { server = it }, R.string.server, serverFocus, busy, KeyboardType.Uri,
                    error = serverError, help = if (serverError) R.string.server_input_error else R.string.server_example,
                    next = { userFocus.requestFocus() },
                )
                if (showLocalOption) Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = local, enabled = !busy, role = Role.Checkbox, onValueChange = { local = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(local, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.http), Modifier.padding(start = 12.dp))
                }
                ProfileField(
                    user, { user = it }, if (accountLogin) R.string.account_username else R.string.username,
                    userFocus, busy, KeyboardType.Email,
                    modifier = Modifier.semantics { contentType = ContentType.Username },
                    error = userError, help = (if (accountLogin) R.string.account_username_error else R.string.username_input_error).takeIf { userError },
                    next = { passwordFocus.requestFocus() },
                )
                ProfileField(
                    password, { password = it }, if (accountLogin) R.string.account_password else R.string.password,
                    passwordFocus, busy, KeyboardType.Password,
                    modifier = Modifier.semantics { contentType = ContentType.Password },
                    error = passwordError,
                    help = when {
                        passwordError -> if (accountLogin) R.string.account_password_error else R.string.password_input_error
                        sameAccount != null -> if (accountLogin) R.string.account_session_hint else R.string.password_saved_hint
                        else -> null
                    },
                    transformation = if (showPassword) VisualTransformation.None else passwordMask,
                    trailing = {
                        val action = stringResource(if (showPassword) R.string.hide_password else R.string.show_password)
                        TextButton(enabled = !busy, onClick = { showPassword = !showPassword },
                            modifier = Modifier.semantics { contentDescription = action }) {
                            Text(stringResource(if (showPassword) R.string.hide_password_short else R.string.show_password_short))
                        }
                    },
                    next = { labelFocus.requestFocus() },
                )
                ProfileField(label, { label = it }, R.string.optional_profile_label, labelFocus, busy,
                    KeyboardType.Text, done = true, next = ::submit)

                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.checking_connection),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                problem?.let {
                    Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error)
                }
                Button(enabled = !busy, onClick = ::submit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.signin))
                }
                TextButton(enabled = !busy, onClick = {
                    accountLogin = !accountLogin
                    showPassword = false
                    validated = false
                }) {
                    Text(stringResource(if (accountLogin) R.string.use_opds else R.string.use_account))
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    value: TextFieldValue,
    update: (TextFieldValue) -> Unit,
    label: Int,
    focus: FocusRequester,
    busy: Boolean,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    help: Int? = null,
    transformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    done: Boolean = false,
    next: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = update,
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        label = { Text(stringResource(label)) },
        supportingText = help?.let { { Text(stringResource(it)) } },
        trailingIcon = trailing,
        isError = error,
        readOnly = busy,
        singleLine = true,
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false,
            imeAction = if (done) ImeAction.Done else ImeAction.Next),
        keyboardActions = if (done) KeyboardActions(onDone = { next() }) else KeyboardActions(onNext = { next() }),
    )
}
