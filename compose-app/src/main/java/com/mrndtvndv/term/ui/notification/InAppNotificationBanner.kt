package com.mrndtvndv.term.ui.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

// Drag past this fraction of the card width to dismiss; release earlier to snap back.
private const val SwipeDismissThreshold = 0.4f
// Fly past the card's own width so its edge clears the screen, including the padding and shadow.
private const val SwipeExitFraction = 1.5f

@Composable
fun InAppNotificationBanner(
    activeNotification: ActiveNotification?,
    onDismiss: () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    // Holds the last notification so it can animate out after onDismiss clears the source.
    var shown by remember { mutableStateOf<ActiveNotification?>(null) }

    LaunchedEffect(activeNotification) {
        if (activeNotification != null) {
            shown = activeNotification
            visible = true
            kotlinx.coroutines.delay(4000)
            visible = false
            kotlinx.coroutines.delay(300) // Wait for animation
            onDismiss()
        } else {
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        shown?.let { notification ->
            // Keyed so a new notification starts with its swipe offset reset.
            key(notification) {
                NotificationCard(
                    activeNotification = notification,
                    onClick = onClick,
                    onClose = {
                        visible = false
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(
    activeNotification: ActiveNotification,
    onClick: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val currentOnClose by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .statusBarsPadding()
    ) {
        val cardModifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val dismiss = abs(offsetX) > size.width * SwipeDismissThreshold
                        val target = if (dismiss) sign(offsetX) * size.width * SwipeExitFraction else 0f
                        scope.launch {
                            animate(offsetX, target) { value, _ -> offsetX = value }
                            if (dismiss) currentOnClose()
                        }
                    },
                    onDragCancel = {
                        scope.launch { animate(offsetX, 0f) { value, _ -> offsetX = value } }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount
                    },
                )
            }
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
        Card(
            modifier = if (onClick != null) cardModifier.clickable { onClick() } else cardModifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
            )
        ) {
            NotificationCardContent(
                activeNotification = activeNotification,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun NotificationCardContent(
    activeNotification: ActiveNotification,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = "Notification",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activeNotification.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (activeNotification.body.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = activeNotification.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
