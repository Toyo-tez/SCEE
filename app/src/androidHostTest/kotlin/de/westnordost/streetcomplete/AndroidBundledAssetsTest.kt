package de.westnordost.streetcomplete

import android.app.Application
import de.westnordost.streetcomplete.util.BundledAssetsContract
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidBundledAssetsTest : BundledAssetsContract() {
    private val composeFilesDir = "composeResources/de.westnordost.streetcomplete.resources/files"
    private val expectedText = "UTF-8 漢字🌍\n"

    // Robolectric uses android.app.Application without the app's manifest ContentProvider, so Compose's
    // DefaultAndroidResourceReader may not get a context unless we bind it explicitly.
    @OptIn(ExperimentalResourceApi::class)
    @Before fun bindComposeResourceContext() {
        setResourceReaderAndroidContext(RuntimeEnvironment.getApplication())
    }

    /**
     * Verifies that non-ASCII Compose file names survive the androidHostTest asset merge.
     *
     * Robolectric's [org.robolectric.shadows.ShadowArscAssetManager10] cannot [android.content.res.AssetManager.open]
     * paths that contain non-ASCII characters (FileNotFoundException although [AssetManager.list] shows the file).
     * Runtime [android.content.res.AssetManager] on devices does not share that limitation; [DesktopBundledAssetsTest]
     * covers [de.westnordost.streetcomplete.util.readBundledText] / [Res.readBytes] for this fixture.
     */
    @Test override fun `Unicode filenames and UTF8 fixture contents are supported`() = runBlocking {
        val assets = RuntimeEnvironment.getApplication().assets
        val dir = "$composeFilesDir/m4-fixtures"
        val names = assets.list(dir)?.toSet().orEmpty()
        assertEquals(setOf("ä.txt"), names, "merged assets in $dir")
        assertFailsWith<FileNotFoundException> { assets.open("$dir/ä.txt").close() }

        val merged = mergedHostTestUnicodeFixture()
        assertEquals(expectedText, merged.readText(Charsets.UTF_8))
        assertTrue(merged.name.any { it.code > 127 })
    }

    private fun mergedHostTestUnicodeFixture(): File {
        val root = File("build/intermediates/assets/androidHostTest")
        val matches = root.walkTopDown().filter { it.isFile && it.parentFile?.name == "m4-fixtures" && it.name == "ä.txt" }.toList()
        assertEquals(1, matches.size, "expected one merged m4-fixtures/ä.txt under $root")
        return matches.single()
    }
}