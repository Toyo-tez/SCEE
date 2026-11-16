import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/* The update process of the traffic sign catalogues, without Gradle types so that it can be tested
 * (buildSrc/src/test). UpdateTrafficSignsTask is only the wiring.
 *
 * Steps: fetch + verify the npm tarball -> read it -> find all country catalogues in it -> plan
 * (stage + validate + diff per country, nothing written) -> only if asked and the plan has no
 * problems (and, for changes that need a look, the review was accepted): apply, with a backup that is
 * restored if anything fails. A failed fetch, a broken catalogue or a failed write leaves the existing
 * catalogues as they were.
 *
 * Every country of the package is imported, there is no list of countries anywhere: a new country in
 * a new version of the package is found and imported by the next update. Of each catalogue, only the
 * signs are imported whose id can be written into a traffic_sign value without ambiguity and whose
 * image exists and can be converted. The others are listed with the reason in the index
 * (TRAFFIC_SIGNS_INDEX), which the app reads to know the available countries and which signs to
 * leave out. The catalogue JSON itself is copied unchanged. */

const val TRAFFIC_SIGNS_PACKAGE = "@osm-traffic-signs/converter"
const val TRAFFIC_SIGNS_REGISTRY = "https://registry.npmjs.org/$TRAFFIC_SIGNS_PACKAGE"
/** name of the index of the imported catalogues in the target directory, read by the app
 *  (TrafficSignCatalogues). Keep in sync. */
const val TRAFFIC_SIGNS_INDEX = "catalogues.json"

class TrafficSignUpdateConfig(
    /** a version like "0.8.0", or "latest" */
    val version: String,
    /** SHA-256 of the npm tarball. Required to apply. If null, the tarball is verified against the
     *  integrity (sha512) of the registry and the SHA-256 to pin is printed */
    val sha256: String?,
    val targetDir: File,
    val backupDir: File,
    val reportDir: File,
    /** where the history of applied updates is written (one file per update) */
    val historyDir: File?,
    /** country -> osm value parts of the signs the curated rules for tags (WAY_RULES) are made of */
    val ruleSigns: Map<String, Set<String>>,
    /** write the update. false: only plan and report */
    val apply: Boolean,
    /** accept changes that need a manual look (see [CatalogueDiff.reviewReasons]) */
    val acceptReview: Boolean,
)

class TrafficSignUpdateResult(
    val version: String,
    val sha256: String,
    val plan: CatalogueUpdatePlan,
    val applied: Boolean,
    val reportFile: File,
)

class TrafficSignUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** [fetch]: bytes of the URL, throws if not possible. [convertSvg]: svg file name (without
 *  extension) and its bytes -> vector drawable xml bytes, or null and the error */
fun updateTrafficSignCatalogue(
    config: TrafficSignUpdateConfig,
    fetch: (url: String) -> ByteArray,
    convertSvg: (name: String, svg: ByteArray) -> Pair<ByteArray?, String>,
    log: (String) -> Unit = ::println,
): TrafficSignUpdateResult {
    // 1. resolve the version and fetch: nothing is touched if this fails
    val metadata = try {
        parseJson(fetch("$TRAFFIC_SIGNS_REGISTRY/${config.version}").decodeToString())
    } catch (e: Exception) {
        throw TrafficSignUpdateException("Could not read the registry data of $TRAFFIC_SIGNS_PACKAGE@${config.version}: ${e.message}", e)
    }
    val version = (metadata["version"] as? JsonPrimitive)?.content
        ?: throw TrafficSignUpdateException("The registry data has no version")
    val integrity = ((metadata["dist"] as? JsonObject)?.get("integrity") as? JsonPrimitive)?.content
    val url = "$TRAFFIC_SIGNS_REGISTRY/-/converter-$version.tgz"
    val tarball = try {
        fetch(url)
    } catch (e: Exception) {
        throw TrafficSignUpdateException("Download of $url failed, the existing catalogue is unchanged: ${e.message}", e)
    }
    val actualSha256 = sha256Hex(tarball)
    if (config.sha256 != null) {
        if (actualSha256 != config.sha256) throw TrafficSignUpdateException("SHA-256 of $url is $actualSha256 but expected ${config.sha256}")
    } else {
        // no pinned hash (a new version): the registry's own integrity must match
        val expected = integrity?.takeIf { it.startsWith("sha512-") }?.removePrefix("sha512-")
            ?: throw TrafficSignUpdateException("No pinned SHA-256 and no sha512 integrity in the registry data")
        val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball))
        if (actual != expected) throw TrafficSignUpdateException("The tarball does not match the integrity of the registry")
        log("Pin this version with -PtrafficSignsSha256=$actualSha256")
    }
    val packageFiles = try {
        readTarGz(tarball)
    } catch (e: Exception) {
        throw TrafficSignUpdateException("The tarball can not be read: ${e.message}", e)
    }

    // 2. plan: stage, validate and compare. Nothing is written.
    val existing = readCatalogueFiles(config.targetDir)
    val plan = planCatalogueUpdate(config, version, actualSha256, packageFiles, existing, convertSvg)
    config.reportDir.mkdirs()
    val reportFile = File(config.reportDir, "traffic-signs-update-$version.md")
    reportFile.writeText(plan.report(version, actualSha256, config))
    log(plan.summary(version))
    log("Report: $reportFile")

    if (plan.problems.isNotEmpty()) {
        throw TrafficSignUpdateException("The catalogue is not valid, nothing was changed:\n" + plan.problems.joinToString("\n") { "  $it" })
    }
    if (!config.apply) {
        log("Dry run: nothing was changed. Use -PtrafficSignsApply=true (and -PtrafficSignsSha256=$actualSha256) to apply.")
        return TrafficSignUpdateResult(version, actualSha256, plan, false, reportFile)
    }
    if (config.sha256 == null) {
        throw TrafficSignUpdateException("To apply, give the SHA-256 that was printed: -PtrafficSignsSha256=$actualSha256")
    }
    if (plan.reviewReasons.isNotEmpty() && !config.acceptReview) {
        throw TrafficSignUpdateException(
            "These changes need a manual review, nothing was changed (accept with -PtrafficSignsAcceptReview=true after reading the report):\n" +
                plan.reviewReasons.joinToString("\n") { "  $it" }
        )
    }
    if (plan.identical) {
        log("The catalogues are already $version, nothing to do.")
        return TrafficSignUpdateResult(version, actualSha256, plan, false, reportFile)
    }

    // 3. apply with backup
    applyCatalogueUpdate(config.targetDir, plan, config.backupDir)
    config.historyDir?.let {
        it.mkdirs()
        File(it, "${plan.oldVersion ?: "none"}-to-$version.md").writeText(plan.report(version, actualSha256, config))
    }
    log("Applied. Backup of the previous files: ${config.backupDir}. Now run the checks listed in the report.")
    return TrafficSignUpdateResult(version, actualSha256, plan, true, reportFile)
}

