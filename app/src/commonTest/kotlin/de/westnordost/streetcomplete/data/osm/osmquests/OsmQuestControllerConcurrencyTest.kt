package de.westnordost.streetcomplete.data.osm.osmquests

import com.russhwolf.settings.ObservableSettings
import de.westnordost.streetcomplete.Prefs
import de.westnordost.streetcomplete.data.download.tiles.DownloadedTilesSource
import de.westnordost.streetcomplete.data.osm.edits.MapDataWithEditsSource
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryEntry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType.NODE
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.MutableMapDataWithGeometry
import de.westnordost.streetcomplete.data.osmnotes.edits.NotesWithEditsSource
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import de.westnordost.streetcomplete.data.quest.TestQuestTypeA
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.p
import de.westnordost.streetcomplete.testutils.pGeom
import de.westnordost.streetcomplete.util.countryboundaries.Countries
import de.westnordost.streetcomplete.util.countryboundaries.CountryBoundaries
import de.westnordost.streetcomplete.util.countryboundaries.NoCountriesExcept
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import de.westnordost.streetcomplete.util.math.contains
import de.westnordost.streetcomplete.ApplicationConstants
import de.westnordost.streetcomplete.data.download.tiles.TilePos
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.key
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OsmQuestControllerConcurrencyTest {

    private lateinit var db: OsmQuestDao
    private lateinit var mapDataSource: MapDataWithEditsSource
    private lateinit var notesSource: NotesWithEditsSource
    private lateinit var questTypeRegistry: QuestTypeRegistry
    private lateinit var prefs: ObservableSettings
    private lateinit var ctrl: OsmQuestController
    private lateinit var mapDataListener: MapDataWithEditsSource.Listener

    @BeforeTest fun setUp() {
        db = mock()
        mapDataSource = mock {
            every { addListener(any()) } calls { (listener: MapDataWithEditsSource.Listener) ->
                mapDataListener = listener
            }
        }
        notesSource = mock {
            every { addListener(any()) } returns Unit
        }
        questTypeRegistry = QuestTypeRegistry({ listOf(0 to GatedQuestType) })
        val countryBoundaries = object : CountryBoundaries {
            override fun isInAny(position: LatLon, countries: Countries) = true
            override fun getIds(position: LatLon) = emptyList<String>()
            override fun intersects(bbox: BoundingBox, countries: Countries) = true
            // consistent with getIds(): no country is known to contain the whole bbox
            override fun getContainingIds(bbox: BoundingBox): Set<String> = emptySet()
        }
        prefs = mock {
            every { getBoolean(Prefs.DYNAMIC_QUEST_CREATION, false) } returns false
            every { getBoolean(Prefs.EXPERT_MODE, false) } returns false
            every { getBoolean(Prefs.OVERRIDE_COUNTRY_RESTRICTIONS, false) } returns false
        }
        val downloadedTiles: DownloadedTilesSource = mock()
        ctrl = OsmQuestController(db, mapDataSource, notesSource, questTypeRegistry, lazyOf(countryBoundaries), prefs, downloadedTiles)
    }

    @Test fun `stale quest evaluation does not overwrite newer results when completions are out of order`() = runBlocking {
        val pos = p()
        val geom = pGeom()
        val older = mapDataFor(node(1, tags = mapOf("a" to "b")), geom)
        val newer = mapDataFor(node(1, tags = emptyMap()), geom)

        every { db.getAllForElements(any()) } returns emptyList()
        every { mapDataSource.getMapDataWithGeometry(any()) } returns older
        every { notesSource.getAllPositions(any()) } returns emptyList()

        val releaseOlder = CompletableDeferred<Unit>()
        val releaseNewer = CompletableDeferred<Unit>()
        GatedQuestType.releaseNewer = releaseNewer
        GatedQuestType.releaseOlder = releaseOlder
        GatedQuestType.evaluationsStarted = AtomicInteger(0)

        val olderEval = async(Dispatchers.Default) {
            mapDataListener.onUpdated(older, emptyList())
        }
        val newerEval = async(Dispatchers.Default) {
            mapDataListener.onUpdated(newer, emptyList())
        }

        withTimeout(10_000) {
            while (GatedQuestType.evaluationsStarted.get() < 2) { kotlinx.coroutines.yield() }
        }

        releaseNewer.complete(Unit)
        withTimeout(10_000) { newerEval.await() }
        releaseOlder.complete(Unit)
        withTimeout(10_000) { olderEval.await() }

        verify { db.putAll(matches { it.isEmpty() }) }
        verify(exactly(0)) { db.putAll(matches { it.isNotEmpty() }) }
    }

    private fun mapDataFor(element: Element, geometry: ElementPointGeometry) = MutableMapDataWithGeometry(
        listOf(element),
        listOf(ElementGeometryEntry(NODE, element.id, geometry)),
    )
}

