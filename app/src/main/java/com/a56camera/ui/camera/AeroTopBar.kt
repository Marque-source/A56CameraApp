package com.a56camera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a56camera.ui.theme.AquaBright

@Composable
fun AeroTopBar(
    resolution: String = "12MP",
    ratio: String = "4:3",
    gridOn: Boolean = true,
    hdrOn: Boolean = true,
    timerOn: Boolean = false,
    flashMode: Int = 0,
    onSettings: () -> Unit = {},
    onResolutionToggle: () -> Unit = {},
    onRatioToggle: () -> Unit = {},
    onGridToggle: () -> Unit = {},
    onHdrToggle: () -> Unit = {},
    onTimerToggle: () -> Unit = {},
    onFlashToggle: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Settings Gear Button
        TopChipIcon(icon = Icons.Default.Settings, onClick = onSettings)

        // Resolution Chip
        TopChipText(label = resolution, active = resolution == "50MP", onClick = onResolutionToggle)

        // Ratio Chip
        TopChipText(label = ratio, active = false, onClick = onRatioToggle)

        // Grid Chip
        TopChipIcon(icon = Icons.Default.GridOn, active = gridOn, onClick = onGridToggle)

        // HDR Chip
        TopChipText(label = "HDR", active = hdrOn, onClick = onHdrToggle)

        // Timer Chip
        TopChipIcon(icon = Icons.Default.Timer, active = timerOn, onClick = onTimerToggle)

        // Flash Chip
        TopChipFlash(flashMode = flashMode, onClick = onFlashToggle)
    }
}

@Composable
private fun TopChipText(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (active) AquaBright.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f)
            )
            .border(
                width = 1.dp,
                color = if (active) AquaBright.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) AquaBright else Color.White.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun TopChipIcon(
    icon: ImageVector,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (active) AquaBright.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f)
            )
            .border(
                width = 1.dp,
                color = if (active) AquaBright.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.25f),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) AquaBright else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun TopChipFlash(
    flashMode: Int,
    onClick: () -> Unit
) {
    val icon = when (flashMode) {
        1 -> Icons.Default.FlashOn
        2 -> Icons.Default.FlashAuto
        else -> Icons.Default.FlashOff
    }
    val tint = when (flashMode) {
        1 -> AquaBright
        2 -> Color(0xFFFFD54F)
        else -> Color.White.copy(alpha = 0.6f)
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}