/* ------------------------------------------ countries and signs ------------------------------------------ */

private val CATALOGUE_PATH_REGEX = Regex("^package/dist/data/([A-Z]{2})\\.json$")

/** The countries of which the package contains a catalogue (dist/data/{CC}.json), sorted */
fun detectCountries(packageFiles: Map<String, ByteArray>): List<String> =
    packageFiles.keys.mapNotNull { CATALOGUE_PATH_REGEX.matchEntire(it)?.groupValues?.get(1) }.sorted()

/** Why a sign of a catalogue is not imported */
enum class ExclusionReason(val id: String, val isError: Boolean) {
    /** the entry lacks required fields, has an unknown kind or repeats an osm value part */
    INVALID_ENTRY("invalid_entry", true),
    /** the osm value part can not be written into a traffic_sign value without ambiguity, see
     *  [osmValuePartProblem] */
    UNSUPPORTED_ID("unsupported_id", false),
    /** several signs would have the same image */
    IMAGE_NAME_COLLISION("image_name_collision", true),
    /** there is no SVG in the package */
    NO_IMAGE("no_image", false),
    /** the image states no licence */
    NO_IMAGE_LICENCE("no_image_licence", false),
    /** the SVG can not be converted */
    DEFECTIVE_IMAGE("defective_image", true),
}

/** Why [osmValuePart] can not be imported as id of a sign, null if it can.
 *
 *  The parser of the app (parseTrafficSignValue) splits values at "," and ";" outside of brackets and
 *  quotes, takes a leading "XX:" as country and named values like "none" as no sign. A sign id that
 *  contains any of these would be read as something else, so it is not imported. Ids with "+" (e.g.
 *  "B1+M1" in Belgium) are combinations of a main and an additional sign written as one id: whether
 *  that is the same as "B1,M1" is not defined, so they are not imported either. Allowed are:
 *  - a free text in one pair of quotation marks, e.g. "\"Kfz-Verkehr frei\""
 *  - an id of letters, digits, spaces, ".", "-", "_" and "/", starting with a letter or digit,
 *    optionally followed by one variable value in brackets at the end, e.g. "274[30]",
 *    "1042-31[Mo-Sa 18:00-19:00]"
 *  Keep in sync with osmValuePartProblem in the app (TrafficSignImportRules.kt). */
fun osmValuePartProblem(osmValuePart: String): String? {
    val id = osmValuePart
    if (id.isBlank()) return "empty"
    if (id != id.trim()) return "space at the start or end"
    if (COUNTRY_PREFIX_REGEX.containsMatchIn(id)) return "starts with a country prefix"
    if (id.lowercase() in NAMED_VALUES || id.lowercase() == "no") return "is a named value"
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
    val unsupported = head.filter { !(it.isLetterOrDigit() || it in ALLOWED_ID_CHARS) }.toSet()
    if (unsupported.isNotEmpty()) return "contains ${unsupported.sorted().joinToString(" ") { "\"$it\"" }}"
    return null
}

