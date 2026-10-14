package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Opens the tabs grid. Shows the tab count as a badge over [tabIcon]. */
@Composable
fun TabsButton(tabCount: Int, tabIcon: @Composable () -> Unit, onOpenTabs: () -> Unit) {
    Surface(
        onClick = onOpenTabs,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Tabs" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            BadgedBox(badge = { Badge { Text(tabCount.toString()) } }) {
                tabIcon()
            }
        }
    }
}
