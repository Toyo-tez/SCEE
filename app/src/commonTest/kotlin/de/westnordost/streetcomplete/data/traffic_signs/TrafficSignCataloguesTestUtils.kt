package de.westnordost.streetcomplete.data.traffic_signs

/** Catalogues for tests: an index with the given [countries] (no excluded signs) and the catalogue
 *  JSON of each country from [loadJson] */
internal fun TrafficSignCatalogues(
    countries: Set<String> = setOf("DE"),
    loadJson: (country: String) -> String?,
): TrafficSignCatalogues = TrafficSignCatalogues(
    loadIndex = { indexJson(countries.associateWith { emptySet() }) },
    loadJson = loadJson,
)

/** the JSON of an index of the catalogues: country -> osm value parts of the excluded signs */
internal fun indexJson(countries: Map<String, Set<String>>): String =
    """{"package": "test", "countries": [""" + countries.entries.joinToString(",") { (country, excluded) ->
        """{"country": "$country", "excluded": [""" +
            excluded.joinToString(",") { """{"osmValuePart": "${it.replace("\"", "\\\"")}", "reason": "no_image"}""" } + "]}"
    } + "]}"