private val COUNTRY_PREFIX_REGEX = Regex("^[A-Za-z]{2}:")
private val ALLOWED_ID_CHARS = setOf(' ', '.', '-', '_', '/')

/** Name of the image of a sign, same as trafficSignImageName in the app (TrafficSignImages.kt) and
 *  createSvgImportname of the converter. Keep in sync. */
fun imageName(country: String, osmValuePart: String) =
    "${country}_" + osmValuePart.replace("[", "__").replace("]", "__").replace(Regex("[^a-zA-Z0-9_]"), "_")

private val validKinds = setOf("traffic_sign", "exception_modifier", "condition_modifier")
private val NAMED_VALUES = setOf(
    "city_limit", "maxspeed", "none", "destination", "yes", "variable", "hazard", "signals",
    "give_way", "stop", "variable_message",
)

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The problems of the catalogue as a whole: if there are any, it can not be imported at all. The
 *  problems of single signs are not included, see [entryProblem] */
internal fun catalogueProblems(root: JsonObject?, country: String, version: String): List<String> {
    if (root == null) return listOf("not a JSON object")
    val problems = ArrayList<String>()
    if (root.str("country") != country) problems.add("country is not $country")
    val fileVersion = root.str("version")
    if (fileVersion.isNullOrBlank()) problems.add("version is missing")
    else if (fileVersion != version) problems.add("$country.json is of version $fileVersion, not $version")
    val signs = root["signs"] as? JsonArray ?: return problems + "list of signs is missing"

    // redirects that lead to nothing or contradict each other change how values are read
    val ids = signs.mapNotNull { (it as? JsonObject)?.str("osmValuePart") }.toSet()
    val signIds = signs.mapNotNull { (it as? JsonObject)?.str("signId") }.toSet()
    val targets = HashMap<String, String>()
    for (element in signs) {
        val sign = element as? JsonObject ?: continue
        val id = sign.str("osmValuePart") ?: continue
        (sign["redirects"] as? JsonArray)?.forEach { r ->
            val from = (r as? JsonObject)?.str("from")
            val to = (r as? JsonObject)?.str("to")
            if (from == null || to == null) {
                problems.add("$id: redirect without from/to")
                return@forEach
            }
            if (to !in ids && to !in signIds && to !in NAMED_VALUES && to.substringBefore('[') !in signIds) {
                problems.add("$id: redirect \"$from\" leads to \"$to\", which is not in the catalogue")
            }
            val previous = targets.put(from.lowercase(), to)
            if (previous != null && previous != to) problems.add("$id: redirect \"$from\" leads to \"$previous\" and \"$to\"")
        }
    }
    return problems
}