private object GatedQuestType : TestQuestTypeA() {
    var releaseOlder = CompletableDeferred<Unit>()
    var releaseNewer = CompletableDeferred<Unit>()
    var evaluationsStarted = AtomicInteger(0)

    override fun isApplicableTo(element: Element): Boolean? = null

    override fun getApplicableElements(mapData: MapDataWithGeometry): List<Element> {
        evaluationsStarted.incrementAndGet()
        val gate = if (mapData.any { it.tags["a"] == "b" }) releaseOlder else releaseNewer
        runBlocking { gate.await() }
        return if (mapData.any { it.tags["a"] == "b" }) mapData.toList() else emptyList()
    }
}
/** Independent edits must never lose each other's quest updates (REL-C2). */
class OsmQuestControllerLostUpdateTest {

    private lateinit var db: OsmQuestDao
    private lateinit var mapDataSource: MapDataWithEditsSource
    private lateinit var ctrl: OsmQuestController
    private lateinit var listener: MapDataWithEditsSource.Listener
    private val persisted = java.util.Collections.synchronizedList(mutableListOf<OsmQuest>())

    @BeforeTest fun setUp() {
        PerElementGateQuestType.reset()
        persisted.clear()
        db = mock()
        every { db.getAllForElements(any()) } returns emptyList()
        every { db.getAllInBBox(any()) } returns emptyList()
        every { db.putAll(any()) } calls { (q: Collection<OsmQuest>) -> persisted.addAll(q); Unit }
        every { db.deleteAll(any()) } returns Unit
        mapDataSource = mock {
            every { getMapDataWithGeometry(any()) } calls { MutableMapDataWithGeometry() }
            every { getWaysForNode(any()) } returns emptyList()
            every { addListener(any()) } calls { (l: MapDataWithEditsSource.Listener) -> listener = l }
        }
        val notes: NotesWithEditsSource = mock {
            every { addListener(any()) } returns Unit
            every { getAllPositions(any()) } returns emptyList()
        }
        val boundaries = object : CountryBoundaries {
            override fun isInAny(position: LatLon, countries: Countries) = true
            override fun getIds(position: LatLon) = emptyList<String>()
            override fun intersects(bbox: BoundingBox, countries: Countries) = true
            // consistent with getIds(): no country is known to contain the whole bbox
            override fun getContainingIds(bbox: BoundingBox): Set<String> = emptySet()
        }
        val prefs: ObservableSettings = mock {
            every { getBoolean(Prefs.DYNAMIC_QUEST_CREATION, false) } returns false
            every { getBoolean(Prefs.EXPERT_MODE, false) } returns false
            every { getBoolean(Prefs.OVERRIDE_COUNTRY_RESTRICTIONS, false) } returns false
        }
        ctrl = OsmQuestController(db, mapDataSource, notes, QuestTypeRegistry({ listOf(0 to PerElementGateQuestType) }), lazyOf(boundaries), prefs, mock<DownloadedTilesSource>())
    }

    /** runs a test body; always opens all gates afterwards so a failing assertion cannot hang the suite */
    private fun gated(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        try { coroutineScope { try { body() } finally { PerElementGateQuestType.releaseAll() } } }
        finally { PerElementGateQuestType.releaseAll() }
    }

