package com.mrndtvndv.term.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

private val PillShape = RoundedCornerShape(28.dp)
private val PillSizeSpring = spring<IntSize>(dampingRatio = 0.8f, stiffness = 380f)

/** Frosted glass: blurs what [hazeState] sources behind the node, tinted with surfaceContainerHighest. */
@Composable
fun Modifier.glassPill(hazeState: HazeState, shape: Shape = PillShape): Modifier {
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .shadow(elevation = 6.dp, shape = shape)
        .clip(shape)
        .hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle {
                blurEnabled(true)
                blurRadius(24.dp)
                backgroundColor(glassTint)
                colorEffects(listOf(HazeColorEffect.tint(glassTint.copy(alpha = 0.55f))))
                fallbackColorEffect(HazeColorEffect.tint(glassTint))
            }
        )
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
}

/**
 * The [glassPill] look without Haze, for pills over content that blurs itself, such as the terminal's
 * GL backdrop blur regions.
 */
@Composable
fun Modifier.tintedGlassPill(shape: Shape = PillShape): Modifier {
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .shadow(elevation = 6.dp, shape = shape)
        .clip(shape)
        .background(glassTint.copy(alpha = 0.55f))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
}

/**
 * Floating pill that springs between [state] contents. Callers place it and own the insets.
 * [trailing] sits to the right of the pill, vertically centered with it.
 */
@Composable
fun <T> FloatingPill(
    state: T,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    label: String = "FloatingPill",
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f, fill = false).glassPill(hazeState),
                shape = PillShape,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                AnimatedContent(
                    targetState = state,
                    contentAlignment = Alignment.Center,
                    transitionSpec = {
                        val spec = spring<Float>(stiffness = Spring.StiffnessMedium)
                        (fadeIn(spec) + scaleIn(initialScale = 0.92f, animationSpec = spec))
                            .togetherWith(fadeOut(spec) + scaleOut(targetScale = 0.92f, animationSpec = spec))
                            .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> PillSizeSpring }))
                    },
                    label = label,
                    content = { content(it) }
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun PillActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
fun PillDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(20.dp)
            .padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}