/** Why a single sign can not be read, null if it can */
private fun entryProblem(sign: JsonObject): String? {
    val problems = ArrayList<String>()
    if (sign.str("osmValuePart").isNullOrBlank()) problems.add("osmValuePart is missing or empty")
    if (sign.str("signId").isNullOrBlank()) problems.add("signId is missing or empty")
    if (sign.str("name") == null) problems.add("name is missing")
    val kind = sign.str("kind")
    if (kind !in validKinds) problems.add("unknown kind $kind")
    if ((sign["catalogue"] as? JsonObject)?.str("signCategory") == null) problems.add("catalogue.signCategory is missing")
    if (sign["tagRecommendationsByGeometry"] == null) problems.add("tagRecommendationsByGeometry is missing")
    return problems.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** the licence of the image of the sign as stated in the catalogue, null if none */
private fun imageLicence(sign: JsonObject): String? =
    (sign["image"] as? JsonObject)?.str("licence")?.takeIf { it.isNotBlank() }

/* ------------------------------------------------ plan ------------------------------------------------ */

/** A sign that is not imported, with the reason and details */
data class ExcludedSign(val osmValuePart: String, val reason: ExclusionReason, val detail: String)

/** What happens with the catalogue of one country */
class CountryImport(
    val country: String,
    /** whether the catalogue is imported (false: it can not be used at all, see [problems]) */
    val isImported: Boolean,
    /** number of entries in the catalogue */
    val catalogueSigns: Int,
    /** osm value parts of the imported signs, in catalogue order */
    val imported: List<String>,
    val excluded: List<ExcludedSign>,
    /** licences of the images of the imported signs */
    val imageLicences: Set<String>,
    /** warnings of the conversion of images that were imported nevertheless */
    val warnings: List<String>,
    /** problems of the catalogue as a whole, see [catalogueProblems] */
    val problems: List<String>,
    val diff: CatalogueDiff,
) {
    val skippedCount get() = excluded.count { !it.reason.isError }
    val errorCount get() = excluded.count { it.reason.isError }
}

class CatalogueUpdatePlan(
    /** relative path (DE.json, DE/<name>.xml, VERSION, catalogues.json) -> content of everything the
     *  catalogues consist of */
    val newFiles: Map<String, ByteArray>,
    /** same for the existing catalogues */
    val oldFiles: Map<String, ByteArray>,
    val oldVersion: String?,
    val countries: List<CountryImport>,
    /** countries that are there now but not in the package anymore */
    val removedCountries: List<String>,
    /** problems that prevent the update */
    val problems: List<String>,
    /** country -> signs of the curated rules that are not imported */
    val ruleSignsNotImported: Map<String, List<String>> = emptyMap(),
) {
    val addedFiles get() = newFiles.keys.filter { it !in oldFiles }
    val removedFiles get() = oldFiles.keys.filter { it !in newFiles }
    val changedFiles get() = newFiles.keys.filter { it in oldFiles && !oldFiles.getValue(it).contentEquals(newFiles.getValue(it)) }
    val identical get() = addedFiles.isEmpty() && removedFiles.isEmpty() && changedFiles.isEmpty()

    fun country(country: String): CountryImport? = countries.find { it.country == country }

    /** the diff of the one country there is (for single-country tests and reports) */
    val diff: CatalogueDiff get() = countries.singleOrNull()?.diff ?: emptyDiff()

    /** changes that must not be taken over without a look */
    val reviewReasons: List<String> get() =
        countries.flatMap { c -> c.diff.reviewReasons.map { "${c.country}: $it" } } +
            removedCountries.map { "$it: the catalogue is not in the package anymore and would be removed" } +
            ruleSignsNotImported.map { (country, signs) -> "$country: signs of the curated rules are not imported: ${signs.joinToString()}" }

    fun summary(version: String) = buildString {
        append("Traffic sign catalogues ${oldVersion ?: "(none)"} -> $version: ")
        append(countries.filter { it.isImported }.joinToString { "${it.country} ${it.imported.size}/${it.catalogueSigns}" })
        countries.filter { !it.isImported }.takeIf { it.isNotEmpty() }?.let { failed -> append("; not imported: ${failed.joinToString { it.country }}") }
        if (identical) append(" (identical to the existing files)")
        if (reviewReasons.isNotEmpty()) append("; ${reviewReasons.size} reasons for a manual review")
        if (problems.isNotEmpty()) append("; ${problems.size} PROBLEMS")
    }

    fun report(version: String, sha256: String, config: TrafficSignUpdateConfig) = buildString {
        appendLine("# Traffic sign catalogue update ${oldVersion ?: "(none)"} -> $version")
        appendLine()
        appendLine("- source: $TRAFFIC_SIGNS_REGISTRY/-/converter-$version.tgz")
        appendLine("- sha256: $sha256")
        appendLine("- countries found in the package: ${countries.joinToString { it.country }.ifEmpty { "-" }}")
        appendLine("- files: ${addedFiles.size} added, ${changedFiles.size} changed, ${removedFiles.size} removed")
        appendLine()
        if (problems.isNotEmpty()) {
            appendLine("## Problems (the update is not applied)")
            problems.forEach { appendLine("- $it") }
            appendLine()
        }
        if (reviewReasons.isNotEmpty()) {
            appendLine("## Needs a manual review")
            reviewReasons.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## Import per country")
        appendLine("Skipped: no image, no image licence, unsupported id. Errors: invalid entry, defective image, image name collision.")
        appendLine()
        appendLine("| country | in catalogue | imported | skipped | errors | status |")
        appendLine("|---|---|---|---|---|---|")
        for (c in countries) {
            val status = if (c.isImported) "imported" else "NOT imported: ${c.problems.joinToString("; ")}"
            appendLine("| ${c.country} | ${c.catalogueSigns} | ${c.imported.size} | ${c.skippedCount} | ${c.errorCount} | $status |")
        }
        appendLine()
        for (c in countries) {
            appendLine("### ${c.country}")
            val byReason = c.excluded.groupBy { it.reason }
            for (reason in ExclusionReason.entries) {
                val signs = byReason[reason] ?: continue
                appendLine("- ${reason.id} (${signs.size}): ${signs.joinToString { if (it.detail.isEmpty()) it.osmValuePart else "${it.osmValuePart} (${it.detail})" }}")
            }
            if (c.warnings.isNotEmpty()) appendLine("- conversion warnings (${c.warnings.size}): ${c.warnings.take(20).joinToString("; ")}")
            appendLine("- image licences: ${c.imageLicences.sorted().joinToString().ifEmpty { "-" }}")
            val d = c.diff
            appendLine("- added (${d.added.size}): ${d.added.joinToString().ifEmpty { "-" }}")
            appendLine("- removed (${d.removed.size}): ${d.removed.joinToString().ifEmpty { "-" }}")
            appendLine("- changed (${d.changed.size}):")
            d.changed.forEach { (sign, fields) -> appendLine("  - $sign: ${fields.joinToString()}") }
            appendLine("- images changed (${d.imagesChanged.size}): ${d.imagesChanged.take(50).joinToString().ifEmpty { "-" }}")
            appendLine("- fields added: ${d.addedFields.joinToString().ifEmpty { "-" }}; fields removed: ${d.removedFields.joinToString().ifEmpty { "-" }}")
            val rules = config.ruleSigns[c.country].orEmpty()
            if (rules.isNotEmpty()) {
                appendLine("- signs of the curated rules (WAY_RULES): ${rules.sorted().joinToString()}")
                appendLine(if (d.ruleChanges.isEmpty()) "  - none of them changed" else d.ruleChanges.joinToString("\n") { "  - $it" })
            }
            appendLine()
        }
        appendLine("## After applying")
        appendLine("1. ./gradlew :app:testAndroidHostTest (catalogue tests, rule check, parity)")
        appendLine("2. update the expected values in TrafficSignCatalogueTest (version, number of signs) after reading this report")
        appendLine("3. converter parity: cd tools/traffic-sign-parity; npm install --save-exact $TRAFFIC_SIGNS_PACKAGE@$version; node reference.mjs; read the differences before committing reference.jsonl")
    }
}

class CatalogueDiff(
    val added: List<String>,
    val removed: List<String>,
    /** osm value part -> names of the fields that changed */
    val changed: Map<String, List<String>>,
    val imagesChanged: List<String>,
    val addedFields: Set<String>,
    val removedFields: Set<String>,
    /** changes of the signs the rules are made of, and of redirects that touch them */
    val ruleChanges: List<String>,
    /** signs that were imported before and are not imported anymore (but still in the catalogue) */
    val noLongerImported: List<String> = emptyList(),
) {
    /** changes that must not be taken over without a look: they could change what the curated tag rules
     *  do or what existing values mean */
    val reviewReasons: List<String> get() = buildList {
        if (ruleChanges.isNotEmpty()) addAll(ruleChanges.map { "rule sign: $it" })
        if (addedFields.isNotEmpty() || removedFields.isNotEmpty()) add("the structure of the entries changed (fields +${addedFields} -${removedFields})")
        if (removed.isNotEmpty()) add("${removed.size} signs were removed: existing values with them stay as unknown signs (${removed.take(10).joinToString()})")
        if (noLongerImported.isNotEmpty()) add("${noLongerImported.size} signs are not imported anymore: existing values with them stay as unknown signs (${noLongerImported.take(10).joinToString()})")
        changed.filterValues { f -> f.any { it in READING_FIELDS } }
            .forEach { (sign, fields) -> add("$sign changed ${fields.filter { it in READING_FIELDS }}: this can change how values are read") }
    }
}

private val READING_FIELDS = setOf("kind", "signId", "redirects", "redirectDashValues", "signValue")

fun planCatalogueUpdate(
    config: TrafficSignUpdateConfig,
    version: String,
    sha256: String,
    packageFiles: Map<String, ByteArray>,
    existing: Map<String, ByteArray>,
    convertSvg: (name: String, svg: ByteArray) -> Pair<ByteArray?, String>,
): CatalogueUpdatePlan {
    val problems = ArrayList<String>()
    val countries = detectCountries(packageFiles)
    val existingCountries = existingCountries(existing)
    val oldIndex = existing[TRAFFIC_SIGNS_INDEX]?.let { runCatching { parseJson(it.decodeToString()) }.getOrNull() }
    if (countries.isEmpty()) problems.add("The package contains no catalogue (dist/data/{CC}.json)")

    val files = LinkedHashMap<String, ByteArray>()
    val imports = ArrayList<CountryImport>()
    val ruleSignsNotImported = LinkedHashMap<String, List<String>>()
    for (country in countries) {
        val countryFiles = LinkedHashMap<String, ByteArray>()
        val import = planCountry(config, country, version, packageFiles, existing, oldIndex, convertSvg, countryFiles)
        imports.add(import)
        if (import.isImported) {
            files.putAll(countryFiles)
        } else if (country in existingCountries) {
            // a catalogue that is there now must not silently disappear
            problems.addAll(import.problems.map { "$country: $it" })
        }
        // the rules should only be made of imported signs: the rules stay as they are, but a value
        // with a sign that is not imported anymore is not read as that sign
        val missing = config.ruleSigns[country].orEmpty().filter { it !in import.imported }.sorted()
        if (missing.isNotEmpty()) ruleSignsNotImported[country] = missing
    }
    for ((country, ruleSigns) in config.ruleSigns) {
        if (country !in countries && ruleSigns.isNotEmpty()) ruleSignsNotImported[country] = ruleSigns.sorted()
    }
    val removedCountries = existingCountries.filter { it !in countries }.sorted()
    files["VERSION"] = versionFileText(version, sha256, imports.filter { it.isImported }).encodeToByteArray()
    files[TRAFFIC_SIGNS_INDEX] = indexText(version, imports.filter { it.isImported }).encodeToByteArray()
    return CatalogueUpdatePlan(files, existing, existingVersion(existing), imports, removedCountries, problems, ruleSignsNotImported)
}

private fun planCountry(
    config: TrafficSignUpdateConfig,
    country: String,
    version: String,
    packageFiles: Map<String, ByteArray>,
    existing: Map<String, ByteArray>,
    oldIndex: JsonObject?,
    convertSvg: (name: String, svg: ByteArray) -> Pair<ByteArray?, String>,
    files: MutableMap<String, ByteArray>,
): CountryImport {
    val json = packageFiles.getValue("package/dist/data/$country.json")
    val text = json.decodeToString()
    val root = runCatching { parseJson(text) }.getOrNull()
    val catalogueProblems = catalogueProblems(root, country, version)
    val signs = (root?.get("signs") as? JsonArray).orEmpty()
    if (root == null || catalogueProblems.isNotEmpty()) {
        return CountryImport(country, false, signs.size, emptyList(), emptyList(), emptySet(), emptyList(), catalogueProblems, emptyDiff())
    }

    val svgPrefix = "package/dist/data-svgs/$country/svgs/"
    val svgs = packageFiles.filterKeys { it.startsWith(svgPrefix) && it.endsWith(".svg") }
        .mapKeys { it.key.removePrefix(svgPrefix).removeSuffix(".svg") }

    val excluded = ArrayList<ExcludedSign>()
    // 1. entries that can be read and whose id can be written
    val candidates = ArrayList<Pair<String, JsonObject>>()
    val seen = HashSet<String>()
    signs.forEachIndexed { index, element ->
        val sign = element as? JsonObject
        val id = sign?.str("osmValuePart")
        val problem = if (sign == null) "not an object" else entryProblem(sign)
        when {
            problem != null -> excluded.add(ExcludedSign(id ?: "#$index", ExclusionReason.INVALID_ENTRY, problem))
            !seen.add(id!!) -> excluded.add(ExcludedSign(id, ExclusionReason.INVALID_ENTRY, "duplicate osmValuePart"))
            else -> {
                val idProblem = osmValuePartProblem(id)
                if (idProblem != null) excluded.add(ExcludedSign(id, ExclusionReason.UNSUPPORTED_ID, idProblem))
                else candidates.add(id to sign!!)
            }
        }
    }
    // 2. signs that would share an image are ambiguous
    val collisions = candidates.groupBy { imageName(country, it.first) }.filterValues { it.size > 1 }
    val collidingIds = collisions.values.flatten().mapTo(HashSet()) { it.first }
    for ((image, group) in collisions) {
        for ((id, _) in group) excluded.add(ExcludedSign(id, ExclusionReason.IMAGE_NAME_COLLISION, "$image is also the image of ${group.map { it.first }.filter { it != id }.joinToString()}"))
    }
    // 3. the image: it must exist, have a licence and be convertible
    val imported = ArrayList<String>()
    val licences = sortedSetOf<String>()
    val warnings = ArrayList<String>()
    val images = sortedMapOf<String, ByteArray>()
    for ((id, sign) in candidates) {
        if (id in collidingIds) continue
        val image = imageName(country, id)
        val svg = svgs[image]
        val licence = imageLicence(sign)
        if (svg == null) { excluded.add(ExcludedSign(id, ExclusionReason.NO_IMAGE, "")); continue }
        if (licence == null) { excluded.add(ExcludedSign(id, ExclusionReason.NO_IMAGE_LICENCE, "")); continue }
        val (xml, log) = try {
            convertSvg(image, svg)
        } catch (e: Exception) {
            null to e.toString()
        }
        val errors = log.lines().filter { it.isNotBlank() && !isConversionWarning(it) && !it.trim().startsWith("In ") }
        if (xml == null || xml.isEmpty() || !xml.decodeToString().contains("<vector") || errors.isNotEmpty()) {
            val detail = errors.joinToString(" ").ifBlank { log.replace('\n', ' ').ifBlank { "no output" } }
            excluded.add(ExcludedSign(id, ExclusionReason.DEFECTIVE_IMAGE, detail.take(200)))
            continue
        }
        log.lines().filter { isConversionWarning(it) }.forEach { warnings.add("$image: ${it.trim()}") }
        images[image] = xml
        imported.add(id)
        licences.add(licence)
    }
    if (imported.isEmpty()) {
        return CountryImport(country, false, signs.size, emptyList(), excluded.sortedBy { it.osmValuePart }, emptySet(), warnings, listOf("no sign can be imported"), emptyDiff())
    }

    files["$country.json"] = json
    for ((name, xml) in images) files["$country/$name.xml"] = xml

    val oldJson = existing["$country.json"]?.decodeToString()
    val diff = try {
        val oldImported = oldJson?.let { importedSignsOf(it, oldIndex, country) }
        diffCatalogues(oldJson, text, config.ruleSigns[country].orEmpty(), existing, files, country, oldImported, imported.toSet())
    } catch (e: Exception) {
        return CountryImport(country, false, signs.size, emptyList(), excluded, licences, warnings, listOf("The catalogues can not be compared: $e"), emptyDiff())
    }
    return CountryImport(country, true, signs.size, imported, excluded.sortedBy { it.osmValuePart }, licences, warnings, emptyList(), diff)
}

/** Svg2Vector writes "WARNING @ line ..." for things it ignores and "ERROR @ line ..." for things it
 *  can not convert. Only warnings are acceptable. */
private fun isConversionWarning(line: String) = line.trim().startsWith("WARNING")

/** the signs of the existing catalogue that were imported: all but the excluded ones of the index */
private fun importedSignsOf(json: String, index: JsonObject?, country: String): Set<String> {
    val all = signsOf(parseJson(json)).keys
    val entry = (index?.get("countries") as? JsonArray)?.mapNotNull { it as? JsonObject }?.find { it.str("country") == country }
    val excluded = (entry?.get("excluded") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("osmValuePart") }.toSet()
    return all - excluded
}

private fun existingCountries(existing: Map<String, ByteArray>): List<String> =
    existing.keys.mapNotNull { Regex("^([A-Z]{2})\\.json$").matchEntire(it)?.groupValues?.get(1) }.sorted()

private fun existingVersion(existing: Map<String, ByteArray>): String? =
    existing["VERSION"]?.decodeToString()?.lineSequence()?.firstOrNull()?.substringAfter('@', "")?.substringAfter('@', "")?.ifBlank { null }

fun versionFileText(version: String, sha256: String, countries: List<CountryImport> = emptyList()) =
    "$TRAFFIC_SIGNS_PACKAGE@$version\n" +
        "sha256 $sha256\n" +
        "source $TRAFFIC_SIGNS_REGISTRY/-/converter-$version.tgz\n" +
        "licence of catalogue data: AGPL-3.0-or-later (https://github.com/osmberlin/osm-traffic-sign-tool)\n" +
        "licence of sign images: as stated per sign in the catalogue (" +
        countries.joinToString("; ") { "${it.country}: ${it.imageLicences.joinToString(", ")}" } + ")\n"

/** The index of the imported catalogues: which countries there are and which of their signs are not
 *  imported, and why. Read by the app. Deterministic: countries and signs are sorted. */
fun indexText(version: String, countries: List<CountryImport>): String {
    val root = buildJsonObject {
        put("package", "$TRAFFIC_SIGNS_PACKAGE@$version")
        putJsonArray("countries") {
            for (c in countries.sortedBy { it.country }) add(buildJsonObject {
                put("country", c.country)
                put("catalogueSigns", c.catalogueSigns)
                put("importedSigns", c.imported.size)
                put("skippedSigns", c.skippedCount)
                put("erroneousSigns", c.errorCount)
                putJsonArray("imageLicences") { c.imageLicences.sorted().forEach { add(JsonPrimitive(it)) } }
                put("excluded", buildJsonArray {
                    for (e in c.excluded.sortedWith(compareBy({ it.osmValuePart }, { it.reason.ordinal }))) add(buildJsonObject {
                        put("osmValuePart", e.osmValuePart)
                        put("reason", e.reason.id)
                    })
                })
            })
        }
    }
    return prettyJson.encodeToString(JsonElement.serializer(), root) + "\n"
}

private val prettyJson = Json { prettyPrint = true }

private fun emptyDiff() = CatalogueDiff(emptyList(), emptyList(), emptyMap(), emptyList(), emptySet(), emptySet(), emptyList())

/* ------------------------------------------------ diff ------------------------------------------------ */

internal fun parseJson(text: String): JsonObject =
    Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not a JSON object")

private fun signsOf(root: JsonObject): Map<String, JsonObject> =
    (root["signs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .mapNotNull { s -> (s["osmValuePart"] as? JsonPrimitive)?.content?.let { it to s } }.toMap()

/** The fields of the signs that decide what a sign means for the rules and the reading of values */
private val RULE_RELEVANT_FIELDS = listOf("kind", "signId", "tagRecommendationsByGeometry", "compatibility", "redirects", "redirectDashValues", "signValue", "valuePrompt")

fun diffCatalogues(
    oldJson: String?,
    newJson: String,
    ruleSigns: Set<String>,
    oldFiles: Map<String, ByteArray>,
    newFiles: Map<String, ByteArray>,
    country: String,
    /** the signs of the old catalogue that were imported, null: all */
    oldImported: Set<String>? = null,
    /** the signs of the new catalogue that are imported, null: all */
    newImported: Set<String>? = null,
): CatalogueDiff {
    val new = signsOf(parseJson(newJson))
    val old = oldJson?.let { signsOf(parseJson(it)) } ?: emptyMap()
    val added = new.keys.filter { it !in old }.sorted()
    val removed = old.keys.filter { it !in new }.sorted()
    val changed = LinkedHashMap<String, List<String>>()
    for ((id, sign) in new) {
        val before = old[id] ?: continue
        val fields = (sign.keys + before.keys).filter { sign[it] != before[it] }.sorted()
        if (fields.isNotEmpty()) changed[id] = fields
    }
    val imagesChanged = (newFiles.keys + oldFiles.keys).filter { it.startsWith("$country/") }
        .filter { !(oldFiles[it]?.contentEquals(newFiles[it] ?: ByteArray(0)) ?: false) || it !in oldFiles || it !in newFiles }
        .map { it.removePrefix("$country/").removeSuffix(".xml") }.sorted()
    val newFields = new.values.flatMapTo(sortedSetOf()) { it.keys }
    val oldFields = old.values.flatMapTo(sortedSetOf()) { it.keys }
    val addedFields = if (oldJson == null) emptySet() else newFields - oldFields
    val removedFields = if (oldJson == null) emptySet() else oldFields - newFields
    val noLongerImported = if (oldJson == null) emptyList() else {
        val before = oldImported ?: old.keys
        val after = newImported ?: new.keys
        before.filter { it in new && it !in after }.sorted()
    }

    val ruleChanges = ArrayList<String>()
    val ruleSignIds = ruleSigns.mapTo(HashSet()) { it.substringBefore('[') }
    for (id in ruleSigns.sorted()) {
        val n = new[id]
        val o = old[id]
        when {
            oldJson == null -> {}
            n == null -> ruleChanges.add("$id is not in the new catalogue")
            o == null -> ruleChanges.add("$id was not in the old catalogue")
            else -> for (f in RULE_RELEVANT_FIELDS) if (n[f] != o[f]) ruleChanges.add("$id: $f changed")
        }
    }
    // redirects that start or end at a sign of the rules (or use a sign id of them) change which sign a value is
    fun redirects(signs: Map<String, JsonObject>) = signs.values.flatMap { s ->
        (s["redirects"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
            Triple((it["from"] as? JsonPrimitive)?.content.orEmpty(), (it["to"] as? JsonPrimitive)?.content.orEmpty(), (s["osmValuePart"] as? JsonPrimitive)?.content.orEmpty())
        }
    }.toSet()
    if (oldJson != null) {
        val touching = { r: Triple<String, String, String> -> r.first in ruleSigns || r.second in ruleSigns || r.first.substringBefore('[') in ruleSignIds || r.second.substringBefore('[') in ruleSignIds }
        val before = redirects(old).filter(touching).toSet()
        val after = redirects(new).filter(touching).toSet()
        (after - before).forEach { ruleChanges.add("redirect added: \"${it.first}\" -> \"${it.second}\" (at ${it.third})") }
        (before - after).forEach { ruleChanges.add("redirect removed: \"${it.first}\" -> \"${it.second}\" (at ${it.third})") }
    }
    return CatalogueDiff(added, removed, changed, imagesChanged, addedFields, removedFields, ruleChanges, noLongerImported)
}

/* ------------------------------------------------ files ------------------------------------------------ */

/** Everything the catalogues consist of in [dir]: {CC}.json, the {CC}/<name>.xml images, VERSION and
 *  the index */
fun readCatalogueFiles(dir: File): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    val countries = dir.listFiles { f -> f.isFile && Regex("^[A-Z]{2}\\.json$").matches(f.name) }.orEmpty().map { it.nameWithoutExtension }.sorted()
    for (country in countries) {
        result["$country.json"] = File(dir, "$country.json").readBytes()
        File(dir, country).listFiles { f -> f.isFile && f.extension == "xml" }?.sortedBy { it.name }?.forEach { result["$country/${it.name}"] = it.readBytes() }
    }
    listOf("VERSION", TRAFFIC_SIGNS_INDEX).forEach { name -> File(dir, name).takeIf { it.isFile }?.let { result[name] = it.readBytes() } }
    return result
}

/** Writes only what differs and removes what is gone. The previous files are copied to [backupDir]
 *  first; if anything fails the previous state is restored and the exception is thrown again.
 *  [beforeWrite] is called before every file (for tests). */
fun applyCatalogueUpdate(targetDir: File, plan: CatalogueUpdatePlan, backupDir: File, beforeWrite: (String) -> Unit = {}) {
    backupDir.deleteRecursively()
    for ((path, content) in plan.oldFiles) File(backupDir, path).also { it.parentFile.mkdirs() }.writeBytes(content)
    try {
        for (path in plan.removedFiles) {
            beforeWrite(path)
            File(targetDir, path).delete()
        }
        for (path in plan.addedFiles + plan.changedFiles) {
            beforeWrite(path)
            File(targetDir, path).also { it.parentFile.mkdirs() }.writeBytes(plan.newFiles.getValue(path))
        }
        // directories of images of removed countries
        plan.removedFiles.map { File(targetDir, it).parentFile }.distinct()
            .filter { it != targetDir && it.isDirectory && it.list().isNullOrEmpty() }.forEach { it.delete() }
    } catch (e: Throwable) {
        // restore: everything that was there, and remove what is new
        for (path in plan.addedFiles) File(targetDir, path).delete()
        for ((path, content) in plan.oldFiles) File(targetDir, path).also { it.parentFile.mkdirs() }.writeBytes(content)
        throw TrafficSignUpdateException("Writing the catalogue failed, the previous files were restored: $e", e)
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Reads the file of the signs of the curated rules: one "CC:osmValuePart" per line, "#" comments */
fun parseRuleSigns(lines: List<String>): Map<String, Set<String>> =
    lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .map { line ->
            val country = Regex("^([A-Z]{2}):").find(line)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("rule sign \"$line\" has no country prefix like \"DE:\"")
            country to line.substring(3)
        }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