    @Test fun `deleted element is not resurrected by paused older update of it`() = gated {
        val older = async(Dispatchers.Default) { listener.onUpdated(data(1), emptyList()) }
        withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 1) kotlinx.coroutines.yield() }
        // the element is deleted (no evaluation, nothing to gate) while the older evaluation is paused
        every { db.getAllForElements(any()) } returns emptyList()
        listener.onUpdated(MutableMapDataWithGeometry(), listOf(ElementKey(NODE, 1)))
        PerElementGateQuestType.gate(1).complete(Unit)
        withTimeout(10_000) { older.await() }
        assertEquals(emptyList(), persisted.map { it.elementId })
    }

    private fun data(id: Long) = MutableMapDataWithGeometry(
        listOf(node(id, tags = mapOf("a" to "b"))),
        listOf(ElementGeometryEntry(NODE, id, pGeom())),
    )

    @Test fun `edit A paused while independent edit B completes keeps quests of both`() = gated {
        val a = async(Dispatchers.Default) { listener.onUpdated(data(1), emptyList()) }
        val b = async(Dispatchers.Default) { listener.onUpdated(data(2), emptyList()) }
        withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 2) kotlinx.coroutines.yield() }
        PerElementGateQuestType.gate(2).complete(Unit)
        withTimeout(10_000) { b.await() }
        PerElementGateQuestType.gate(1).complete(Unit)
        withTimeout(10_000) { a.await() }
        assertEquals(setOf(1L, 2L), persisted.map { it.elementId }.toSet())
    }

    @Test fun `bbox replacement paused while disjoint onUpdated completes keeps both`() = gated {
        val bbox = de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox(0.0, 0.0, 1.0, 1.0)
        val replace = async(Dispatchers.Default) { listener.onReplacedForBBox(bbox, data(1)) }
        withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 1) kotlinx.coroutines.yield() }
        val update = async(Dispatchers.Default) { listener.onUpdated(data(2), emptyList()) }
        withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 2) kotlinx.coroutines.yield() }
        PerElementGateQuestType.gate(2).complete(Unit)
        withTimeout(10_000) { update.await() }
        PerElementGateQuestType.gate(1).complete(Unit)
        withTimeout(10_000) { replace.await() }
        assertEquals(setOf(1L, 2L), persisted.map { it.elementId }.toSet())
    }

    @Test fun `same element superseded edit does not overwrite newer result`() = gated {
        val older = async(Dispatchers.Default) { listener.onUpdated(data(1), emptyList()) }
        withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 1) kotlinx.coroutines.yield() }
        PerElementGateQuestType.nextResultEmpty = true
        val newer = async(Dispatchers.Default) { listener.onUpdated(data(1), emptyList()) }
        withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 2) kotlinx.coroutines.yield() }
        PerElementGateQuestType.gate(1).complete(Unit)
        withTimeout(10_000) { older.await(); newer.await() }
        assertEquals(emptyList(), persisted.map { it.elementId })
    }
}

private object PerElementGateQuestType : TestQuestTypeA() {
    val started = AtomicInteger(0)
    private val gates = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    @Volatile var nextResultEmpty = false
    fun gate(id: Long) = gates.getOrPut(id) { CompletableDeferred<Unit>().also { if (releasedAll) it.complete(Unit) } }
    fun releaseAll() { releasedAll = true; gates.values.forEach { it.complete(Unit) } }
    @Volatile var releasedAll = false
    fun reset() { releasedAll = false; started.set(0); gates.clear(); nextResultEmpty = false }

    override val isCountryRestrictionOverridable = true
    override val enabledInCountries: Countries = NoCountriesExcept("DE")
    override fun isApplicableTo(element: Element): Boolean? = null
    override fun getApplicableElements(mapData: MapDataWithGeometry): List<Element> {
        val list = mapData.toList()
        val empty = nextResultEmpty && started.get() >= 1
        started.incrementAndGet()
        runBlocking { gate(list.maxOf { it.id }).await() }
        return if (empty) emptyList() else list
    }
}

