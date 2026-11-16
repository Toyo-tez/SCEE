import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Update the traffic sign catalogues of all countries in a version of the npm package
 *  @osm-traffic-signs/converter (https://github.com/osmberlin/osm-traffic-sign-tool) and convert the
 *  bundled SVGs to Android vector drawable XMLs, which Compose Multiplatform can render on all
 *  platforms (SVG is not supported on Android).
 *
 *  The countries are not listed anywhere: every catalogue in the package (dist/data/{CC}.json) is
 *  imported, so a country that is added to the package is imported by the next update.
 *
 *  The process is in TrafficSignCatalogueUpdate.kt (tested in buildSrc/src/test): download and verify,
 *  stage, validate, compare with the existing catalogues, write a report. Without
 *  -PtrafficSignsApply=true nothing is written (dry run). A broken download or catalogue changes
 *  nothing, a failed write is rolled back. See app/traffic-signs-update/README.md for the commands.
 *
 *  The catalogue JSON is copied unchanged. The SVGs are converted with Android's own Svg2Vector
 *  (from com.android.tools:sdk-common), which is loaded from [converterClasspath] in an isolated
 *  class loader so that it does not interfere with the Android Gradle plugin. */
open class UpdateTrafficSignsTask : DefaultTask() {
    /** a version like "0.8.0" or "latest" */
    @get:Input lateinit var version: String
    /** SHA-256 of the npm tarball of [version], so that a changed package is not used unnoticed. null:
     *  a new version, only to be looked at (dry run) */
    @get:Input @get:Optional var sha256: String? = null
    @get:Input var apply: Boolean = false
    @get:Input var acceptReview: Boolean = false
    /** the signs of the curated rules, one "CC:osmValuePart" per line (see TrafficSignRuleCatalogueTest) */
    @get:InputFile lateinit var ruleSignsFile: File
    @get:Classpath lateinit var converterClasspath: FileCollection
    @get:OutputDirectory lateinit var targetDir: File
    @get:Internal lateinit var reportDir: File
    @get:Internal lateinit var backupDir: File
    @get:Internal lateinit var historyDir: File

    init {
        // a manual task with side effects on files in the project: always run when asked
        outputs.upToDateWhen { false }
    }

    @TaskAction fun run() {
        val ruleSigns = parseRuleSigns(ruleSignsFile.readLines())
        val convert = svg2VectorConverter()
        val config = TrafficSignUpdateConfig(
            version = version, sha256 = sha256, targetDir = targetDir,
            backupDir = backupDir, reportDir = reportDir, historyDir = historyDir,
            ruleSigns = ruleSigns, apply = apply, acceptReview = acceptReview,
        )
        val tmpDir = Files.createTempDirectory("traffic-signs")
        try {
            updateTrafficSignCatalogue(
                config,
                fetch = { url ->
                    val connection = URI(url).toURL().openConnection()
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 120_000
                    connection.getInputStream().use { it.readBytes() }
                },
                convertSvg = { name, svg ->
                    val tmpFile = tmpDir.resolve("$name.svg")
                    try {
                        Files.write(tmpFile, svg)
                        val output = ByteArrayOutputStream()
                        val errors = convert(tmpFile, output)
                        output.toByteArray() to errors
                    } finally {
                        Files.deleteIfExists(tmpFile)
                    }
                },
                log = { logger.lifecycle(it) },
            )
        } catch (e: TrafficSignUpdateException) {
            throw GradleException(e.message ?: "update failed", e)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** returns a function that converts the given SVG file to vector drawable XML written into the
     *  output stream and returns the error log (empty if successful) */
    private fun svg2VectorConverter(): (Path, OutputStream) -> String {
        val classLoader = URLClassLoader(
            converterClasspath.files.map { it.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader()
        )
        val svg2Vector = classLoader.loadClass("com.android.ide.common.vectordrawable.Svg2Vector")
        val parseSvgToXml = svg2Vector.getMethod("parseSvgToXml", Path::class.java, OutputStream::class.java)
        return { path, output -> parseSvgToXml.invoke(null, path, output) as String? ?: "" }
    }
}

/** Minimal reader for (pax) ustar archives as published by npm. Returns file path to content */
internal fun readTarGz(bytes: ByteArray): Map<String, ByteArray> {
    val tar = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
    val result = LinkedHashMap<String, ByteArray>()
    var offset = 0
    var paxPath: String? = null
    while (offset + 512 <= tar.size) {
        val header = tar.copyOfRange(offset, offset + 512)
        if (header.all { it == 0.toByte() }) break
        val name = header.readString(0, 100)
        val size = header.readString(124, 12).trim().ifEmpty { "0" }.toLong(8).toInt()
        val type = header[156].toInt().toChar()
        val prefix = header.readString(345, 155)
        val contentStart = offset + 512
        val content = tar.copyOfRange(contentStart, contentStart + size)
        when (type) {
            'x' -> {
                paxPath = content.decodeToString().lineSequence()
                    .map { it.substringAfter(' ') }
                    .firstOrNull { it.startsWith("path=") }
                    ?.removePrefix("path=")
            }
            '0', '\u0000' -> {
                val path = paxPath ?: if (prefix.isEmpty()) name else "$prefix/$name"
                result[path] = content
                paxPath = null
            }
            else -> paxPath = null
        }
        offset = contentStart + (size + 511) / 512 * 512
    }
    return result
}

private fun ByteArray.readString(start: Int, length: Int): String {
    val end = (start until start + length).firstOrNull { this[it] == 0.toByte() } ?: (start + length)
    return copyOfRange(start, end).decodeToString()
}
