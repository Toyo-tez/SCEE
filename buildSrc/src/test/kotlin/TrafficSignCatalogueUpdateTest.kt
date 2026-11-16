import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The update of the traffic sign catalogue: a broken or failed update changes nothing, a good one is
 *  reported, applied once and can be repeated. No network: the registry is a map of URLs. */
class TrafficSignCatalogueUpdateTest {

    /* ------------------------------------------- fixtures ------------------------------------------- */

    private fun sign(
        id: String, kind: String = "traffic_sign", name: String = "Zeichen $id", signId: String = id,
        recommendations: String = "\"none\"", redirects: String = "[]", extra: String = "",
        licence: String? = "Public Domain",
    ): String {
        val image = if (licence == null) "\"missing\"" else """{"kind": "remote", "licence": "$licence"}"""
        return """{"osmValuePart": "${esc(id)}", "signId": "${esc(signId)}", "name": "$name", "kind": "$kind", "catalogue": {"signCategory": "x"}, "tagRecommendationsByGeometry": $recommendations, "redirects": $redirects, "image": $image$extra}"""
    }

    private fun esc(s: String) = s.replace("\"", "\\\"")

    private fun catalogue(version: String, vararg signs: String, country: String = "DE") =
        """{"country": "$country", "version": "$version", "signs": [${signs.joinToString(",")}], "relations": []}"""

    private fun imageName(id: String, country: String = "DE") = "${country}_" + id.replace("[", "__").replace("]", "__").replace(Regex("[^a-zA-Z0-9_]"), "_")

