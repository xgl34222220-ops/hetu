package io.github.xgl34222220.hetu.ui

import kotlinx.coroutines.launch
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

/** Compatibility name; feedback is now shared Compose animation rather than library-specific. */
@Composable
fun Modifier.miuixTap(enabled: Boolean = true, role: Role = Role.Button,
    onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    hetuTap(enabled, role, onClickLabel, onClick)

val LocalReferenceDismiss = staticCompositionLocalOf<(() -> Unit) -> Unit> { { action -> action() } }

/** A real bottom sheet: keep SheetState, swipe dismissal, scrim, insets and caller styling. */
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
    scrimColor: Color = Color.Black.copy(alpha = .28f),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(onDismissRequest)
    var dismissing by remember(sheetState) { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (!dismissing) onDismiss() },
        modifier = modifier, sheetState = sheetState, sheetMaxWidth = sheetMaxWidth,
        shape = shape, containerColor = containerColor, contentColor = contentColor,
        tonalElevation = tonalElevation, scrimColor = scrimColor, dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
    ) {
        CompositionLocalProvider(LocalReferenceDismiss provides { action ->
            if (!dismissing) {
                dismissing = true
                scope.launch {
                    try {
                        sheetState.hide()
                        // A caller may veto Hidden through confirmValueChange.
                        if (!sheetState.isVisible) action()
                    } finally { dismissing = false }
                }
            }
        }) { content() }
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
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = shape, colors = colors, elevation = elevation, border = border,
        contentPadding = contentPadding, interactionSource = interactionSource, content = content,
    )
}
