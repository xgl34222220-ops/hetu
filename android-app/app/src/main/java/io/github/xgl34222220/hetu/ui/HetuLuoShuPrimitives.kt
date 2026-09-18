package io.github.xgl34222220.hetu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hetu's shared native-mobile scale.
 *
 * The historical LuoShu-prefixed API names are kept so existing screens do not churn, but the
 * visual rules are now Hetu-specific: 48dp Android touch targets, restrained surfaces, a clear
 * 4/8dp rhythm, and no decorative elevation where hierarchy can be expressed by tone instead.
 */
object HetuLuoShuIconTokens {
    val HeaderTouchTarget = 48.dp
    val HeaderContainer = 44.dp
    val HeaderGlyph = 21.dp
    val DockGlyph = 22.dp
    val SectionGlyph = 18.dp
    val ToolGlyph = 20.dp
    val LeadingGlyph = 20.dp
    val StatusGlyph = 22.dp
    val TrailingGlyph = 18.dp
    val ProgressGlyph = 22.dp
    val CompactProgress = 20.dp
}

@Composable
fun HetuLuoShuGlyph(
    imageVector: ImageVector,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    opticalScale: Float = 1f,
    tint: Color = Color.Unspecified,
) {
    val resolvedTint = if (tint == Color.Unspecified) LocalContentColor.current else tint
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().scale(opticalScale),
            tint = resolvedTint,
        )
    }
}

@Composable
fun HetuLuoShuHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color = LocalHetuTokens.current.controlBackground,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    opticalScale: Float = 1f,
    contentColor: Color = Color.Unspecified,
) {
    val resolved = if (contentColor == Color.Unspecified) MaterialTheme.colorScheme.primary else contentColor
    Box(modifier = modifier.size(HetuLuoShuIconTokens.HeaderTouchTarget), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(HetuLuoShuIconTokens.HeaderContainer),
            shape = CircleShape,
            color = containerColor,
            contentColor = resolved,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            IconButton(
                onClick = onClick,
                enabled = enabled && !loading,
                modifier = Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = resolved,
                    disabledContentColor = resolved.copy(alpha = .38f),
                ),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(HetuLuoShuIconTokens.HeaderGlyph),
                        strokeWidth = 2.dp,
                        color = resolved,
                    )
                } else {
                    HetuLuoShuGlyph(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        size = HetuLuoShuIconTokens.HeaderGlyph,
                        opticalScale = opticalScale,
                        tint = resolved,
                    )
                }
            }
        }
    }
}

@Composable
fun HetuLuoShuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val tokens = LocalHetuTokens.current
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = tokens.textPrimary,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}

@Composable
fun HetuLuoShuSurfaceCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalHetuTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (emphasized) tokens.selectionBackground else tokens.cardBackground,
        contentColor = tokens.textPrimary,
        tonalElevation = 0.dp,
        shadowElevation = if (emphasized) 1.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun HetuLuoShuSectionHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val tokens = LocalHetuTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        action()
    }
}
