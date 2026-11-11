package io.github.xororz.localdream.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.IconButton

/** Single-line toolbar; detailed names and descriptions live in the scrollable content. */
@Composable
fun DreamToolbar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun DreamPage(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) = Scaffold(topBar = { DreamToolbar(title, onBack, actions) }, bottomBar = bottomBar, content = content)

@Composable
fun PageColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
fun Section(title: String? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        }
    }
}

@Composable
fun PageIntro(title: String, summary: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ActionDock(navigationInsets: Boolean = true, imeInsets: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.then(if (navigationInsets) Modifier.navigationBarsPadding() else Modifier).then(if (imeInsets) Modifier.imePadding() else Modifier).padding(horizontal = 24.dp, vertical = 14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun keyboardIsVisible(): Boolean = LocalImeVisibleOverride.current ?: (WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0)

/** One bottom clearance drives both IME travel and navigation, avoiding a late nav-height jump. */
@Composable
internal fun WorkspaceBottomBar(selectedIndex: Int, onSelected: (Int) -> Unit, imeBottomOverride: Dp? = null, action: @Composable () -> Unit) {
    val density = LocalDensity.current
    val ime = imeBottomOverride ?: with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val navigationHeight = 64.dp + navigationBottom
    Column(Modifier.fillMaxWidth().testTag("workspace-bottom-bar")) {
        if (selectedIndex == 0) ActionDock(navigationInsets = false, imeInsets = false) { action() }
        Box(Modifier.fillMaxWidth().height(maxOf(ime, navigationHeight)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.graphicsLayer { alpha = (1f - ime.value / navigationHeight.value).coerceIn(0f, 1f) }) {
                WorkspaceNavigation(selectedIndex, onSelected, enabled = ime == 0.dp)
            }
        }
    }
}

/** Inline icon + label keeps navigation compact at large font scales. */
@Composable
fun WorkspaceNavigation(selectedIndex: Int, onSelected: (Int) -> Unit, enabled: Boolean = true) {
    val labels = listOf(
        androidx.compose.ui.res.stringResource(io.github.xororz.localdream.R.string.ui_create),
        androidx.compose.ui.res.stringResource(io.github.xororz.localdream.R.string.ui_results),
        androidx.compose.ui.res.stringResource(io.github.xororz.localdream.R.string.ui_history),
    )
    val icons = listOf(
        androidx.compose.material.icons.Icons.Default.Edit,
        androidx.compose.material.icons.Icons.Default.Image,
        androidx.compose.material.icons.Icons.Default.History,
    )
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Card(onClick = { onSelected(index) }, enabled = enabled, modifier = Modifier.weight(1f), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(icons[index], null, Modifier.size(18.dp), tint = color)
                        Spacer(Modifier.width(4.dp))
                        Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
