package stringfit

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import java.io.File

/**
 * Renders every @Preview and records, for each text node, how much room
 * the string actually had. Output feeds `./gradlew stringFitReport`.
 *
 * Installed by the StringFit Gradle plugin. Edit freely -- it will not be
 * overwritten unless you pass --overwrite.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w800dp-h1200dp-xhdpi",
    // Use the stock Application. Your own Application#onCreate typically
    // starts DI and loads native libraries that cannot run on the JVM,
    // and preview rendering needs none of it. If your previews genuinely
    // require DI, point this at a test Application that provides it.
    application = android.app.Application::class,
)
class StringFitHarnessTest(
    private val preview: ComposablePreview<AndroidPreviewInfo>,
    private val locale: String,
) {

    companion object {
        private val outDir = File("build/stringfit/sites").apply { mkdirs() }

        /**
         * Locales to measure, written by `stringFitPrepare` from your
         * `stringFit { locales }` config. An empty entry means the
         * source locale, which is always measured as the baseline.
         */
        private fun locales(): List<String> {
            val f = File("build/stringfit/locales.txt")
            val configured = if (f.isFile) {
                f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            return listOf("") + configured
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> {
            val previews = AndroidComposablePreviewScanner()
                .scanPackageTrees("dev.stringfit.sample")
                // Preview functions are idiomatically private; without
                // this the scanner silently returns an empty list.
                .includePrivatePreviews()
                .getPreviews()
            return previews.flatMap { p -> locales().map { l -> arrayOf<Any>(p, l) } }
        }

        // One file per preview: @Parameters is re-invoked once per
        // Robolectric sandbox, so a shared sink gets truncated.
        fun write(label: String, rows: List<String>) {
            val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(150)
            outDir.resolve("$safe.tsv").writeText(rows.joinToString("\n"))
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val argToken = Regex("%(\\d+\\$)?[sd]")

    private data class Entry(val name: String, val value: String, val regex: Regex?)

    /**
     * Every module's R.string class, resolved by name.
     *
     * With non-transitive R classes a library's strings are absent from
     * the app's R, so a preview in :app that renders a string from
     * :core-ui could not be named without this. Classes that are not on
     * the classpath are skipped.
     */
    private fun rStringClasses(): List<Class<*>> =
        listOf("dev.stringfit.sample.R", "dev.stringfit.core.R").mapNotNull { name ->
            runCatching { Class.forName(name + '$' + "string") }.getOrNull()
        }

    private fun catalog(): List<Entry> {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        return rStringClasses().flatMap { it.fields.asList() }.mapNotNull { f ->
            val value = runCatching { res.getString(f.getInt(null)) }.getOrNull()
                ?: return@mapNotNull null
            val regex = if (!argToken.containsMatchIn(value)) null else Regex(
                "^" + argToken.split(value)
                    .joinToString("(.*)") { Regex.escape(it) } + "$"
            )
            Entry(f.name, value, regex)
        }
    }

    private fun resolve(text: String, catalog: List<Entry>): String? =
        catalog.firstOrNull { it.regex == null && it.value == text }?.name
            ?: catalog.firstOrNull { it.regex?.matches(text) == true }?.name

    private fun collect(node: SemanticsNode, out: MutableList<List<Any>>) {
        val texts = node.config.getOrNull(SemanticsProperties.Text)
        if (!texts.isNullOrEmpty()) {
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action?.invoke(results)
            val r = results.firstOrNull()
            if (r != null) {
                val li = r.layoutInput
                val m = TextMeasurer(li.fontFamilyResolver, li.density, li.layoutDirection)
                // Compose's own overflow flags are unusable here:
                // didOverflowWidth is inverted and isLineEllipsized never
                // fires. Re-measuring is the only reliable ground truth.
                val intrinsic = m.measure(
                    text = li.text, style = li.style, overflow = TextOverflow.Clip,
                    softWrap = false, maxLines = 1, constraints = Constraints(),
                ).size.width
                val linesNeeded = m.measure(
                    text = li.text, style = li.style, overflow = TextOverflow.Clip,
                    softWrap = li.softWrap, maxLines = Int.MAX_VALUE,
                    constraints = Constraints(maxWidth = li.constraints.maxWidth),
                ).lineCount
                val b = node.boundsInRoot
                out += listOf(
                    texts.joinToString(" ") { it.text },
                    li.constraints.maxWidth, intrinsic, li.maxLines,
                    linesNeeded, r.size.width,
                    b.left.toInt(), b.right.toInt(),
                )
            }
        }
        node.children.forEach { collect(it, out) }
    }

    @Test
    fun measure() {
        val info = preview.previewInfo
        val widthDp = if (info.widthDp > 0) info.widthDp else 360
        val fontScale = if (info.fontScale > 0f) info.fontScale else 1f
        val label = "${preview.declaringClass}.${preview.methodName}[${info.name}]"

        // Switching the qualifier swaps the string resources AND the
        // layout direction, so RTL locales exercise real mirroring.
        // "rtl-probe" keeps the source text and flips only the layout
        // direction, which is what makes a width change attributable
        // to mirroring rather than to a different translation.
        val probe = locale == "rtl-probe"
        if (locale.isNotEmpty() && !probe) {
            RuntimeEnvironment.setQualifiers("+" + locale)
        }

        val raw = mutableListOf<List<Any>>()
        val error = runCatching {
            rule.setContent {
                val dir = if (probe) LayoutDirection.Rtl else LocalLayoutDirection.current
                CompositionLocalProvider(LocalLayoutDirection provides dir) {
                    Box(Modifier.width(widthDp.dp)) { preview() }
                }
            }
            rule.waitForIdle()
            // Dialogs and bottom sheets compose into their OWN root
            // window; onRoot() misses every one of them.
            rule.onAllNodes(isRoot()).fetchSemanticsNodes()
                .forEach { collect(it, raw) }
        }.exceptionOrNull()

        if (error != null) {
            println("STRINGFIT_SKIP $label [$locale] :: ${error::class.simpleName}: ${error.message?.take(120)}")
            return
        }

        val catalog = catalog()
        write(
            label + "_" + (locale.ifEmpty { "source" }),
            raw.mapNotNull { row ->
                val name = resolve(row[0] as String, catalog) ?: return@mapNotNull null
                listOf(
                    name, label, row[1], row[2], row[3], row[4], row[5],
                    widthDp, fontScale, locale, row[6], row[7],
                ).joinToString("\t")
            },
        )
    }
}