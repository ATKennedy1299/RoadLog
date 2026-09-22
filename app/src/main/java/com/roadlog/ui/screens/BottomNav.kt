package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.BackgroundBlack
import com.roadlog.ui.theme.DividerColor
import com.roadlog.ui.theme.TextSecondary

/** The three top-level, bottom-nav-visible destinations — kept as plain routes, no args. */
enum class BottomNavDestination(val route: String, val label: String) {
    HOME("home", "HOME"),
    GARAGE("garage", "GARAGE"),
    STATS("stats", "STATS")
}

@Composable
fun RoadLogBottomNav(currentRoute: String?, onNavigate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(BackgroundBlack)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BottomNavDestination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) AccentGreen else TextSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable(enabled = !selected) { onNavigate(destination.route) }
                )
            }
        }
    }
}
