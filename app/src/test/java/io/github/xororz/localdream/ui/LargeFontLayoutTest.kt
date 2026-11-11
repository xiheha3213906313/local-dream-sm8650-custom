package io.github.xororz.localdream.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
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

    private fun show(dark: Boolean = false, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback? = null, imeVisible: (() -> Boolean)? = null, content: @androidx.compose.runtime.Composable () -> Unit) {
        val application = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(application.resources, compose.activity.resources).distinct().forEach { resources ->
            val config = android.content.res.Configuration(resources.configuration).apply { fontScale = scale }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
        compose.setContent {
            CompositionLocalProvider(LocalImeVisibleOverride provides imeVisible?.invoke()) {
                LocalDreamTheme(ThemeState(dynamicColor = false, darkMode = if (dark) DarkModePreference.DARK else DarkModePreference.LIGHT)) {
                    val density = LocalDensity.current
                    val controller = androidx.compose.runtime.remember { ThemeController(ThemeState(dynamicColor = false)) {} }
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalThemeController provides controller, androidx.compose.ui.platform.LocalHapticFeedback provides (haptics ?: androidx.compose.ui.platform.LocalHapticFeedback.current)) { content() }
                }
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
        compose.onNodeWithText("确定").performClick()
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
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("negative-prompt-editor"))).performClick().performTextInput("blur")
        val negativeHeight = compose.onNodeWithTag("negative-prompt-editor").fetchSemanticsNode().boundsInRoot.height
        assertEquals(with(compose.density) { 72.dp.toPx() }, negativeHeight, 1f)
        val negativeBounds = compose.onNodeWithTag("negative-prompt-editor").fetchSemanticsNode().boundsInRoot
        val positiveBounds = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(negativeBounds.top >= positiveBounds.bottom)
        capture("generation-negative-expanded")
        compose.onNodeWithTag("negative-prompt-toggle").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("negative-prompt-toggle").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("negative-prompt-editor"))).assertTextContains("blur", substring = true)
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
        capture("parameter-small-card")
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
        compose.onNodeWithText("底图").assertIsDisplayed()
        compose.onNodeWithText("参考").assertIsDisplayed()
        capture("generation-independent-negative")
    }

    @Test fun promptHeightIsCappedAndNegativeFollowsWithoutBottomSpacer() {
        show(dark = true) { SampleWorkspace() }
        compose.onNodeWithTag("parameter-panel-toggle").performClick()
        val positive = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        val negative = compose.onNodeWithTag("negative-prompt-section").fetchSemanticsNode().boundsInRoot
        assertTrue(positive.height <= with(compose.density) { 192.dp.toPx() })
        assertTrue("Empty space pushed the negative prompt down", negative.top - positive.bottom <= with(compose.density) { 8.dp.toPx() })
        capture("dark-prompts-follow")
    }

    @Test fun samplerHasSelectedButtonsAndSeedHasDistinctModes() {
        show(dark = true) { SampleWorkspace() }
        compose.onNodeWithTag("parameter-scheduler").performClick()
        compose.onNodeWithText("Euler").assertIsSelected()
        compose.onNodeWithText("Euler A").assertHasClickAction().performClick().assertIsSelected()
        compose.onNodeWithText("Euler").assertIsNotSelected()
        capture("dark-sampler-buttons")
        compose.onNodeWithContentDescription("返回提示词").performClick()
        compose.onNodeWithTag("parameter-seed").performClick()
        compose.onNodeWithTag("seed-random-mode").assertIsSelected()
        compose.onNodeWithTag("parameter-number-input").assertDoesNotExist()
        capture("dark-seed-random")
        compose.onNodeWithTag("seed-fixed-mode").performClick().assertIsSelected()
        compose.onNodeWithTag("parameter-number-input").performTextClearance()
        compose.onNodeWithTag("parameter-number-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithTag("seed-fixed-mode").assertIsSelected()
        compose.onNodeWithTag("parameter-number-input").performTextReplacement("987654321")
        compose.onNodeWithTag("parameter-number-input").assertTextContains("987654321")
        capture("dark-seed-fixed")
    }

    @Test fun parameterCardEdgesAnimateFromTileAndReturnToTileInSameWindow() {
        show(dark = true) { SampleWorkspace() }
        val tile = compose.onNodeWithTag("parameter-width").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("parameter-width").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val start = compose.onNodeWithTag("parameter-editor-card").fetchSemanticsNode().boundsInRoot
        assertEquals(tile.width, start.width, 3f)
        assertEquals(tile.height, start.height, 3f)
        compose.mainClock.advanceTimeBy(64)
        val middle = compose.onNodeWithTag("parameter-editor-card").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.width > start.width)
        assertTrue(middle.height > start.height)
        capture("dark-parameter-morph-middle")
        compose.mainClock.advanceTimeBy(400)
        val end = compose.onNodeWithTag("parameter-editor-card").fetchSemanticsNode().boundsInRoot
        assertTrue(end.width > middle.width)
        assertTrue(end.height > middle.height)
        assertNull("Parameter editor created a separate Dialog", org.robolectric.shadows.ShadowDialog.getLatestDialog())
        capture("dark-number-input")
        compose.onNodeWithContentDescription("返回提示词").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(64)
        val closing = compose.onNodeWithTag("parameter-editor-card").fetchSemanticsNode().boundsInRoot
        assertTrue(closing.width < end.width)
        assertTrue(closing.width > tile.width)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("parameter-editor-card").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun longPressShowsValueAboveTheFingerUntilRelease() {
        show(dark = true) { SampleWorkspace() }
        val tile = compose.onNodeWithTag("parameter-width").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("parameter-width").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(48f, 0f), delayMillis = 100)
        }
        val lifted = compose.onNodeWithTag("parameter-drag-preview").fetchSemanticsNode().boundsInRoot
        assertEquals("The original tile position became empty", tile.bottom, lifted.bottom, 1f)
        val value = compose.onNodeWithTag("parameter-drag-value").fetchSemanticsNode().boundsInRoot
        assertTrue("Dragged value is obscured by the touch point", value.bottom < tile.center.y)
        capture("dark-drag-lift")
        compose.onNodeWithTag("parameter-width").performTouchInput { up() }
        compose.onNodeWithTag("parameter-drag-preview").assertDoesNotExist()
        compose.onNodeWithTag("parameter-editor-card").assertDoesNotExist()
    }

    @Test fun imageEditingToolsUseOneLabeledActionRow() {
        var clicked = ""
        show(dark = true) { ImageInputActions({ clicked = "base" }, { clicked = "reference" }, onDraw = { clicked = "draw" }, onMask = { clicked = "mask" }) }
        val first = compose.onNodeWithTag("image-input-base").getUnclippedBoundsInRoot()
        listOf("base", "reference", "draw", "mask").forEach { id ->
            val action = compose.onNodeWithTag("image-input-$id")
            val bounds = action.getUnclippedBoundsInRoot()
            assertEquals(first.top, bounds.top)
            assertEquals(first.bottom, bounds.bottom)
            action.performScrollTo().performClick()
            compose.runOnIdle { assertEquals(id, clicked) }
        }
    }

    @Test fun draggingRuntimeBackToStartingPointRestoresStartingOption() {
        show(dark = true) { SampleWorkspace(cpuRuntime = true) }
        compose.onNodeWithTag("parameter-runtime").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(36f, 0f), delayMillis = 100)
        }
        compose.onNode(hasText("GPU") and hasAnyAncestor(hasTestTag("parameter-drag-preview"))).assertIsDisplayed()
        compose.onNodeWithTag("parameter-runtime").performTouchInput { moveBy(Offset(-36f, 0f), delayMillis = 100) }
        compose.onNode(hasText("CPU") and hasAnyAncestor(hasTestTag("parameter-drag-preview"))).assertIsDisplayed()
        compose.onNodeWithTag("parameter-runtime").performTouchInput { up() }
        compose.onNodeWithTag("parameter-runtime").assertTextContains("CPU")
    }

    @Test fun widthHeightLockLinksDimensionsAndUnlockRestoresIndependentEditing() {
        show { SampleWorkspace() }
        compose.onNodeWithTag("aspect-ratio-lock").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("parameter-width").performClick()
        compose.onNodeWithTag("parameter-number-input").performTextReplacement("768")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithTag("parameter-width").assertTextContains("768")
        compose.onNodeWithTag("parameter-height").assertTextContains("768")
        compose.onNodeWithTag("aspect-ratio-lock").performClick().assertIsOff()
        compose.onNodeWithTag("parameter-height").performClick()
        compose.onNodeWithTag("parameter-number-input").performTextReplacement("1024")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithTag("parameter-width").assertTextContains("768")
        compose.onNodeWithTag("parameter-height").assertTextContains("1024")
        capture("v014-dimensions-lock")
    }

    @Test fun dragProvidesTickHapticsAndRetainsOriginalBottomEdge() {
        val feedback = mutableListOf<androidx.compose.ui.hapticfeedback.HapticFeedbackType>()
        show(
            haptics = object : androidx.compose.ui.hapticfeedback.HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {
                    feedback += hapticFeedbackType
                }
            },
        ) { SampleWorkspace() }
        val tile = compose.onNodeWithTag("parameter-width").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("parameter-width").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(36f, 0f), delayMillis = 100)
        }
        compose.onNodeWithTag("parameter-width").performTouchInput { moveBy(Offset(12f, 0f), delayMillis = 100) }
        val expanded = compose.onNodeWithTag("parameter-drag-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(tile.bottom, expanded.bottom, 1f)
        assertTrue(expanded.height > tile.height)
        assertTrue(feedback.count { it == androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove } >= 2)
        capture("v014-drag-extended")
        compose.onNodeWithTag("parameter-width").performTouchInput { up() }
        compose.onNodeWithTag("parameter-drag-preview").assertDoesNotExist()
    }

    @Test fun dragExtensionAnimatesUpwardWithoutDetaching() {
        var shown by androidx.compose.runtime.mutableStateOf(false)
        var open by androidx.compose.runtime.mutableStateOf(true)
        show {
            Box(Modifier.fillMaxSize()) {
                if (shown) LiftedParameter("宽度", "512", androidx.compose.ui.geometry.Rect(16f, 250f, 176f, 298f), open) { shown = false }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = true }
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(48)
        val middle = compose.onNodeWithTag("parameter-drag-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.height > 48f && middle.height < 92f)
        assertEquals(298f, middle.bottom, 1f)
        capture("v014-drag-transition")
        compose.mainClock.advanceTimeBy(220)
        compose.runOnIdle { open = false }
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(48)
        val closing = compose.onNodeWithTag("parameter-drag-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(closing.height > 48f && closing.height < 92f)
        assertEquals(298f, closing.bottom, 1f)
        compose.mainClock.advanceTimeBy(220)
        compose.onNodeWithTag("parameter-drag-preview").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun generationButtonStopsAtRestPositionDuringImeDismissal() {
        var ime by androidx.compose.runtime.mutableStateOf(320.dp)
        show {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                WorkspaceBottomBar(0, {}, imeBottomOverride = ime) { Button({}, Modifier.fillMaxWidth().testTag("ime-generate")) { Text("生成图像") } }
            }
        }
        var previous = Float.NEGATIVE_INFINITY
        var stopped: Float? = null
        listOf(320, 240, 160, 96, 64, 32, 16, 0).forEach { inset ->
            compose.runOnIdle { ime = inset.dp }
            val top = compose.onNodeWithTag("ime-generate").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Generate button reversed direction while IME closed", top >= previous)
            previous = top
            if (inset <= 32) {
                if (stopped == null) stopped = top else assertEquals("Button overshot and jumped when navigation returned", stopped!!, top, 1f)
            }
        }
        capture("v014-ime-rest")
    }

    @Test fun historyChoicesShareHeightAndKeepCornersDuringSelection() {
        var filter by androidx.compose.runtime.mutableStateOf(HistoryFilter(modelIds = setOf("klein")))
        show {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                HistoryFilterBar(filter, "klein", {}, { filter = filter.copy(modelIds = setOf("klein")) }, { filter = filter.copy(modelIds = null) })
            }
        }
        val tags = listOf("history-scope-current", "history-scope-all", "history-filter-button")
        val heights = tags.map { tag -> compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top } }
        assertEquals(40.dp, heights.first())
        assertTrue(heights.all { it == heights.first() })
        compose.onNodeWithTag("history-scope-all").performScrollTo()
        val corner = compose.onNodeWithTag("history-scope-all").fetchSemanticsNode().boundsInRoot.topLeft
        capture("v014-history-choice-before")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("history-scope-all").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(64)
        capture("v014-history-choice-transition")
        fun cornerPixel(name: String): Int {
            val file = File(System.getProperty("ui.screenshots.dir", "/tmp/miuix-screenshots"), "$name-$scale.png")
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            return bitmap.getPixel(corner.x.toInt() + 1, corner.y.toInt() + 1).also { bitmap.recycle() }
        }
        assertEquals("Selection briefly painted a rectangular corner", cornerPixel("v014-history-choice-before"), cornerPixel("v014-history-choice-transition"))
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("history-scope-all").assertIsSelected()
        compose.mainClock.autoAdvance = true
    }

    @Test fun historyFilterButtonsRemainSelectableAndApply() {
        var applied: HistoryFilter? = null
        show { HistoryFilterSheet(HistoryFilter(), listOf("klein"), listOf("euler"), listOf("512x512"), { applied = it }, {}) }
        compose.onNodeWithText("txt2img").assertHasClickAction().performClick().assertIsSelected()
        capture("v014-history-filter", dialog = true)
        compose.onNodeWithText("应用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf(GenerationMode.TXT2IMG), applied?.modes) }
    }

    @Test fun importedThumbnailOpensZoomViewerAndCloses() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(70, 130, 180)) }
        var preview by androidx.compose.runtime.mutableStateOf<Bitmap?>(null)
        show {
            Box(Modifier.fillMaxSize()) {
                PreviewableInputCard(bitmap, "查看参考图", { preview = it }, Modifier.size(64.dp).testTag("input-thumbnail")) { Text("参考") }
                preview?.let { InputImagePreview(it) { preview = null } }
            }
        }
        compose.onNodeWithTag("input-thumbnail").performClick()
        compose.onNodeWithTag("input-image-preview").assertIsDisplayed()
        capture("v014-input-preview")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("input-image-preview").assertDoesNotExist()
    }

    @Test fun modelLibraryImportIsRevealedByDownwardDrag() {
        val dirs = openLibrary()
        try {
            compose.onNodeWithText("CPU").assertIsDisplayed()
            compose.onNodeWithText("NPU").assertIsDisplayed()
            compose.onNodeWithTag("model-import-entry").assertDoesNotExist()
            compose.onNodeWithTag("model-library-list").performTouchInput { swipeDown(startY = height * .25f, endY = height * .8f, durationMillis = 500) }
            compose.onNodeWithTag("model-import-entry").assertIsDisplayed()
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("model-import-entry")), useUnmergedTree = true).assertIsDisplayed()
            capture("v014-library-pull-import")
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    @Test fun parameterCardDoesNotDarkenBackground() {
        show { SampleWorkspace() }
        capture("v014-card-background-before")
        compose.onNodeWithTag("parameter-width").performClick()
        capture("v014-card-background-open")
        fun pixel(name: String): Int {
            val file = File(System.getProperty("ui.screenshots.dir", "/tmp/miuix-screenshots"), "$name-$scale.png")
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            return bitmap.getPixel(4, 120).also { bitmap.recycle() }
        }
        assertEquals("Opening a parameter card dimmed the workspace", pixel("v014-card-background-before"), pixel("v014-card-background-open"))
    }

    private fun screenshotPixels(name: String, rect: androidx.compose.ui.geometry.Rect): IntArray {
        val file = File(System.getProperty("ui.screenshots.dir", "/tmp/miuix-screenshots"), "$name-$scale.png")
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        val width = rect.width.toInt().coerceAtLeast(1)
        val height = rect.height.toInt().coerceAtLeast(1)
        return IntArray(width * height).also {
            bitmap.getPixels(it, 0, width, rect.left.toInt(), rect.top.toInt(), width, height)
            bitmap.recycle()
        }
    }

    @Test fun pressingOutsideParameterCardDoesNotTintTheWorkspace() {
        show { SampleWorkspace() }
        compose.onNodeWithTag("parameter-width").performClick()
        val area = compose.onNodeWithTag("parameter-editor-scrim").fetchSemanticsNode().boundsInRoot
        val sample = androidx.compose.ui.geometry.Rect(area.left + 2, area.top + 2, area.left + 14, area.top + 14)
        capture("v015-outside-rest")
        compose.onNodeWithTag("parameter-editor-scrim").performTouchInput {
            down(Offset(4f, 4f))
            advanceEventTime(80)
        }
        capture("v015-outside-pressed")
        assertArrayEquals("Pressing empty workspace painted a dark ripple", screenshotPixels("v015-outside-rest", sample), screenshotPixels("v015-outside-pressed", sample))
        compose.onNodeWithTag("parameter-editor-scrim").performTouchInput { up() }
    }

    @Test fun closingParameterCardWaitsForImeAndRestoresUnderlayBeforeShrinking() {
        var ime by androidx.compose.runtime.mutableStateOf(false)
        show(imeVisible = { ime }) { SampleWorkspace() }
        compose.onNodeWithTag("parameter-width").performClick()
        compose.onNodeWithTag("parameter-number-input").performClick()
        compose.runOnIdle { ime = true }
        compose.onNodeWithTag("parameter-cfg").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("返回提示词").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("parameter-editor-card").assertIsDisplayed()
        compose.runOnIdle { ime = false }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("parameter-cfg").assertIsDisplayed()
        compose.onNodeWithTag("parameter-editor-card").assertIsDisplayed()
        capture("v015-card-return-underlay")
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("parameter-editor-card").assertDoesNotExist()
        compose.onNodeWithTag("parameter-cfg").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test fun holdingLockCanChangePresetsAgainWithoutUnlocking() {
        show { SampleWorkspace() }
        fun choose(distance: Float) {
            compose.onNodeWithTag("aspect-ratio-lock").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveBy(Offset(distance, 0f), delayMillis = 100)
            }
            compose.onNodeWithTag("aspect-ratio-picker").assertIsDisplayed()
            capture("v015-ratio-picker")
            compose.onNodeWithTag("aspect-ratio-lock").performTouchInput { up() }
            compose.onNodeWithTag("aspect-ratio-lock").assertIsOn()
        }
        choose(32f)
        compose.onNodeWithTag("parameter-width").assertTextContains("704")
        compose.onNodeWithTag("parameter-height").assertTextContains("512")
        choose(32f)
        compose.onNodeWithTag("parameter-width").assertTextContains("768")
        compose.onNodeWithTag("parameter-height").assertTextContains("512")
        compose.onNodeWithTag("aspect-ratio-lock").performClick().assertIsOff()
    }

    @Test fun lockedDimensionPartnerOnlyGetsAnOutlineWhileDragging() {
        show { SampleWorkspace() }
        compose.onNodeWithTag("aspect-ratio-lock").performClick()
        val before = compose.onNodeWithTag("parameter-height").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("parameter-width").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(36f, 0f), delayMillis = 100)
        }
        val after = compose.onNodeWithTag("parameter-height").fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, 1f)
        compose.onNodeWithTag("parameter-height").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "联动调节"))
        compose.onAllNodesWithTag("parameter-drag-preview").assertCountEquals(1)
        capture("v015-linked-outline-ruler")
        compose.onNodeWithTag("parameter-width").performTouchInput { up() }
    }

    @Test fun dragRulerMovesEvenBetweenValueTicks() {
        show { SampleWorkspace() }
        compose.onNodeWithTag("parameter-width").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(3f, 0f), delayMillis = 100)
        }
        val ruler = compose.onNodeWithTag("drag-ruler").fetchSemanticsNode().boundsInRoot
        capture("v015-ruler-before")
        compose.onNodeWithTag("parameter-width").performTouchInput { moveBy(Offset(4f, 0f), delayMillis = 100) }
        capture("v015-ruler-after")
        assertFalse("The ruler marks did not move with the finger", screenshotPixels("v015-ruler-before", ruler).contentEquals(screenshotPixels("v015-ruler-after", ruler)))
        compose.onNodeWithTag("parameter-width").performTouchInput { up() }
    }

    @Test fun focusingPositivePromptCollapsesNegativeWithoutRestoringItOnImeClose() {
        var ime by androidx.compose.runtime.mutableStateOf(false)
        show(imeVisible = { ime }) { SampleWorkspace() }
        compose.onNodeWithTag("negative-prompt-toggle").performClick()
        compose.onNodeWithTag("negative-prompt-editor").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("positive-prompt-editor"))).performClick()
        compose.runOnIdle { ime = true }
        compose.onNodeWithTag("negative-prompt-editor").assertDoesNotExist()
        compose.runOnIdle { ime = false }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("positive-prompt-editor"))).assertIsNotFocused()
        compose.onNodeWithTag("negative-prompt-editor").assertDoesNotExist()
    }

    @Test fun positivePromptExpandsFromItsOwnEdgesAndKeepsText() {
        show { SampleWorkspace() }
        val original = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("positive-prompt-expand").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(64)
        val middle = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.height > original.height)
        capture("v015-prompt-expand-middle")
        compose.mainClock.advanceTimeBy(300)
        val full = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(full.height > middle.height)
        compose.onNode(hasSetTextAction()).performTextInput(", soft lighting")
        capture("v015-prompt-expanded")
        compose.onNodeWithTag("positive-prompt-expand").performClick()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNode(hasSetTextAction()).assertTextContains("soft lighting", substring = true)
        val restored = compose.onNodeWithTag("positive-prompt-editor").fetchSemanticsNode().boundsInRoot
        assertEquals(original.height, restored.height, 1f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun keyboardDismissClearsFocusInsideDialogFieldsToo() {
        var ime by androidx.compose.runtime.mutableStateOf(false)
        var value by androidx.compose.runtime.mutableStateOf("")
        var opened by androidx.compose.runtime.mutableStateOf(true)
        try {
            show(imeVisible = { ime }) {
                if (opened) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { opened = false }) {
                        Card(Modifier.fillMaxWidth()) {
                            OutlinedTextField(value, { value = it }, singleLine = true)
                        }
                    }
                }
            }
            compose.onNode(hasSetTextAction()).performClick().assertIsFocused()
            compose.runOnIdle { ime = true }
            compose.runOnIdle { ime = false }
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
        } finally {
            compose.runOnUiThread { opened = false }
        }
    }

    @Test fun referenceDraggingChangesStableItemOrderAndRetainsClickPreview() {
        val values = androidx.compose.runtime.mutableStateListOf("A", "B", "C", "D")
        var preview = ""
        show {
            ReorderableReferenceRow(values, { it }, true, { from, to -> values.add(to, values.removeAt(from)) }) { value, _, modifier ->
                Card(onClick = { preview = value }, modifier = modifier.size(64.dp).testTag("reference-$value")) { Text(value) }
            }
        }
        compose.onNodeWithText("A").performClick()
        compose.runOnIdle { assertEquals("A", preview) }
        val first = compose.onNodeWithTag("reference-A").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("reference-image-strip").performTouchInput {
            down(first)
            advanceEventTime(700)
            moveBy(Offset(80f, 0f), delayMillis = 100)
        }
        compose.runOnIdle { assertEquals(listOf("B", "A", "C", "D"), values.toList()) }
        compose.onNodeWithTag("reference-image-strip").performTouchInput { moveBy(Offset(72f, 0f), delayMillis = 100) }
        compose.runOnIdle { assertEquals(listOf("B", "C", "A", "D"), values.toList()) }
        compose.onNodeWithTag("reference-image-strip").performTouchInput { up() }
        capture("v015-reference-order")
    }

    @Test fun switchingModelTabsResetsScrollAndHidesImportAgain() {
        val dirs = openLibrary()
        try {
            compose.onNodeWithTag("model-library-list").performTouchInput { swipeDown() }
            compose.onNodeWithTag("model-import-entry").assertIsDisplayed()
            repeat(3) { compose.onNodeWithTag("model-library-list").performTouchInput { swipeUp() } }
            compose.onNodeWithText("NPU").performClick()
            compose.onNodeWithText("CPU").performClick()
            compose.onNodeWithTag("model-import-entry").assertDoesNotExist()
            compose.onNodeWithText("ui_scroll_00").assertIsDisplayed()
            capture("v015-library-reset")
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    @Test fun selectedChoicesKeepTheSameTextPositionWithoutPrefixMarks() {
        var first by androidx.compose.runtime.mutableStateOf(false)
        var second by androidx.compose.runtime.mutableStateOf(false)
        show {
            Column {
                ChoiceControl("筛选选项", { first = !first }, checked = first)
                ParameterChoice("Euler A", second, { second = !second })
            }
        }
        listOf("筛选选项", "Euler A").forEach { label ->
            val before = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText(label).performClick().assertIsSelected()
            val after = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertEquals("Selection inserted a prefix mark", before.width, after.width, 1f)
        }
        capture("v015-selected-without-checkmarks")
    }

    @Test fun cpuImportUsesThemeAccentAndStaysCompact() {
        var accent = androidx.compose.ui.graphics.Color.Unspecified
        show {
            accent = MaterialTheme.colorScheme.primary
            AddCustomModelButton({}, Modifier.testTag("cpu-import"))
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("导入模型", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(accent, layouts.first().layoutInput.style.color)
        val bounds = compose.onNodeWithTag("cpu-import").getUnclippedBoundsInRoot()
        assertEquals(52.dp, bounds.bottom - bounds.top)
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
    private fun SampleWorkspace(onWidth: (Float) -> Unit = {}, cpuRuntime: Boolean = false) {
        var width by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(512f) }
        var height by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(512f) }
        var locked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var ratio by androidx.compose.runtime.remember { androidx.compose.runtime.mutableDoubleStateOf(1.0) }
        var openCL by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var seed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var sampler by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("euler") }
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
                    isSdxl = false, isDit = !cpuRuntime, runOnCpu = cpuRuntime, useImg2img = true, isRunning = false,
                    aspectRatio = "1:1", availableResolutions = emptyList(), currentWidth = width.toInt(), currentHeight = height.toInt(),
                    aspectLocked = locked, lockedRatio = if (locked) ratio else null,
                    onAspectPresetSelected = {
                        ratio = it
                        locked = true
                        val pair = DitResolution.lockedSize(width, ratio, true)
                        width = pair.first.toFloat()
                        height = pair.second.toFloat()
                    }, onAspectLockChange = {
                        ratio = width.toDouble() / height
                        locked = it
                    },
                    scheduler = sampler, steps = 4f, cfg = 1f, useOpenCL = openCL, batchCounts = 1,
                    denoiseStrength = .6f, seed = seed, returnedSeed = 123L,
                    onAspectRatioSelected = {}, onCustomAspectRatioClick = {}, onResolutionSelected = {},
                    onDitWidthChange = {
                        if (locked) {
                            val pair = DitResolution.lockedSize(it, ratio, true)
                            width = pair.first.toFloat()
                            height = pair.second.toFloat()
                        } else {
                            width = it
                        }
                        onWidth(width)
                    }, onDitHeightChange = {
                        if (locked) {
                            val pair = DitResolution.lockedSize(it, ratio, false)
                            width = pair.first.toFloat()
                            height = pair.second.toFloat()
                        } else {
                            height = it
                        }
                    }, onSchedulerChange = { sampler = it }, onStepsChange = {}, onCfgChange = {}, onSizeChange = {},
                    onCpuSelected = { openCL = false }, onGpuSelected = { openCL = true }, onBatchCountsChange = {}, onDenoiseStrengthChange = {},
                    onSeedChange = { seed = it }, onUseLastSeed = { seed = "123" },
                    promptFocused = positive.isFocused || negative.isFocused,
                    positiveFocused = positive.isFocused,
                    positive = { ControlledPromptTagTextField(positive, false, { Text("正向提示词", style = MaterialTheme.typography.labelSmall) }, fixedViewport = true, labelInOutline = true, bottomInset = 36.dp) },
                    negative = { ControlledPromptTagTextField(negative, false, {}, fixedViewport = true) },
                    inputs = { _ -> ImageInputActions({}, {}) },
                )
            }
        }
    }

    @Test fun resultDetailsCanBeReachedWithTouchScrolling() {
        var saved = false
        var favorite by androidx.compose.runtime.mutableStateOf(false)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 140, 120)) }
        val params = GenerationParameters(4, 1f, 1711452183L, "a portrait", "", "146.4 seconds", 512, 512, false)
        show {
            DreamPage("Klein", bottomBar = { WorkspaceNavigation(1, {}) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    ModelRunResultPage(bitmap, 1, params, emptyList(), false, true, true, true, true, {}, {}, favorite, { favorite = !favorite }, {}, {}, {}, { saved = it === bitmap }, {}, {}, {}, {})
                }
            }
        }
        val actionBounds = listOf("result-save", "result-favorite", "result-upscale").map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        actionBounds.forEach {
            assertEquals((actionBounds.first().right - actionBounds.first().left).value, (it.right - it.left).value, 1f)
            assertEquals(44.dp, it.bottom - it.top)
        }
        compose.onNodeWithText("导入图片").assertDoesNotExist()
        compose.onNodeWithTag("result-favorite").assertTextContains("收藏").performClick().assertIsSelected()
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
        // Robolectric replaces Application/storage between methods while Kotlin statics can
        // retain the previous Application. Scan the fixture with this test's actual context.
        ModelRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("is_first_launch", false).putInt("last_viewed_page", 0).commit()
        val dirs = (0..17).map { index ->
            File(ModelStorage.modelsDir(context), "ui_scroll_%02d".format(index)).apply {
                mkdirs()
                File(this, "finished").writeText("")
            }
        }
        kotlinx.coroutines.runBlocking { ModelRepository.getInstance(context).refreshAllModels() }
        assertEquals("The scrolling fixture was not loaded into the actual repository", 18, ModelRepository.getInstance(context).models.count { it.id.startsWith("ui_scroll_") })
        show { ModelListScreen(androidx.navigation.compose.rememberNavController()) }
        compose.onNodeWithTag("model-library-list").assertIsDisplayed()
        capture("v014-library-initial")
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
            assertTrue("Finger swipes did not advance the list: before=$before after=$after", after > before)
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
