package de.westnordost.streetcomplete.data.traffic_signs

import de.westnordost.streetcomplete.osm.traffic_sign.TrafficSignCatalogue
import de.westnordost.streetcomplete.resources.Res
import de.westnordost.streetcomplete.ui.ktx.readBytesOrNull
import de.westnordost.streetcomplete.util.SynchronizedLazyCache
import de.westnordost.streetcomplete.util.logs.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Provides the traffic sign catalogues bundled with the app (files/traffic_signs/, see
 *  UpdateTrafficSignsTask).
 *
 *  Which countries there are is not defined here but read from the index of the import
 *  (files/traffic_signs/catalogues.json): every country whose catalogue was imported is available.
 *  The index also lists the signs of each catalogue that were not imported (no image, an id that can
 *  not be written unambiguously, ...): they are left out of the catalogue.
 *
 *  Nothing is loaded before it is needed: the index on the first question about the countries, each
 *  catalogue on the first [get] of its country. Both are kept in memory afterwards. */
class TrafficSignCatalogues internal constructor(
    /** loads the index of the imported catalogues, or null if there is none */
    private val loadIndex: () -> String?,
    /** loads the catalogue JSON for the given country, or null if there is none */
    private val loadJson: (country: String) -> String?,
) {
    constructor(res: Res) : this(
        { load(res, "files/traffic_signs/$INDEX_FILE") },
        { country -> load(res, "files/traffic_signs/$country.json") },
    )

    private val index: Map<String, Set<String>> by lazy {
        try {
            loadIndex()?.let(::parseTrafficSignCatalogueIndex) ?: run {
                Log.e(TAG, "There is no index of the traffic sign catalogues")
                emptyMap()
            }
        } catch (e: Exception) {
            // never an error for the one who opens the overlay: then there is just no catalogue
            Log.e(TAG, "Unable to read the index of the traffic sign catalogues", e)
            emptyMap()
        }
    }

    private val catalogues = SynchronizedLazyCache<String, TrafficSignCatalogue?> { country ->
        try {
            val json = loadJson(country)
            if (json == null) {
                Log.e(TAG, "The traffic sign catalogue of $country is missing")
                null
            } else {
                // a broken sign is left out and logged, the others stay usable (see parseLenient)
                val result = TrafficSignCatalogue.parseLenient(json)
                for (problem in result.problems) Log.w(TAG, "Problem in traffic sign catalogue of $country: $problem")
                val catalogue = result.catalogue
                when {
                    catalogue == null -> { Log.e(TAG, "Unable to use traffic sign catalogue of $country"); null }
                    catalogue.country != country -> { Log.e(TAG, "The traffic sign catalogue of $country is of ${catalogue.country}"); null }
                    else -> catalogue.withoutSigns(index[country].orEmpty())
                }
            }
        } catch (e: Exception) {
            // never an error for the one who opens the quest or overlay
            Log.e(TAG, "Unable to load traffic sign catalogue of $country", e)
            null
        }
    }

    /** The countries (ISO 3166-1, e.g. "DE") for which a catalogue was imported */
    val availableCountries: Set<String> get() = index.keys

    /** Whether there is a catalogue for the given country. Only reads the (small) index. */
    fun isAvailable(country: String): Boolean = country in index

    /** Get the catalogue of the given country (e.g. "DE"), null if there is none or it can not be
     *  read. Loads it synchronously on first access, so this should not be called on the main thread
     *  for the first time. */
    fun get(country: String): TrafficSignCatalogue? =
        if (isAvailable(country)) catalogues.get(country) else null

    companion object {
        /** keep in sync with TRAFFIC_SIGNS_INDEX in buildSrc */
        const val INDEX_FILE = "catalogues.json"
        private const val TAG = "TrafficSignCatalogues"
    }
}

/** Parses the index of the imported catalogues: country -> osm value parts of the signs of its
 *  catalogue that were not imported. Countries that are not an ISO 3166-1 code are left out. */
internal fun parseTrafficSignCatalogueIndex(json: String): Map<String, Set<String>> {
    val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyMap()
    val countries = root["countries"] as? JsonArray ?: return emptyMap()
    val result = LinkedHashMap<String, Set<String>>()
    for (element in countries) {
        val entry = element as? JsonObject ?: continue
        val country = (entry["country"] as? JsonPrimitive)?.contentOrNull ?: continue
        if (!COUNTRY_CODE.matches(country)) continue
        val excluded = (entry["excluded"] as? JsonArray).orEmpty()
            .mapNotNullTo(HashSet()) { ((it as? JsonObject)?.get("osmValuePart") as? JsonPrimitive)?.contentOrNull }
        result[country] = excluded
    }
    return result
}

private val COUNTRY_CODE = Regex("^[A-Z]{2}$")

/** the catalogue without the signs with the given osm value parts */
private fun TrafficSignCatalogue.withoutSigns(excluded: Set<String>): TrafficSignCatalogue =
    if (excluded.isEmpty() || signs.none { it.osmValuePart in excluded }) {
        this
    } else {
        TrafficSignCatalogue(country, version, signs.filter { it.osmValuePart !in excluded }, relations)
    }

private fun load(res: Res, path: String): String? =
    runBlocking { res.readBytesOrNull(path)?.decodeToString() }
