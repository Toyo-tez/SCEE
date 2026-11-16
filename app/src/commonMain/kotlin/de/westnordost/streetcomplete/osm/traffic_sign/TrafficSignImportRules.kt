package de.westnordost.streetcomplete.osm.traffic_sign

/** Why [osmValuePart] can not be used as the id of a sign, null if it can: the same rules as the
 *  import of the catalogues (osmValuePartProblem in buildSrc/src/main/java/TrafficSignCatalogueUpdate.kt,
 *  keep in sync), checked here once more so that a sign that the import would not have taken is never
 *  offered for selection.
 *
 *  [parseTrafficSignValue] splits values at "," and ";" outside of brackets and quotes, reads a
 *  leading "XX:" as country and named values like "none" as no sign. A sign id with any of these would
 *  be read as something else. Ids with "+" (e.g. "B1+M1" in Belgium) are combinations of a main and
 *  an additional sign written as one id, and whether that is the same as "B1,M1" is not defined.
 *  Allowed are a free text in one pair of quotation marks, or an id of letters, digits, spaces, ".",
 *  "-", "_" and "/" that starts with a letter or digit, optionally followed by one variable value in
 *  brackets at the end. */
fun osmValuePartProblem(osmValuePart: String): String? {
    val id = osmValuePart
    if (id.isBlank()) return "empty"
    if (id != id.trim()) return "space at the start or end"
    if (COUNTRY_PREFIX.containsMatchIn(id)) return "starts with a country prefix"
    if (id.lowercase() in NAMED_TRAFFIC_SIGN_VALUES || id.lowercase() in GENERAL_REDIRECTS) return "is a named value"
    if (id.startsWith('"')) {
        val inner = id.removePrefix("\"").removeSuffix("\"")
        return if (id.length > 2 && id.endsWith('"') && '"' !in inner && inner.isNotBlank()) null else "quotation marks"
    }
    val bracket = id.indexOf('[')
    val head = if (bracket < 0) id else id.substring(0, bracket)
    if (bracket >= 0) {
        if (!id.endsWith(']')) return "text after the brackets"
        val value = id.substring(bracket + 1, id.length - 1)
        if (value.isBlank() || value.any { it == '[' || it == ']' || it == '"' }) return "brackets"
    }
    if (head.isEmpty() || !head.first().isLetterOrDigit()) return "does not start with a letter or digit"
    if (head.any { !(it.isLetterOrDigit() || it in ALLOWED_ID_CHARS) }) return "unsupported character"
    return null
}

private val COUNTRY_PREFIX = Regex("^[A-Za-z]{2}:")
private val ALLOWED_ID_CHARS = setOf(' ', '.', '-', '_', '/')

/** Whether [sign] can be offered for selection: its id is allowed (see [osmValuePartProblem]) and
 *  the value written for it is read back as exactly this sign, with nothing missing or left over. A
 *  sign that is not selectable is still known when existing values are read. */
fun TrafficSignCatalogue.isSelectable(sign: TrafficSign): Boolean {
    if (osmValuePartProblem(sign.osmValuePart) != null) return false
    val written = try {
        TrafficSignPart.of(sign)
    } catch (e: IllegalArgumentException) {
        return false
    }
    val value = parseTrafficSignValue("$country:${written.text}", this)
    val part = value.parts.singleOrNull() ?: return false
    return !value.isUnparseable && value.country == country &&
        part.sign == sign && part.text == written.text && part.kind == sign.kind &&
        !part.isMissingValue && !part.hasUnexpectedValue
}
