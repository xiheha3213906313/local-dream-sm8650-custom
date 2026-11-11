package io.github.xororz.localdream.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Buttons have one-line names. Explanations and filenames belong outside the button.
internal val LocalCompactAction = staticCompositionLocalOf { false }

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = if (LocalCompactAction.current) MaterialTheme.typography.labelLarge else MiuixTheme.textStyles.main,
) {
    val compact = LocalCompactAction.current
    top.yukonga.miuix.kmp.basic.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize,
        fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = if (compact) TextOverflow.Ellipsis else overflow,
        softWrap = !compact && softWrap, maxLines = if (compact) 1 else maxLines,
        minLines = if (compact) 1 else minLines,
        onTextLayout = onTextLayout, style = style,
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = if (LocalCompactAction.current) MaterialTheme.typography.labelLarge else MiuixTheme.textStyles.main,
) {
    val compact = LocalCompactAction.current
    top.yukonga.miuix.kmp.basic.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize,
        fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = if (compact) TextOverflow.Ellipsis else overflow,
        softWrap = !compact && softWrap, maxLines = if (compact) 1 else maxLines,
        minLines = if (compact) 1 else minLines,
        onTextLayout = { onTextLayout?.invoke(it) }, style = style,
    )
}
