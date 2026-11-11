package io.github.xororz.localdream.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.data.*
import io.github.xororz.localdream.ui.design.*
import io.github.xororz.localdream.ui.screens.*
import io.github.xororz.localdream.ui.theme.LocalDreamTheme
import io.github.xororz.localdream.ui.theme.LocalThemeController
import io.github.xororz.localdream.ui.theme.ThemeController
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeFontLayoutTest(private val scale: Float) {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font={0}")
        fun fonts() = listOf(arrayOf(1.0f), arrayOf(1.3f), arrayOf(1.5f), arrayOf(2.0f))
    }

    private fun show(dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        val application = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(application.resources, compose.activity.resources).distinct().forEach { resources ->
            val config = android.content.res.Configuration(resources.configuration).apply { fontScale = scale }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
        compose.setContent {
            LocalDreamTheme(ThemeState(dynamicColor = false, darkMode = if (dark) DarkModePreference.DARK else DarkModePreference.LIGHT)) {
                val density = LocalDensity.current
                val controller = androidx.compose.runtime.remember { ThemeController(ThemeState(dynamicColor = false)) {} }
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalThemeController provides controller) { content() }
            }
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val output = File(System.getProperty("ui.screenshots.dir", "/tmp/miuix-screenshots"), "$name-$scale.png")
        output.parentFile?.mkdirs()
        // PixelCopy waits for a hardware frame unavailable in a JVM window.
        // Draw the actual Activity view with Robolectric's native Android Canvas instead.
        compose.runOnIdle {
            val view = if (dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView ?: compose.activity.window.decorView else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun buttonsRemainSingleLineAndCompact() {
        var lines = 0
        var clicks = 0
        show {
            DreamPage("导入模型") { padding ->
                PageColumn(padding) {
                    Text("flux-2-klein-4b-very-long-model-name-Q4_K_M.gguf")
                    Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("action")) {
                        Text("选择文件后继续下一步导入模型", onTextLayout = { lines = it.lineCount })
                    }
                    Text("完整文件名与说明独立显示，不挤进按钮。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val action = compose.onNodeWithTag("action")
        action.assertHeightIsAtLeast(48.dp).performClick()
        val height = with(compose.density) { action.fetchSemanticsNode().boundsInRoot.height.toDp() }
        assertTrue("Button grew beyond one line: $height", height <= 64.dp)
        compose.runOnIdle {
            assertEquals(1, lines)
            assertEquals(1, clicks)
        }
        capture("compact-actions")
    }

    @Test fun familySelectionAndVisionFilesRemainReachable() {
        var selected = DitImportKind.KLEIN
        show { DreamPage("导入模型") { padding -> PageColumn(padding) { ImportFamilyContent(selected) { selected = it } } } }
        compose.onNodeWithText("Krea 2 Turbo").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DitImportKind.KREA2, selected) }
        capture("import-families")
    }

    @Test fun filenamesWrapOutsideTheButtons() {
        val filename = "qwen-image-2.1-turbo-very-long-vision-projector-name-Q8_0.gguf"
        var picked: DitImportComponent? = null
        show {
            DreamPage("导入模型") { padding ->
                PageColumn(padding) {
                    ImportFilesContent(DitImportKind.QWEN21, mapOf(DitImportComponent.VISION to ImportFileSummary(filename, "1.2 GB"))) { picked = it }
                }
            }
        }
        compose.onNodeWithText(filename).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("重新选择文件").onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DitImportComponent.VISION, picked) }
        capture("import-vision")
    }

    @Test fun allGenerationParametersFitWithoutScrolling() {
        show { SampleWorkspace() }
        listOf("width", "height", "steps", "cfg", "scheduler", "seed", "batch", "denoise").forEach { id ->
            val cell = compose.onNodeWithTag("parameter-$id")
            cell.assertIsDisplayed()
            val height = with(compose.density) { cell.fetchSemanticsNode().boundsInRoot.height.toDp() }
            assertTrue("Parameter $id is too tall: $height", height <= 64.dp)
        }
        compose.onNodeWithTag("generation-workspace").assert(
            SemanticsMatcher("page has no scroll action") {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)
            },
        )
        compose.onNodeWithText("生成").assertIsDisplayed()
        capture("generation-form")
    }

    @Test fun parameterValuesEditInline() {
        var changedWidth = 512f
        show { SampleWorkspace(onWidth = { changedWidth = it }) }
        compose.onNodeWithTag("parameter-width").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("parameter-editor-card"))).performTextReplacement("768")
        compose.runOnIdle { assertEquals(768f, changedWidth) }
        compose.onNodeWithContentDescription("确定").performClick()
        compose.onNodeWithText("portrait in a quiet garden").assertIsDisplayed()
        compose.onNodeWithTag("parameter-scheduler").performClick()
        compose.onNodeWithText("Euler A").assertIsDisplayed().performClick()
        capture("workspace-parameters")
    }

    @Test fun typingCollapsesControlsAndNegativeInputStaysCompact() {
        show { SampleWorkspace() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("parameter-width").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performClick().performTextInput(", soft light")
        compose.onNodeWithTag("parameter-width").assertDoesNotExist()
        compose.onNodeWithTag("negative-prompt-toggle").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        val editors = compose.onAllNodes(hasSetTextAction())
        editors.onLast().performClick().performTextInput("blur")
        val negativeHeight = compose.onNodeWithTag("negative-prompt-editor").fetchSemanticsNode().boundsInRoot.height
        assertEquals(with(compose.density) { 72.dp.toPx() }, negativeHeight, 1f)
        val negativeBounds = compose.onNodeWithTag("negative-prompt-editor").fetchSemanticsNode().boundsInRoot
        val positiveBounds = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(negativeBounds.top >= positiveBounds.bottom)
        capture("generation-negative-expanded")
        compose.onNodeWithTag("negative-prompt-toggle").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("negative-prompt-toggle").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().assertTextContains("blur", substring = true)
        compose.onNodeWithTag("parameter-panel-toggle").performClick()
        compose.onNodeWithTag("parameter-width").assertIsDisplayed()
        compose.onNodeWithTag("negative-prompt-editor").assertDoesNotExist()
    }

    @Test fun longPressThenHorizontalDragChangesValueWithoutOpeningEditor() {
        var width = 512f
        show { SampleWorkspace(onWidth = { width = it }) }
        compose.onNodeWithTag("parameter-width").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(84f, 0f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertTrue("Long-press dragging did not change width: $width", width > 512f) }
        compose.onNodeWithTag("parameter-editor-card").assertDoesNotExist()
        compose.onNodeWithTag("parameter-width").performClick()
        compose.onNodeWithTag("parameter-editor-card").assertIsDisplayed()
        capture("parameter-small-card", dialog = true)
    }

    @Test fun imageInputIsAbovePositivePromptAndMenuIsInToolbar() {
        show { SampleWorkspace() }
        val image = compose.onNodeWithTag("workspace-image-inputs").fetchSemanticsNode().boundsInRoot
        val positive = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(image.bottom <= positive.top)
        val menu = compose.onNodeWithTag("generation-menu-button").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.bottom <= image.top)
        compose.onNodeWithTag("generation-menu-button").performClick()
        compose.onNodeWithTag("generation-menu-popup").assertIsDisplayed()
    }

    @Test fun negativeSectionIsOutsideParameterPanelAndImageActionsAreIcons() {
        show { SampleWorkspace() }
        compose.onNodeWithTag("negative-prompt-section").assert(!hasAnyAncestor(hasTestTag("parameter-panel")))
        compose.onNodeWithTag("negative-prompt-toggle").assert(!hasAnyAncestor(hasTestTag("parameter-panel")))
        val negative = compose.onNodeWithTag("negative-prompt-section").fetchSemanticsNode().boundsInRoot
        val positive = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        val parameters = compose.onNodeWithTag("parameter-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Negative prompt is not directly below the positive prompt", negative.top >= positive.bottom)
        assertTrue("Negative prompt was placed below the parameters", negative.bottom <= parameters.top)
        compose.onNodeWithTag("image-input-base").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("image-input-reference").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("底图").assertDoesNotExist()
        compose.onNodeWithText("参考").assertDoesNotExist()
        capture("generation-independent-negative")
    }

    @Test fun modelTabsFillTheAvailableRow() {
        show { ModelFormatTabs(0, {}) }
        val first = compose.onNodeWithTag("model-format-0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("model-format-2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, last.width, 2f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 20.dp.toPx() }, root.right - last.right, 2f)
    }

    @androidx.compose.runtime.Composable
    private fun SampleWorkspace(onWidth: (Float) -> Unit = {}) {
        var width by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(512f) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        val repository = androidx.compose.runtime.remember { TagAutocompleteRepository.getInstance(context) }
        val positive = androidx.compose.runtime.remember { PromptFieldController(scope, repository, 8, { emptyList() }).apply { update(androidx.compose.ui.text.input.TextFieldValue("portrait in a quiet garden")) } }
        val negative = androidx.compose.runtime.remember { PromptFieldController(scope, repository, 8, { emptyList() }) }
        DreamPage("FLUX.2 Klein", actions = { GenerationToolbarMenu("Klein", "说明", {}, {}, {}) }, bottomBar = {
            Column {
                ActionDock(navigationInsets = false) { Button({}, Modifier.fillMaxWidth()) { Text("生成") } }
                WorkspaceNavigation(0, {})
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                GenerationWorkspace(
                    isSdxl = false, isDit = true, runOnCpu = false, useImg2img = true, isRunning = false,
                    aspectRatio = "1:1", availableResolutions = emptyList(), currentWidth = width.toInt(), currentHeight = 512,
                    scheduler = "euler", steps = 4f, cfg = 1f, useOpenCL = false, batchCounts = 1,
                    denoiseStrength = .6f, seed = "", returnedSeed = 123L,
                    onAspectRatioSelected = {}, onCustomAspectRatioClick = {}, onResolutionSelected = {},
                    onDitWidthChange = {
                        width = it
                        onWidth(it)
                    }, onSchedulerChange = {}, onStepsChange = {}, onCfgChange = {}, onSizeChange = {},
                    onCpuSelected = {}, onGpuSelected = {}, onBatchCountsChange = {}, onDenoiseStrengthChange = {},
                    onSeedChange = {}, onUseLastSeed = {},
                    promptFocused = positive.isFocused || negative.isFocused,
                    positive = { ControlledPromptTagTextField(positive, false, {}, fixedViewport = true) },
                    negative = { ControlledPromptTagTextField(negative, false, {}, fixedViewport = true) },
                    inputs = { _ -> ImageInputActions({}, {}) },
                )
            }
        }
    }

    @Test fun resultDetailsCanBeReachedWithTouchScrolling() {
        var saved = false
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 140, 120)) }
        val params = GenerationParameters(4, 1f, 1711452183L, "a portrait", "", "146.4 seconds", 512, 512, false)
        show {
            DreamPage("Klein", bottomBar = { WorkspaceNavigation(1, {}) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    ModelRunResultPage(bitmap, 1, params, emptyList(), false, true, true, true, true, {}, {}, false, {}, {}, {}, {}, { saved = it === bitmap }, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertTrue(saved) }
        repeat(3) { compose.onNodeWithTag("generation-result-scroll").performTouchInput { swipeUp() } }
        val info = compose.onNodeWithTag("result-parameters").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("结果").fetchSemanticsNode().boundsInRoot
        assertTrue("Result details remain under the footer", info.bottom <= footer.top)
        capture("result-details")
    }

    @Test fun settingDescriptionDoesNotInflateTheSwitchRow() {
        var checked = false
        show {
            DreamPage("设置") { padding ->
                PageColumn(padding) {
                    Box(Modifier.testTag("setting")) {
                        SwitchSettingRow("CPU 解码", "这是较长的说明，只有主动展开时才显示。", checked, { checked = it })
                    }
                }
            }
        }
        val setting = compose.onNodeWithTag("setting")
        val height = with(compose.density) { setting.fetchSemanticsNode().boundsInRoot.height.toDp() }
        assertTrue("Switch row is too tall: $height", height <= 76.dp)
        capture("settings-compact")
        compose.onNodeWithContentDescription("查看说明").performClick()
        compose.onNodeWithText("这是较长的说明，只有主动展开时才显示。").assertIsDisplayed()
    }

    @Test fun darkLibraryKeepsModelNamesCompact() {
        val name = "FLUX.2 Klein 4B very-long-custom-model-name"
        var opened = false
        show(dark = true) {
            DreamPage("模型库") { padding ->
                PageColumn(padding) {
                    ModelCard(Model("klein", name, "长模型说明应留在详情中，卡片维持简洁。", "", approximateSize = "3.2 GB", isDownloaded = true, ditKind = "klein"), false, false, { opened = true }, {})
                }
            }
        }
        val title = compose.onNodeWithText(name, useUnmergedTree = true)
        val height = with(compose.density) { title.fetchSemanticsNode().boundsInRoot.height.toDp() }
        assertTrue("Model name wrapped into a tall card: $height", height <= 48.dp)
        capture("library-dark")
        title.performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
    private fun openLibrary(): List<File> {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("is_first_launch", false).putInt("last_viewed_page", 0).commit()
        val dirs = (0..17).map { index ->
            File(ModelStorage.modelsDir(context), "ui_scroll_%02d".format(index)).apply {
                mkdirs()
                File(this, "finished").writeText("")
            }
        }
        kotlinx.coroutines.runBlocking { ModelRepository.getInstance(context).refreshAllModels() }
        show { ModelListScreen(androidx.navigation.compose.rememberNavController()) }
        compose.onNodeWithTag("model-library-list").assertIsDisplayed()
        return dirs
    }

    @Test fun actualModelLibraryScrollsWithFingerDragging() {
        val dirs = openLibrary()
        try {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val last = ModelRepository.getInstance(context).models.last { it.runOnCpu && !it.isDit }.name
            val before = compose.onNodeWithTag("model-library-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            repeat(10) { compose.onNodeWithTag("model-library-list").performTouchInput { swipeUp() } }
            val after = compose.onNodeWithTag("model-library-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Finger swipes did not advance the list", after > before)
            capture("model-library-scrolled")
            compose.onNodeWithText(last).assertIsDisplayed()
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    @Test fun libraryOverflowStaysAnchoredToTopRight() {
        val dirs = openLibrary()
        try {
            compose.onNodeWithTag("model-library-menu-button").performClick()
            compose.onNodeWithTag("model-library-menu-popup").assertIsDisplayed()
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
            val popup = windows.last { it !== compose.activity.window.decorView }
            val params = popup.layoutParams as android.view.WindowManager.LayoutParams
            val anchor = compose.onNodeWithTag("model-library-menu-button").fetchSemanticsNode().boundsInRoot
            assertTrue("Popup detached from its right-hand anchor: x=${params.x}, width=${popup.width}, anchor=$anchor", kotlin.math.abs(params.x + popup.width - anchor.right) <= with(compose.density) { 12.dp.toPx() })
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }
}