/** A country refresh interrupted by an unrelated edit must still bring every tile to its final state. */
class OsmQuestControllerCountryRefreshTest {
    private val zoom = ApplicationConstants.DOWNLOAD_TILE_ZOOM
    private val tile1 = TilePos(100, 100)
    private val tile2 = TilePos(101, 100)
    private lateinit var listener: MapDataWithEditsSource.Listener
    private val persisted = java.util.Collections.synchronizedList(mutableListOf<OsmQuest>())
    private val state = java.util.concurrent.ConcurrentHashMap<Long, LatLon>()

    private fun center(t: TilePos) = t.asBoundingBox(zoom).let { LatLon((it.min.latitude + it.max.latitude) / 2, (it.min.longitude + it.max.longitude) / 2) }

    private fun mapDataIn(bbox: de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox): MutableMapDataWithGeometry {
        val d = MutableMapDataWithGeometry()
        for ((id, pos) in state) if (bbox.contains(pos)) d.put(node(id, pos, mapOf("a" to "b")), ElementPointGeometry(pos))
        return d
    }

    @Test fun `edit during country refresh leaves every tile and the edited element in final state`() = runBlocking {
        PerElementGateQuestType.reset()
        persisted.clear()
        val db: OsmQuestDao = mock()
        every { db.getAllForElements(any()) } returns emptyList()
        every { db.getAllInBBox(any(), any()) } returns emptyList()
        every { db.putAll(any()) } calls { (q: Collection<OsmQuest>) -> persisted.addAll(q); Unit }
        every { db.deleteAll(any()) } returns Unit
        val mapDataSource: MapDataWithEditsSource = mock {
            every { addListener(any()) } calls { (l: MapDataWithEditsSource.Listener) -> listener = l }
            every { getMapDataWithGeometry(any()) } calls { (b: de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox) -> mapDataIn(b) }
            every { getWaysForNode(any()) } returns emptyList()
        }
        val notes: NotesWithEditsSource = mock {
            every { addListener(any()) } returns Unit
            every { getAllPositions(any()) } returns emptyList()
        }
        val boundaries = object : CountryBoundaries {
            override fun isInAny(position: LatLon, countries: Countries) = true
            override fun getIds(position: LatLon) = emptyList<String>()
            override fun intersects(bbox: BoundingBox, countries: Countries) = true
            // consistent with getIds(): no country is known to contain the whole bbox
            override fun getContainingIds(bbox: BoundingBox): Set<String> = emptySet()
        }
        val prefs: ObservableSettings = mock {
            every { getBoolean(Prefs.DYNAMIC_QUEST_CREATION, false) } returns false
            every { getBoolean(Prefs.EXPERT_MODE, false) } returns false
            every { getBoolean(Prefs.OVERRIDE_COUNTRY_RESTRICTIONS, false) } returns false
        }
        val tiles: DownloadedTilesSource = mock { every { getAll(any()) } returns listOf(tile1, tile2) }
        val ctrl = OsmQuestController(db, mapDataSource, notes, QuestTypeRegistry({ listOf(0 to PerElementGateQuestType) }), lazyOf(boundaries), prefs, tiles)

        state[1] = center(tile1)
        state[3] = center(tile2)
        try {
            val job = ctrl.refreshCountryRestrictedQuests()!!
            withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 1) kotlinx.coroutines.yield() }

            // independent element 2 (inside tile 1) is added while the refresh is paused on tile 1
            val pos2 = center(tile1).let { LatLon(it.latitude + 1e-4, it.longitude + 1e-4) }
            state[2] = pos2
            val edit = async(Dispatchers.Default) {
                listener.onUpdated(
                    MutableMapDataWithGeometry(listOf(node(2, pos2, mapOf("a" to "b"))), listOf(ElementGeometryEntry(NODE, 2, ElementPointGeometry(pos2)))),
                    emptyList()
                )
            }
            withTimeout(10_000) { while (PerElementGateQuestType.started.get() < 2) kotlinx.coroutines.yield() }
            PerElementGateQuestType.gate(2).complete(Unit)
            withTimeout(10_000) { edit.await() }
            PerElementGateQuestType.releaseAll()
            withTimeout(10_000) { job.join() }

            assertEquals(setOf(1L, 2L, 3L), persisted.map { it.elementId }.toSet())
        } finally {
            PerElementGateQuestType.releaseAll()
        }
    }
}
