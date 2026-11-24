package app.yourneeds.foliocue

import java.net.URI

private val explicitScheme = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

internal fun profileServerAddress(input: String, localHttp: Boolean): String {
    val address = input.trim().trimEnd('/')
    if (address.isEmpty() || explicitScheme.containsMatchIn(address)) return address
    val scheme = if (localHttp) "http" else "https"
    return "$scheme://$address"
}

internal fun validProfileUsername(name: String): Boolean =
    name.isNotBlank() && name.none { it == ':' || it.isISOControl() }

internal fun offersLocalHttp(input: String): Boolean {
    val uri = runCatching { URI(profileServerAddress(input, true)) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    return uri.host?.let(::localHost) == true
}
