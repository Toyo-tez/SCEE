package de.westnordost.streetcomplete.quests.is_sidepath

import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.streetcomplete.osm.FILTER_FOOT_OR_BICYCLE_ACCESSIBLE
import de.westnordost.streetcomplete.osm.Tags
import de.westnordost.streetcomplete.osm.getLastCheckDateKeys
import de.westnordost.streetcomplete.osm.toCheckDate
import de.westnordost.streetcomplete.osm.updateCheckDateForKey
import de.westnordost.streetcomplete.util.ktx.toLocalDate
import kotlin.time.Instant

/** Shared highway/path tag shape for eligible sidepath ways (classification + completion). */
private const val SIDEPATH_ELIGIBLE_PATH_SHAPE = """
      (
        (
          highway = cycleway
          and cycleway !~ sidewalk|link|sidepath|crossing
        )
        or
        (
          highway = footway
          and footway !~ link|crossing
        )
        or
        (
          highway = path
          and (bicycle or foot)
          and path !~ sidewalk|link|crossing|sidepath
        )
      )
"""

/** Tag filter for paths without [is_sidepath] and for yes-sidepaths missing road association. */
internal val ADD_IS_SIDEPATH_ELEMENT_FILTER = """
    ways with
      area != yes
      and ($FILTER_FOOT_OR_BICYCLE_ACCESSIBLE)
      and (
        (
          !is_sidepath
          and (
            (
              highway = cycleway
              and cycleway !~ sidewalk|link|sidepath|crossing
            )
            or
            (
              highway = footway
              and footway !~ sidewalk|link|crossing
            )
            or
            (
              highway = path
              and (bicycle or foot)
              and path !~ sidewalk|link|crossing|sidepath
            )
          )
        )
        or
        (
          is_sidepath = yes
          and (!is_sidepath:of or (!is_sidepath:of:name and !is_sidepath:of:ref))
          and $SIDEPATH_ELIGIBLE_PATH_SHAPE
        )
      )
""".trimIndent()

/**
 * Association is complete when [is_sidepath:of] exists and either an identifier is recorded or no
 * nearby candidate can supply a missing name or ref (see wiki Key:is_sidepath:of*).
 */
internal fun isSidepathRoadMetadataAssociationComplete(
    tags: Map<String, String>,
    geometry: ElementGeometry,
    mapData: MapDataWithGeometry,
): Boolean {
    val associatedHighway = tags["is_sidepath:of"]?.takeIf { it.isNotEmpty() } ?: return false
    if (!tags["is_sidepath:of:name"].isNullOrEmpty()) return true
    if (!tags["is_sidepath:of:ref"].isNullOrEmpty()) return true
    val candidates = findCandidateRoads(geometry, mapData)
        .filter { it.highway == associatedHighway }
    return candidates.none { candidate ->
        sidepathRoadMetadataAnswerProducesChanges(
            tags,
            candidate.name,
            candidate.highway,
            candidate.ref,
        )
    }
}

internal fun selectDefaultSidepathRoadCandidate(
    candidates: List<CandidateRoad>,
    preferredHighway: String? = null,
    roadSelectionProducesChanges: (ofName: String?, ofHighway: String?, ofRef: String?) -> Boolean,
): CandidateRoad {
    if (preferredHighway != null) {
        candidates.firstOrNull {
            it.highway == preferredHighway && roadSelectionProducesChanges(it.name, it.highway, it.ref)
        }?.let { return it }
    }
    return candidates.firstOrNull { roadSelectionProducesChanges(it.name, it.highway, it.ref) }
        ?: candidates.firstOrNull { it.name != null || it.ref != null }
        ?: candidates.first()
}

internal fun isSidepathRoadMetadataCompletionEligible(
    element: Element,
    geometry: ElementGeometry,
    mapData: MapDataWithGeometry,
): Boolean {
    val tags = element.tags
    if (tags["is_sidepath"] != "yes") return false
    if (isSidepathRoadMetadataAssociationComplete(tags, geometry, mapData)) return false
    val candidates = findCandidateRoads(geometry, mapData)
    val associatedHighway = tags["is_sidepath:of"]
    val relevantCandidates = candidates.let { roads ->
        if (associatedHighway.isNullOrEmpty()) roads
        else roads.filter { it.highway == associatedHighway }
    }
    if (hasSidepathAssociationSurveyMark(tags, relevantCandidates)) return false
    return relevantCandidates.any { candidate ->
        sidepathRoadMetadataAnswerProducesChanges(
            tags,
            candidate.name,
            candidate.highway,
            candidate.ref,
        )
    }
}

/** Whether [applySidepathRoadMetadata] would change anything (uses the same writer). */
internal fun sidepathRoadMetadataAnswerProducesChanges(
    tags: Map<String, String>,
    ofName: String?,
    ofHighway: String?,
    ofRef: String? = null,
): Boolean {
    val builder = StringMapChangesBuilder(tags)
    applySidepathRoadMetadata(builder, ofName, ofHighway, ofRef)
    return !builder.create().isEmpty()
}

