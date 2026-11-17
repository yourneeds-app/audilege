package app.yourneeds.foliocue

private val explicitScheme = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)

internal fun profileServerAddress(input: String, localHttp: Boolean): String {
    val address = input.trim().trimEnd('/')
    if (address.isEmpty() || explicitScheme.containsMatchIn(address)) return address
    val scheme = if (localHttp) "http" else "https"
    return "$scheme://$address"
}

internal fun validProfileUsername(name: String): Boolean =
    name.isNotBlank() && name.none { it == ':' || it.isISOControl() }
