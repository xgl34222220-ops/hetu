package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

/** The reference uses Miuix's sink response and touch indication, including release springs. */
@Composable
fun Modifier.miuixTap(enabled: Boolean = true, role: Role = Role.Button,
    onClickLabel: String? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val motion = LocalHetuMotionEnabled.current
    return pressable(source, if (motion) SinkFeedback(sinkAmount = .97f) else null, enabled, delay = null)
        .clickable(source, LocalIndication.current, enabled, onClickLabel, role, onClick)
}

val LocalReferenceDismiss = staticCompositionLocalOf<(() -> Unit) -> Unit> { { action -> action() } }

/** Compatibility bridge for existing form bodies: actual Miuix window layout and motion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetMaxWidth: Dp = 640.dp,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = LocalHetuTokens.current.cardBackground,
    contentColor: Color = LocalHetuTokens.current.textPrimary,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color.Black.copy(alpha = .3f),
    dragHandle: @Composable (() -> Unit)? = null,
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets(0) },
    content: @Composable ColumnScope.() -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) { visible = true }
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = visible, modifier = modifier,
        onDismissRequest = { visible = false }, onDismissFinished = { (afterDismiss ?: onDismissRequest)() },
        outsideMargin = DpSize(12.dp, 12.dp), insideMargin = DpSize(0.dp, 12.dp),
        backgroundColor = LocalHetuTokens.current.cardBackground,
        maxWidth = sheetMaxWidth, cornerRadius = 22.dp,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor,
            LocalReferenceDismiss provides { action -> afterDismiss = action; visible = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp), content = content)
        }
    }
}

@Composable
fun ReferenceButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: androidx.compose.foundation.BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    top.yukonga.miuix.kmp.basic.Button(
        onClick, modifier, enabled, cornerRadius = 12.dp, minHeight = 44.dp,
        insideMargin = contentPadding, interactionSource = interactionSource,
        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
            color = colors.containerColor, contentColor = colors.contentColor,
            disabledColor = colors.disabledContainerColor, disabledContentColor = colors.disabledContentColor),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor) {
            content()
        }
    }
}