/** Fill only missing association tags; never overwrite existing values or touch unrelated keys. */
internal fun applySidepathRoadMetadata(
    tags: Tags,
    ofName: String?,
    ofHighway: String?,
    ofRef: String?,
) {
    if (tags["is_sidepath:of"].isNullOrEmpty() && !ofHighway.isNullOrEmpty()) {
        tags["is_sidepath:of"] = ofHighway
    }
    if (tags["is_sidepath:of:name"].isNullOrEmpty() && !ofName.isNullOrEmpty()) {
        tags["is_sidepath:of:name"] = ofName
    }
    if (tags["is_sidepath:of:ref"].isNullOrEmpty() && !ofRef.isNullOrEmpty()) {
        tags["is_sidepath:of:ref"] = ofRef
    }
}

/**
 * Explicit road selection from the quest form: rewrite association tags as one unit.
 * Optional name/ref are cleared when the chosen road provides none.
 */
internal fun applySidepathRoadAssociationFromSelection(
    tags: Tags,
    ofName: String?,
    ofHighway: String,
    ofRef: String?,
) {
    if (tags["is_sidepath:of"] != ofHighway) {
        tags["is_sidepath:of"] = ofHighway
    }
    if (!ofName.isNullOrEmpty()) {
        if (tags["is_sidepath:of:name"] != ofName) {
            tags["is_sidepath:of:name"] = ofName
        }
    } else if (tags.containsKey("is_sidepath:of:name")) {
        tags.remove("is_sidepath:of:name")
    }
    if (!ofRef.isNullOrEmpty()) {
        if (tags["is_sidepath:of:ref"] != ofRef) {
            tags["is_sidepath:of:ref"] = ofRef
        }
    } else if (tags.containsKey("is_sidepath:of:ref")) {
        tags.remove("is_sidepath:of:ref")
    }
}

internal fun sidepathAssociationTagsMatchSelection(
    tags: Map<String, String>,
    ofName: String?,
    ofHighway: String?,
    ofRef: String?,
): Boolean {
    if (ofHighway.isNullOrEmpty()) return false
    if (tags["is_sidepath:of"] != ofHighway) return false
    val taggedName = tags["is_sidepath:of:name"]
    val taggedRef = tags["is_sidepath:of:ref"]
    val nameMatches = (taggedName.isNullOrEmpty() && ofName.isNullOrEmpty()) || taggedName == ofName
    val refMatches = (taggedRef.isNullOrEmpty() && ofRef.isNullOrEmpty()) || taggedRef == ofRef
    return nameMatches && refMatches
}

/**
 * Whether the sidepath association was surveyed and no nearby candidate road has been edited on a
 * later date. A newer road edit can expose association information that was unavailable before.
 */
internal fun hasSidepathAssociationSurveyMark(
    tags: Map<String, String>,
    candidates: List<CandidateRoad>,
): Boolean {
    val checkedDates = getLastCheckDateKeys("is_sidepath:of")
        .mapNotNull { tags[it]?.toCheckDate() }
        .toList()
    val lastChecked = checkedDates.maxOrNull() ?: return false
    return candidates.none {
        Instant.fromEpochMilliseconds(it.element.timestampEdited).toLocalDate() > lastChecked
    }
}

/**
 * Explicit road selection from the quest form. A road without name or ref is recorded with
 * `check_date:is_sidepath:of`, so that a named road of the same class nearby does not re-ask.
 */
internal fun applySidepathRoadSelection(
    tags: Tags,
    ofName: String?,
    ofHighway: String,
    ofRef: String?,
) {
    if (tags["is_sidepath"] != "yes") {
        tags["is_sidepath"] = "yes"
    }
    applySidepathRoadAssociationFromSelection(tags, ofName, ofHighway, ofRef)
    if (ofName.isNullOrEmpty() && ofRef.isNullOrEmpty()) {
        tags.updateCheckDateForKey("is_sidepath:of")
    }
}

/** Whether choosing this road in the form would change the tags (same writer as the answer). */
internal fun sidepathRoadSelectionProducesChanges(
    tags: Map<String, String>,
    ofName: String?,
    ofHighway: String?,
    ofRef: String?,
): Boolean {
    if (ofHighway.isNullOrEmpty()) return false
    val builder = StringMapChangesBuilder(tags)
    applySidepathRoadSelection(builder, ofName, ofHighway, ofRef)
    return builder.hasChanges
}

/**
 * Sidepath confirmed, road unknown: keep any existing association untouched and record the
 * association survey with `check_date:is_sidepath:of`.
 */
internal fun applySidepathCantSayWhichRoad(tags: Tags) {
    if (tags["is_sidepath"] != "yes") {
        tags["is_sidepath"] = "yes"
    }
    tags.updateCheckDateForKey("is_sidepath:of")
}

/** Explicit correction: not a sidepath; same association cleanup as [AddIsSidepath] “No”. */
internal fun applySidepathNotSidepath(tags: Tags) {
    tags["is_sidepath"] = "no"
    tags.remove("is_sidepath:of")
    tags.remove("is_sidepath:of:name")
    tags.remove("is_sidepath:of:ref")
}
