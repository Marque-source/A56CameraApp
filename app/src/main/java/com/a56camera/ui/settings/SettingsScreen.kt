package com.a56camera.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Stock Samsung tarzı, soldan sağa sıralanmış özellik chip’i. */
data class CameraFeature(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val premium: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: CameraSettingsState = remember { CameraSettingsState() },
    onBack: () -> Unit = {},
    onUpdate: (CameraSettingsState) -> Unit = {}
) {
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kamera Ayarları", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color(0xFF1A2332)
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFE8F4FC),
                                Color(0xFFF0F6FA),
                                Color(0xFFE0ECF8)
                            )
                        )
                    )
                }
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Çekim Modları",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF5A6B7D),
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )

                // Liquid Glass tarzı soldan sağa kayan özellikler
                FeatureChipsRow(
                    features = listOf(
                        CameraFeature("hdr", "HDR", Icons.Default.HdrOn, state.hdr),
                        CameraFeature("night", "Gece", Icons.Default.Nightlight, state.nightMode, premium = true),
                        CameraFeature("motion", "Hareket", Icons.Default.MotionPhotosOn, state.motionPhoto),
                        CameraFeature("timer", "Zamanlayıcı", Icons.Default.Timer, state.timerSeconds > 0),
                        CameraFeature("grid", "Izgara", Icons.Default.GridOn, state.gridEnabled),
                    ),
                    onFeatureToggle = { id ->
                        val newState = when (id) {
                            "hdr" -> state.copy(hdr = !state.hdr)
                            "night" -> state.copy(nightMode = !state.nightMode)
                            "motion" -> state.copy(motionPhoto = !state.motionPhoto)
                            "timer" -> state.copy(timerSeconds = if (state.timerSeconds > 0) 0 else 3)
                            "grid" -> state.copy(gridEnabled = !state.gridEnabled)
                            else -> state
                        }
                        onUpdate(newState)
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle("Görüntü Kalitesi")

                QualityCard(state) { new -> onUpdate(new) }

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle("Arayüz & Performans")

                ToggleItem(
                    icon = Icons.Default.Cameraswitch,
                    title = "1080p Önizleme",
                    subtitle = "Daha keskin preview, daha fazla bant genişliği",
                    checked = state.preview1080p,
                    onCheckedChange = { onUpdate(state.copy(preview1080p = it)) }
                )

                ToggleItem(
                    icon = Icons.Default.SettingsSuggest,
                    title = "Akıcı Önizleme",
                    subtitle = "Gece modunda analiz stream’ini durdurur",
                    checked = state.smoothPreview,
                    onCheckedChange = { onUpdate(state.copy(smoothPreview = it)) }
                )

                ToggleItem(
                    icon = Icons.Default.HideImage,
                    title = "RAW Yakalama",
                    subtitle = "Bu cihazda kullanılamaz (desteklenmiyor)",
                    checked = false,
                    enabled = false,
                    onCheckedChange = {}
                )

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle("Genel")

                ToggleItem(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    title = "Çekim Sesi",
                    checked = state.shutterSound,
                    onCheckedChange = { onUpdate(state.copy(shutterSound = it)) }
                )

                ToggleItem(
                    icon = Icons.Default.LocationOn,
                    title = "Konum Etiketi",
                    checked = state.saveLocation,
                    onCheckedChange = { onUpdate(state.copy(saveLocation = it)) }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF5A6B7D),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun FeatureChipsRow(
    features: List<CameraFeature>,
    onFeatureToggle: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        features.forEach { feature ->
            FeatureChip(
                feature = feature,
                onClick = { onFeatureToggle(feature.id) }
            )
        }
    }
}

@Composable
private fun FeatureChip(
    feature: CameraFeature,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (feature.enabled) Color(0xFF2D8AF0) else Color(0xCCFFFFFF),
        label = "chipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (feature.enabled) Color.White else Color(0xFF2D3A4A),
        label = "chipContent"
    )

    Surface(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(22.dp),
        color = backgroundColor,
        shadowElevation = if (feature.enabled) 8.dp else 2.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = feature.label,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (feature.premium) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFFFFD700), CircleShape)
                )
            }
        }
    }
}

@Composable
private fun QualityCard(
    state: CameraSettingsState,
    onUpdate: (CameraSettingsState) -> Unit
) {
    var quality by remember(state.jpegQuality) { mutableFloatStateOf(state.jpegQuality) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xD9FFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "JPEG Kalitesi",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1A2332)
                )
                Text(
                    text = "${quality.toInt()}%",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2D8AF0)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Slider(
                value = quality,
                onValueChange = { quality = it },
                onValueChangeFinished = { onUpdate(state.copy(jpegQuality = quality)) },
                valueRange = 70f..100f,
                steps = 29
            )

            Text(
                text = "Daha yüksek kalite daha büyük dosya demektir.",
                fontSize = 12.sp,
                color = Color(0xFF7A8A9A)
            )
        }
    }
}

@Composable
private fun ToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xD9FFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (enabled) Color(0xFFE3F2FD) else Color(0xFFECEFF1)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) Color(0xFF2D8AF0) else Color(0xFFB0BEC5),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color(0xFF1A2332) else Color(0xFF90A4AE)
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF7A8A9A)
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}
