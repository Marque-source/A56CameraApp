package com.a56camera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.a56camera.ui.theme.AquaDeep
import com.a56camera.ui.theme.IridescentBlue
import com.a56camera.ui.theme.IridescentPink

@Composable
fun BottomControls(
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
    isNight: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Son çekilen küçük resim
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .clickable { onOpenGallery() }
        )

        // Shutter butonu
        Box(
            modifier = Modifier
                .size(84.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = CircleShape,
                    ambientColor = if (isNight) IridescentPink else IridescentBlue,
                    spotColor = Color.White
                )
                .clip(CircleShape)
                .background(Color.White)
                .border(
                    width = 4.dp,
                    brush = Brush.sweepGradient(
                        listOf(IridescentBlue, IridescentPink, IridescentBlue)
                    ),
                    shape = CircleShape
                )
                .padding(6.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isNight) IridescentPink else IridescentBlue,
                            AquaDeep
                        )
                    )
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onCapture()
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }

        // Boş dengeleme alanı
        Spacer(modifier = Modifier.size(48.dp))
    }
}