    private fun tarGz(files: Map<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, content) in files) {
            val header = ByteArray(512)
            name.encodeToByteArray().copyInto(header)
            "0000644".encodeToByteArray().copyInto(header, 100)
            String.format("%011o", content.size).encodeToByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            "ustar".encodeToByteArray().copyInto(header, 257)
            tar.write(header)
            tar.write(content)
            tar.write(ByteArray((512 - content.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    /** a package with the catalogue [json] of DE and the [others] (country -> catalogue json and ids of the svgs) */
    private class Package(
        val version: String, val json: String, val svgIds: List<String>,
        extra: Map<String, ByteArray> = emptyMap(),
        others: Map<String, Pair<String, List<String>>> = emptyMap(),
    ) {
        val files: Map<String, ByteArray> = buildMap {
            put("package/package.json", """{"version": "$version"}""".encodeToByteArray())
            for ((country, content) in mapOf("DE" to (json to svgIds)) + others) {
                put("package/dist/data/$country.json", content.first.encodeToByteArray())
                for (id in content.second) put("package/dist/data-svgs/$country/svgs/${"${country}_" + id.replace("[", "__").replace("]", "__").replace(Regex("[^a-zA-Z0-9_]"), "_")}.svg", "<svg id=\"$id\"/>".encodeToByteArray())
            }
            putAll(extra)
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** a fake registry */
    private class Registry(val packages: Map<String, ByteArray>, tarballs: (ByteArray) -> ByteArray = { it }) {
        var failing = false
        var failOnTarball = false
        val requested = ArrayList<String>()
        val tarballByVersion: Map<String, ByteArray> = packages.mapValues { tarballs(it.value) }

        fun fetch(url: String): ByteArray {
            requested.add(url)
            if (failing) throw java.io.IOException("offline")
            val base = "https://registry.npmjs.org/@osm-traffic-signs/converter"
            val rest = url.removePrefix("$base/")
            if (rest.startsWith("-/converter-")) {
                if (failOnTarball) throw java.io.IOException("connection reset")
                val version = rest.removePrefix("-/converter-").removeSuffix(".tgz")
                return tarballByVersion[version] ?: throw java.io.IOException("404")
            }
            val version = if (rest == "latest") packages.keys.maxOrNull()!! else rest
            val tarball = tarballByVersion[version] ?: throw java.io.IOException("404 $rest")
            val integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball))
            return """{"version": "$version", "dist": {"integrity": "$integrity"}}""".encodeToByteArray()
        }
    }

    private fun convert(name: String, svg: ByteArray): Pair<ByteArray?, String> =
        "<vector name=\"$name\">${svg.decodeToString().length}</vector>".encodeToByteArray() to ""

    private fun tmp(): File = Files.createTempDirectory("traffic-sign-update-test").toFile()

    private class Env(val root: File) {
        val target = File(root, "target")
        val backup = File(root, "backup")
        val reports = File(root, "reports")
        val history = File(root, "history")
    }

    private fun config(env: Env, version: String, sha256: String?, apply: Boolean = true, acceptReview: Boolean = false, ruleSigns: Set<String> = setOf("250", "1020-30")) =
        TrafficSignUpdateConfig(version, sha256, env.target, env.backup, env.reports, env.history, mapOf("DE" to ruleSigns), apply, acceptReview)

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path.replace('\\', '/') to sha256(it.readBytes()) }

    private fun run(env: Env, registry: Registry, version: String, sha: String?, apply: Boolean = true, acceptReview: Boolean = false, ruleSigns: Set<String> = setOf("250", "1020-30")) =
        updateTrafficSignCatalogue(config(env, version, sha, apply, acceptReview, ruleSigns), registry::fetch, ::convert, log = {})

    /** installs a version as the existing catalogue */
    private fun install(env: Env, pkg: Package): String {
        val registry = Registry(mapOf(pkg.version to tarGz(pkg.files)))
        val sha = sha256(registry.tarballByVersion.getValue(pkg.version))
        run(env, registry, pkg.version, sha)
        return sha
    }

    private val v1 = Package("0.1.0", catalogue("0.1.0", sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30")), listOf("250", "1020-30", "274-30"))

    private fun pkg2(vararg signs: String, version: String = "0.2.0", svgs: List<String>? = null): Package {
        val ids = signs.map { Regex("\"osmValuePart\": \"([^\"]+)\"").find(it)!!.groupValues[1] }
        return Package(version, catalogue(version, *signs), svgs ?: ids)
    }

    /* ----------------------------------------- success ----------------------------------------- */

    @Test fun `a new version is applied, reported and kept in the history`() {
        val env = Env(tmp())
        install(env, v1)
        val v2 = pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30", name = "Tempo 30"), sign("274-40"))
        val registry = Registry(mapOf("0.2.0" to tarGz(v2.files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
        val result = run(env, registry, "0.2.0", sha)
        assertTrue(result.applied)
        val target = snapshot(env.target)
        assertTrue("DE/${imageName("274-40")}.xml" in target)
        assertTrue(File(env.target, "VERSION").readText().startsWith("@osm-traffic-signs/converter@0.2.0\nsha256 $sha\n"))
        assertTrue(File(env.target, "DE.json").readText().contains("\"0.2.0\""))
        val diff = result.plan.diff
        assertEquals(listOf("274-40"), diff.added)
        assertEquals(mapOf("274-30" to listOf("name")), diff.changed)
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.reviewReasons.isEmpty(), diff.reviewReasons.toString())
        val report = result.reportFile.readText()
        assertTrue("0.1.0 -> 0.2.0" in report && "274-40" in report && "274-30: name" in report, report)
        assertTrue(File(env.history, "0.1.0-to-0.2.0.md").isFile)
        // the previous files are kept as backup
        assertTrue(File(env.backup, "DE.json").readText().contains("\"0.1.0\""))
    }

    @Test fun `without apply nothing is changed`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val v2 = pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-40"))
        val registry = Registry(mapOf("0.2.0" to tarGz(v2.files)))
        val result = run(env, registry, "0.2.0", null, apply = false)
        assertFalse(result.applied)
        assertEquals(before, snapshot(env.target))
        assertTrue(result.reportFile.isFile)
        // the hash to pin is the one of the tarball, derived from the verified integrity of the registry
        assertEquals(sha256(registry.tarballByVersion.getValue("0.2.0")), result.sha256)
    }

    @Test fun `applying needs the confirmed hash`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250"), sign("1020-30", kind = "exception_modifier")).files)))
        val e = assertFailsWith<TrafficSignUpdateException> { run(env, registry, "0.2.0", null, apply = true) }
        assertTrue("SHA-256" in e.message!!)
        assertEquals(before, snapshot(env.target))
    }

    @Test fun `the same version again changes nothing`() {
        val env = Env(tmp())
        val sha = install(env, v1)
        val before = snapshot(env.target)
        val stamps = env.target.walkTopDown().filter { it.isFile }.associate { it.path to it.lastModified() }
        val registry = Registry(mapOf("0.1.0" to tarGz(v1.files)))
        val result = run(env, registry, "0.1.0", sha)
        assertFalse(result.applied)
        assertTrue(result.plan.identical)
        assertEquals(before, snapshot(env.target))
        assertEquals(stamps, env.target.walkTopDown().filter { it.isFile }.associate { it.path to it.lastModified() })
        // and twice an update to a new version: the second one is a no-op
        val v2 = pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30"), sign("274-40"))
        val registry2 = Registry(mapOf("0.2.0" to tarGz(v2.files)))
        val sha2 = sha256(registry2.tarballByVersion.getValue("0.2.0"))
        assertTrue(run(env, registry2, "0.2.0", sha2).applied)
        val afterFirst = snapshot(env.target)
        val second = run(env, registry2, "0.2.0", sha2)
        assertFalse(second.applied)
        assertEquals(afterFirst, snapshot(env.target))
    }

    @Test fun `latest is resolved by the registry`() {
        val env = Env(tmp())
        val registry = Registry(mapOf("0.1.0" to tarGz(v1.files), "0.3.0" to tarGz(pkg2(sign("250"), version = "0.3.0").files)))
        val result = run(env, registry, "latest", null, apply = false)
        assertEquals("0.3.0", result.version)
    }

    /* ----------------------------------- failure keeps the catalogue ----------------------------------- */

    private fun assertUnchanged(env: Env, before: Map<String, String>, block: () -> Unit): TrafficSignUpdateException {
        val e = assertFailsWith<TrafficSignUpdateException> { block() }
        assertEquals(before, snapshot(env.target))
        return e
    }

    @Test fun `a failed download changes nothing`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250")).files)))
        registry.failing = true
        assertTrue("offline" in assertUnchanged(env, before) { run(env, registry, "0.2.0", "x") }.message!!)
        registry.failing = false
        registry.failOnTarball = true
        assertTrue("Download" in assertUnchanged(env, before) { run(env, registry, "0.2.0", "x") }.message!!)
    }

    @Test fun `a tarball with the wrong hash is rejected`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250")).files)))
        assertUnchanged(env, before) { run(env, registry, "0.2.0", "0".repeat(64)) }
        // integrity of the registry not matching (changed tarball)
        val tampered = object {
            val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250")).files)))
        }.registry
        val fetch: (String) -> ByteArray = { url -> if (url.endsWith(".tgz")) tarGz(pkg2(sign("250"), sign("274-40")).files) else tampered.fetch(url) }
        assertFailsWith<TrafficSignUpdateException> {
            updateTrafficSignCatalogue(config(env, "0.2.0", null, apply = false), fetch, ::convert, log = {})
        }
        assertEquals(before, snapshot(env.target))
    }

    @Test fun `a tarball that is no archive is rejected`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to "garbage".encodeToByteArray()))
        val sha = sha256("garbage".encodeToByteArray())
        assertUnchanged(env, before) { run(env, registry, "0.2.0", sha) }
    }

    private fun broken(label: String, json: String, svgs: List<String>, expected: String) {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(Package("0.2.0", json, svgs).files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
        val e = assertUnchanged(env, before) { run(env, registry, "0.2.0", sha) }
        assertTrue(expected in e.message!!, "$label: ${e.message}")
        // the report exists and lists the problem
        assertTrue(expected in File(env.reports, "traffic-signs-update-0.2.0.md").readText(), label)
        assertFalse(File(env.history, "0.1.0-to-0.2.0.md").exists(), label)
    }

    @Test fun `broken or incomplete catalogues are not applied`() {
        // a rule sign that can not be imported anymore: the rules would refer to nothing
        broken("unknown kind of a rule sign", catalogue("0.2.0", sign("250"), sign("1020-30", kind = "something_new")), listOf("250", "1020-30"), "signs of the curated rules are not imported: 1020-30")
        broken("missing field of a rule sign", catalogue("0.2.0", """{"osmValuePart": "250", "signId": "250", "kind": "traffic_sign"}""", sign("1020-30", kind = "exception_modifier")), listOf("250", "1020-30"), "signs of the curated rules are not imported: 250")
        broken("missing svg of a rule sign", catalogue("0.2.0", sign("250"), sign("1020-30", kind = "exception_modifier")), listOf("250"), "signs of the curated rules are not imported: 1020-30")
        broken("image name collision", catalogue("0.2.0", sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30"), sign("274_30")), listOf("250", "1020-30", "274-30", "274_30"), "manual review")
        broken("redirect to nothing", catalogue("0.2.0", sign("250", redirects = """[{"from": "x", "to": "999"}]""")), listOf("250"), "not in the catalogue")
        broken("not json", "not json", listOf("250"), "not a JSON object")
        broken("wrong version", catalogue("0.9.9", sign("250")), listOf("250"), "version")
        broken("no signs", """{"country": "DE", "version": "0.2.0"}""", emptyList(), "list of signs")
    }

    @Test fun `a catalogue file that is missing in the package is not applied`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val files = pkg2(sign("250")).files - "package/dist/data/DE.json" + ("package/dist/data/FR.json" to catalogue("0.2.0", sign("B1"), country = "FR").encodeToByteArray())
        val registry = Registry(mapOf("0.2.0" to tarGz(files)))
        assertUnchanged(env, before) { run(env, registry, "0.2.0", sha256(registry.tarballByVersion.getValue("0.2.0"))) }
    }

    @Test fun `an existing sign whose svg cannot be converted anymore is not dropped silently`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30")).files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
        val failing = { name: String, svg: ByteArray -> if (name.endsWith("274_30")) null to "ERROR @ line 1: unsupported element" else convert(name, svg) }
        val e = assertUnchanged(env, before) {
            updateTrafficSignCatalogue(config(env, "0.2.0", sha), registry::fetch, failing, log = {})
        }
        assertTrue("not imported anymore" in e.message!! && "274-30" in e.message!!, e.message)
    }

    @Test fun `conversion warnings do not exclude a sign, errors do`() {
        val env = Env(tmp())
        val registry = Registry(mapOf("0.1.0" to tarGz(v1.files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.1.0"))
        val warning = { name: String, svg: ByteArray -> convert(name, svg).first to "In $name.svg:\nWARNING @ line 1: <title> is ignored\n" }
        val result = updateTrafficSignCatalogue(config(env, "0.1.0", sha), registry::fetch, warning, log = {})
        assertEquals(listOf("250", "1020-30", "274-30"), result.plan.country("DE")!!.imported)
        assertEquals(3, result.plan.country("DE")!!.warnings.size)
    }

    @Test fun `a failing write is rolled back`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250"), sign("274-40")).files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
        val config = config(env, "0.2.0", sha)
        val plan = planCatalogueUpdate(config, "0.2.0", sha, readTarGz(registry.tarballByVersion.getValue("0.2.0")), readCatalogueFiles(env.target), ::convert)
        assertTrue(plan.problems.isEmpty(), plan.problems.toString())
        var writes = 0
        val e = assertFailsWith<TrafficSignUpdateException> {
            applyCatalogueUpdate(env.target, plan, env.backup) { if (++writes == 3) throw java.io.IOException("disk full") }
        }
        assertTrue("restored" in e.message!!)
        assertEquals(before, snapshot(env.target))
    }

    /* ----------------------------------------- review needed ----------------------------------------- */

    @Test fun `changes of the signs of the rules need a review and are never applied silently`() {
        for ((label, changed) in listOf(
            "recommendations" to pkg2(sign("250", recommendations = """[{"geometries": ["way"], "accessTags": [{"key": "vehicle", "value": "no"}]}]"""), sign("1020-30", kind = "exception_modifier"), sign("274-30")),
            "kind" to pkg2(sign("250", kind = "exception_modifier"), sign("1020-30", kind = "exception_modifier"), sign("274-30")),
            "removed rule sign" to pkg2(sign("250"), sign("274-30")),
            "redirect at a rule sign" to pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30", redirects = """[{"from": "Anlieger", "to": "1020-30"}]""")),
        )) {
            val env = Env(tmp())
            install(env, v1)
            val before = snapshot(env.target)
            val registry = Registry(mapOf("0.2.0" to tarGz(changed.files)))
            val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
            val e = assertUnchanged(env, before) { run(env, registry, "0.2.0", sha) }
            assertTrue("manual review" in e.message!!, "$label: ${e.message}")
            // the report says what to look at, also in a dry run
            val dry = run(env, registry, "0.2.0", null, apply = false)
            assertTrue(dry.plan.diff.ruleChanges.isNotEmpty(), label)
            assertEquals(before, snapshot(env.target))
            // explicitly accepted
            assertTrue(run(env, registry, "0.2.0", sha, acceptReview = true).applied, label)
        }
    }

    @Test fun `changes to other signs do not need a review`() {
        val env = Env(tmp())
        install(env, v1)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30", recommendations = """[{"geometries": ["way"]}]"""), sign("400")).files)))
        val result = run(env, registry, "0.2.0", sha256(registry.tarballByVersion.getValue("0.2.0")))
        assertTrue(result.applied)
        assertTrue(result.plan.diff.reviewReasons.isEmpty())
    }

    @Test fun `a changed structure of the entries needs a review`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val registry = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250", extra = ""","newField": 1"""), sign("1020-30", kind = "exception_modifier"), sign("274-30")).files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
        val dry = run(env, registry, "0.2.0", null, apply = false)
        assertEquals(setOf("newField"), dry.plan.diff.addedFields)
        val e = assertUnchanged(env, before) { run(env, registry, "0.2.0", sha) }
        assertTrue("structure" in e.message!!)
        assertTrue(run(env, registry, "0.2.0", sha, acceptReview = true).applied)
    }

    @Test fun `removed signs and changes of the reading of values need a review`() {
        val env = Env(tmp())
        install(env, v1)
        val before = snapshot(env.target)
        val removed = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250"), sign("1020-30", kind = "exception_modifier")).files)))
        val e1 = assertUnchanged(env, before) { run(env, removed, "0.2.0", sha256(removed.tarballByVersion.getValue("0.2.0"))) }
        assertTrue("274-30" in e1.message!! && "removed" in e1.message!!)
        val redirect = Registry(mapOf("0.2.0" to tarGz(pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30", redirects = """[{"from": "t", "to": "274-30"}]""")).files)))
        val e2 = assertUnchanged(env, before) { run(env, redirect, "0.2.0", sha256(redirect.tarballByVersion.getValue("0.2.0"))) }
        assertTrue("redirects" in e2.message!!)
    }

    @Test fun `the first catalogue needs no comparison`() {
        val env = Env(tmp())
        val registry = Registry(mapOf("0.1.0" to tarGz(v1.files)))
        val result = run(env, registry, "0.1.0", sha256(registry.tarballByVersion.getValue("0.1.0")))
        assertTrue(result.applied)
        assertNotNull(readCatalogueFiles(env.target)["DE.json"])
        assertEquals(3, result.plan.diff.added.size)
    }

    @Test fun `an existing sign whose svg is missing now is not dropped silently`() {
        val env = Env(tmp())
        install(env, v1)
        val v2 = pkg2(sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30"))
        // one image less in the package
        val files = v2.files.filterKeys { !it.endsWith("${imageName("274-30")}.svg") }
        val registry = Registry(mapOf("0.2.0" to tarGz(files)))
        val sha = sha256(registry.tarballByVersion.getValue("0.2.0"))
        val e = assertFailsWith<TrafficSignUpdateException> { run(env, registry, "0.2.0", sha) }
        assertTrue("not imported anymore" in e.message!!, e.message)
        assertTrue(File(env.target, "DE/${imageName("274-30")}.xml").isFile)
        // accepted: the image is removed, the sign is listed as excluded in the index
        assertTrue(run(env, registry, "0.2.0", sha, acceptReview = true).applied)
        assertFalse(File(env.target, "DE/${imageName("274-30")}.xml").exists())
        assertTrue("\"274-30\"" in File(env.target, TRAFFIC_SIGNS_INDEX).readText())
        // and the next update knows that it was not imported: no review again
        val again = run(env, registry, "0.2.0", sha, apply = false)
        assertTrue(again.plan.reviewReasons.isEmpty(), again.plan.reviewReasons.toString())
    }

    /* ------------------------------------------ all countries ------------------------------------------ */

    private val fr = catalogue("0.1.0",
        sign("B9b", name = "B9b"),
        // no image in the package
        sign("A1b", name = "A1b", licence = null),
        // svg exists, but can not be converted
        sign("B1", name = "B1"),
        // ids that can not be written into a traffic_sign value without ambiguity
        sign("A4+M9z"), sign("B-9,T-22"), sign("C1;C2"), sign("none"), sign("FR:C3"), sign("("), sign(" C4"),
        // ids that can
        sign("B14[50]", signId = "B14"), sign("J1 bis"), sign("Rb-76/76A"),
        country = "FR",
    )
    private val frSvgs = listOf("B9b", "B1", "A4+M9z", "B-9,T-22", "C1;C2", "none", "FR:C3", "(", " C4", "B14[50]", "J1 bis", "Rb-76/76A")
    private val at = catalogue("0.1.0", sign("52a"), sign("52b"), country = "AT")

    private fun multiCountry(version: String = "0.1.0", others: Map<String, Pair<String, List<String>>>) =
        Package(version, catalogue(version, sign("250"), sign("1020-30", kind = "exception_modifier"), sign("274-30")), listOf("250", "1020-30", "274-30"), others = others)

    private val convertFailingB1 = { name: String, svg: ByteArray ->
        if (name == "FR_B1") ByteArray(0) to "ERROR @ line 1: <text> is not supported" else convert(name, svg)
    }

    private fun runAll(env: Env, pkg: Package, apply: Boolean = true, acceptReview: Boolean = false): TrafficSignUpdateResult {
        val registry = Registry(mapOf(pkg.version to tarGz(pkg.files)))
        val sha = sha256(registry.tarballByVersion.getValue(pkg.version))
        return updateTrafficSignCatalogue(config(env, pkg.version, sha, apply, acceptReview), registry::fetch, convertFailingB1, log = {})
    }

    @Test fun `all countries of the package are found and imported`() {
        val env = Env(tmp())
        val pkg = multiCountry(others = mapOf("FR" to (fr to frSvgs), "AT" to (at to listOf("52a", "52b"))))
        val result = runAll(env, pkg)
        assertTrue(result.applied)
        assertEquals(listOf("AT", "DE", "FR"), result.plan.countries.map { it.country })
        assertEquals(listOf("AT", "DE", "FR"), detectCountries(pkg.files))
        for (c in listOf("AT", "DE", "FR")) assertTrue(File(env.target, "$c.json").isFile, c)
        assertTrue(File(env.target, "AT/AT_52a.xml").isFile)
        val index = File(env.target, TRAFFIC_SIGNS_INDEX).readText()
        for (c in listOf("AT", "DE", "FR")) assertTrue("\"country\": \"$c\"" in index, index)
    }

    @Test fun `a country that is new in the package is imported without any change of the configuration`() {
        val env = Env(tmp())
        runAll(env, multiCountry(others = mapOf("FR" to (fr to frSvgs))))
        assertFalse(File(env.target, "AT.json").exists())
        val v2 = multiCountry(version = "0.2.0", others = mapOf(
            "FR" to (fr.replace("0.1.0", "0.2.0") to frSvgs),
            "AT" to (at.replace("0.1.0", "0.2.0") to listOf("52a", "52b")),
        ))
        val result = runAll(env, v2)
        assertTrue(result.applied)
        assertTrue(File(env.target, "AT.json").isFile)
        assertEquals(listOf("52a", "52b"), result.plan.country("AT")!!.imported)
        // a new country needs no review
        assertTrue(result.plan.reviewReasons.isEmpty(), result.plan.reviewReasons.toString())
    }

    @Test fun `signs without image, with a defective image or an unsupported id are skipped, the rest is imported`() {
        val env = Env(tmp())
        val result = runAll(env, multiCountry(others = mapOf("FR" to (fr to frSvgs))))
        val frImport = result.plan.country("FR")!!
        assertEquals(listOf("B9b", "B14[50]", "J1 bis", "Rb-76/76A"), frImport.imported)
        val reasons = frImport.excluded.associate { it.osmValuePart to it.reason }
        assertEquals(ExclusionReason.NO_IMAGE, reasons["A1b"])
        assertEquals(ExclusionReason.DEFECTIVE_IMAGE, reasons["B1"])
        for (id in listOf("A4+M9z", "B-9,T-22", "C1;C2", "none", "FR:C3", "(", " C4")) {
            assertEquals(ExclusionReason.UNSUPPORTED_ID, reasons[id], id)
        }
        assertEquals(13, frImport.catalogueSigns)
        assertEquals(8, frImport.skippedCount)
        assertEquals(1, frImport.errorCount)
        // only the images of imported signs are written
        assertTrue(File(env.target, "FR/FR_B9b.xml").isFile)
        assertFalse(File(env.target, "FR/FR_B1.xml").exists())
        assertFalse(File(env.target, "FR/FR_A4_M9z.xml").exists())
        // the catalogue itself is copied unchanged, the index lists what is left out and why
        assertEquals(fr, File(env.target, "FR.json").readText())
        val index = File(env.target, TRAFFIC_SIGNS_INDEX).readText()
        assertTrue("\"osmValuePart\": \"B1\"" in index && "\"reason\": \"defective_image\"" in index, index)
        assertTrue("\"importedSigns\": 4" in index && "\"skippedSigns\": 8" in index && "\"erroneousSigns\": 1" in index, index)
        // and the report counts them per country
        val report = result.reportFile.readText()
        assertTrue("| FR | 13 | 4 | 8 | 1 | imported |" in report, report)
        assertTrue("| DE | 3 | 3 | 0 | 0 | imported |" in report, report)
    }

    @Test fun `a new country that can not be read at all is reported and left out, the others are imported`() {
        val env = Env(tmp())
        val result = runAll(env, multiCountry(others = mapOf("FR" to ("not json" to emptyList()), "AT" to (at to listOf("52a")))))
        assertTrue(result.applied)
        assertFalse(result.plan.country("FR")!!.isImported)
        assertFalse(File(env.target, "FR.json").exists())
        assertTrue(File(env.target, "AT.json").isFile)
        assertEquals(listOf("52a"), result.plan.country("AT")!!.imported)
        assertTrue("NOT imported" in result.reportFile.readText())
    }

    @Test fun `an existing country that can not be read anymore stops the update`() {
        val env = Env(tmp())
        runAll(env, multiCountry(others = mapOf("FR" to (fr to frSvgs))))
        val before = snapshot(env.target)
        val e = assertUnchanged(env, before) { runAll(env, multiCountry(version = "0.2.0", others = mapOf("FR" to ("not json" to emptyList())))) }
        assertTrue("FR: not a JSON object" in e.message!!, e.message)
    }

    @Test fun `an existing country that is not in the package anymore needs a review`() {
        val env = Env(tmp())
        runAll(env, multiCountry(others = mapOf("FR" to (fr to frSvgs))))
        val before = snapshot(env.target)
        val e = assertUnchanged(env, before) { runAll(env, multiCountry(version = "0.2.0", others = emptyMap())) }
        assertTrue("FR: the catalogue is not in the package anymore" in e.message!!, e.message)
        assertTrue(runAll(env, multiCountry(version = "0.2.0", others = emptyMap()), acceptReview = true).applied)
        assertFalse(File(env.target, "FR.json").exists())
        assertFalse(File(env.target, "FR").exists())
    }

    @Test fun `the import is deterministic and can be repeated`() {
        val pkg = multiCountry(others = mapOf("FR" to (fr to frSvgs), "AT" to (at to listOf("52a", "52b"))))
        val env1 = Env(tmp())
        val env2 = Env(tmp())
        runAll(env1, pkg)
        // the same package with the files in a different order in the archive
        val shuffled = pkg.files.entries.reversed().associate { it.toPair() }
        val registry = Registry(mapOf(pkg.version to tarGz(shuffled)))
        updateTrafficSignCatalogue(config(env2, pkg.version, sha256(registry.tarballByVersion.getValue(pkg.version))), registry::fetch, convertFailingB1, log = {})
        // (VERSION has the hash of the tarball, which differs)
        assertEquals(snapshot(env1.target) - "VERSION", snapshot(env2.target) - "VERSION")
        fun versionWithoutHash(env: Env) = File(env.target, "VERSION").readLines().filter { !it.startsWith("sha256") }
        assertEquals(versionWithoutHash(env1), versionWithoutHash(env2))
        // again: nothing to do, nothing is touched
        val before = snapshot(env1.target)
        val again = runAll(env1, pkg)
        assertFalse(again.applied)
        assertTrue(again.plan.identical)
        assertEquals(before, snapshot(env1.target))
    }

    @Test fun `ids are checked like the parser of the app reads them`() {
        for (ok in listOf("237", "274[30]", "1042-32[PH off;Mo-Sa 8:30-11:30,16:00-18:00]", "\"Kfz-Verkehr frei\"", "Kein Winterdienst", "J1 bis", "Rb-76/76A", "274.1-20", "B14[50]", "A16[10%]")) {
            assertEquals(null, osmValuePartProblem(ok), ok)
        }
        for (bad in listOf("", " 1", "1 ", "B1+M1", "C5 + C7", "B-9,T-22", "C1;C2", "DE:250", "none", "No", "city_limit", "(", "274[30", "274[30]x", "274[]", "\"a\"b\"", "\"\"", "a=b")) {
            assertNotNull(osmValuePartProblem(bad), bad)
        }
    }

    @Test fun `the rule signs are read per country`() {
        assertEquals(mapOf("DE" to setOf("237", "1020-30"), "FR" to setOf("B9b")), parseRuleSigns(listOf("# comment", "DE:237", "DE:1020-30 # x", "", "FR:B9b")))
        assertFailsWith<IllegalArgumentException> { parseRuleSigns(listOf("237")) }
    }
}
