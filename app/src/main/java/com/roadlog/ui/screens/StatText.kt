package com.roadlog.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.TextSecondary

@Composable
fun StatText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AccentGreen
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}
