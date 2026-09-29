package com.a56camera.ui.camera

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.a56camera.camera.Camera2Manager
import com.a56camera.camera.FlashMode
import com.a56camera.ui.views.Camera2PreviewView
import com.a56camera.utils.AspectRatio
import kotlin.math.abs

/** UI Durumu */
data class CameraUiState(
    val mode: CameraMode = CameraMode.PHOTO,
    val hdr: Boolean = true,
    val grid: Boolean = false,
    val timer: Int = 0,
    val flash: FlashMode = FlashMode.AUTO,
    val is50Mp: Boolean = false,
    val ratio: AspectRatio = AspectRatio.RATIO_4_3,
    val zoom: Float = 1f
)

enum class CameraMode { NIGHT, PORTRAIT, PHOTO, VIDEO }

private val FrostWhite = Color.White.copy(alpha = 0.15f)
private val FrostBorder = Color.White.copy(alpha = 0.25f)
private val AeroCyan = Color(0xFF5EB4FF)
private val AeroBlue = Color(0xFF2D8AF0)

@Composable
fun CameraScreen(
    onOpenSettings: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onPhotoCaptured: (Uri) -> Unit = {}
) {
    val context = LocalContext.current

    var state by remember { mutableStateOf(CameraUiState()) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }
    var currentScene by remember { mutableStateOf<com.a56camera.pipeline.SceneOptimizer.SceneType?>(null) }

    // SurfaceView tabanlı gerçek donanım preview
    val previewView = remember(context) {
        Camera2PreviewView(context)
    }

    // Camera2Manager referansı
    val cameraManager = remember(previewView) {
        Camera2Manager(context, previewView)
    }

    // Kamera yaşam döngüsü yönetimi
    DisposableEffect(cameraManager) {
        cameraManager.onError = { errorMsg ->
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
        }
        cameraManager.onSceneDetected = { detection ->
            currentScene = if (detection.scene != com.a56camera.pipeline.SceneOptimizer.SceneType.AUTO) {
                detection.scene
            } else null
        }
        cameraManager.start()

        onDispose {
            cameraManager.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Canlı Donanım Önizlemesi (SurfaceView)
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Kılavuz Çizgileri (Grid)
        if (state.grid) {
            CameraGridOverlay(modifier = Modifier.fillMaxSize())
        }

        // 3. Odak Karesi (Vizör ortasında)
        FocusBrackets(modifier = Modifier.align(Alignment.Center))

        // 4. Üst Kontrol Barı (TopCenter)
        TopControlBar(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 12.dp, end = 12.dp),
            onStateChange = { newState ->
                state = newState
                cameraManager.flashMode = newState.flash
                cameraManager.isHighRes50Mp = newState.is50Mp
                cameraManager.aspectRatio = newState.ratio
            },
            onSettingsClick = onOpenSettings
        )

        // 4.5 Yapay Zeka Sahne Rozeti (Samsung S25 Tarzı AI Badge)
        AnimatedVisibility(
            visible = currentScene != null,
            enter = fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 244.dp)
        ) {
            currentScene?.let { scene ->
                AiSceneBadge(scene = scene)
            }
        }

        // 5. Zoom Seçici (BottomCenter)
        ZoomSelector(
            currentZoom = state.zoom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 188.dp),
            onZoomChange = { newZoom ->
                state = state.copy(zoom = newZoom)
                cameraManager.setZoomRatio(newZoom)
            }
        )

        // 6. Çekim Modu Şeridi (BottomCenter)
        ModeStrip(
            currentMode = state.mode,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 104.dp),
            onModeChange = { newMode ->
                state = state.copy(mode = newMode)
            }
        )

        // 7. Alt Kontrol Barı (Deklanşör, Kamera Çevir, Galeri)
        BottomControlBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
            isProcessing = isProcessing,
            onShutter = {
                if (isProcessing) return@BottomControlBar
                isProcessing = true
                processingMessage = "Yakalanıyor..."

                when (state.mode) {
                    CameraMode.PHOTO, CameraMode.PORTRAIT -> {
                        cameraManager.captureZslHdr(
                            location = null,
                            deviceRotation = 0,
                            onProgress = { step -> processingMessage = step },
                            onSaved = { uri ->
                                isProcessing = false
                                onPhotoCaptured(uri)
                                Toast.makeText(context, "Fotoğraf Kaydedildi (0ms ZSL)", Toast.LENGTH_SHORT).show()
                            },
                            onError = {
                                isProcessing = false
                                Toast.makeText(context, "Çekim hatası", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    CameraMode.NIGHT -> {
                        cameraManager.captureNight(
                            location = null,
                            deviceRotation = 0,
                            onProgress = { step -> processingMessage = step },
                            onSaved = { uri ->
                                isProcessing = false
                                onPhotoCaptured(uri)
                                Toast.makeText(context, "Gece Fotoğrafı Kaydedildi", Toast.LENGTH_SHORT).show()
                            },
                            onError = {
                                isProcessing = false
                                Toast.makeText(context, "Gece çekim hatası", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    CameraMode.VIDEO -> {
                        isProcessing = false
                        Toast.makeText(context, "Video kaydı yakında", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onFlip = {
                cameraManager.toggleLens()
            },
            onGallery = onOpenGallery
        )

        // 8. Multi-frame İşleme Bildirim Kartı (S25/iPhone tarzı HUD)
        AnimatedVisibility(
            visible = isProcessing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            ProcessingHud(message = processingMessage)
        }
    }
}

@Composable
private fun TopControlBar(
    state: CameraUiState,
    modifier: Modifier = Modifier,
    onStateChange: (CameraUiState) -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopIconChip(Icons.Default.Settings, active = false, onClick = onSettingsClick)

        TopTextChip(
            text = if (state.is50Mp) "50MP" else "12MP",
            active = state.is50Mp
        ) {
            onStateChange(state.copy(is50Mp = !state.is50Mp))
        }

        TopTextChip(
            text = if (state.ratio == AspectRatio.RATIO_4_3) "4:3" else "16:9",
            active = state.ratio == AspectRatio.RATIO_16_9
        ) {
            val nextRatio = if (state.ratio == AspectRatio.RATIO_4_3) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            onStateChange(state.copy(ratio = nextRatio))
        }

        TopIconChip(Icons.Default.GridOn, active = state.grid) {
            onStateChange(state.copy(grid = !state.grid))
        }

        TopIconChip(Icons.Default.HdrOn, active = state.hdr) {
            onStateChange(state.copy(hdr = !state.hdr))
        }

        TopIconChip(Icons.Default.Timer, active = state.timer > 0) {
            val next = when (state.timer) { 0 -> 3; 3 -> 10; else -> 0 }
            onStateChange(state.copy(timer = next))
        }

        val flashIcon = when (state.flash) {
            FlashMode.OFF -> Icons.Default.FlashOff
            FlashMode.AUTO -> Icons.Default.FlashAuto
            else -> Icons.Default.FlashOn
        }
        TopIconChip(flashIcon, active = state.flash != FlashMode.OFF) {
            val nextFlash = when (state.flash) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
            }
            onStateChange(state.copy(flash = nextFlash))
        }
    }
}

@Composable
private fun TopIconChip(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (active) AeroBlue.copy(alpha = 0.4f) else FrostWhite,
        label = "iconBg"
    )
    val border by animateColorAsState(
        targetValue = if (active) AeroCyan else FrostBorder,
        label = "iconBorder"
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) Color.White else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun TopTextChip(
    text: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (active) AeroBlue.copy(alpha = 0.4f) else FrostWhite,
        label = "textBg"
    )
    val border by animateColorAsState(
        targetValue = if (active) AeroCyan else FrostBorder,
        label = "textBorder"
    )

    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
private fun FocusBrackets(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(68.dp)
            .border(1.2.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
    )
}

@Composable
private fun CameraGridOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.drawBehind {
            val strokeWidth = 1.dp.toPx()
            val gridColor = Color.White.copy(alpha = 0.22f)

            val w1 = size.width / 3f
            val w2 = size.width * 2f / 3f
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(w1, 0f), end = androidx.compose.ui.geometry.Offset(w1, size.height), strokeWidth = strokeWidth)
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(w2, 0f), end = androidx.compose.ui.geometry.Offset(w2, size.height), strokeWidth = strokeWidth)

            val h1 = size.height / 3f
            val h2 = size.height * 2f / 3f
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, h1), end = androidx.compose.ui.geometry.Offset(size.width, h1), strokeWidth = strokeWidth)
            drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, h2), end = androidx.compose.ui.geometry.Offset(size.width, h2), strokeWidth = strokeWidth)
        }
    )
}

@Composable
private fun ZoomSelector(
    currentZoom: Float,
    modifier: Modifier = Modifier,
    onZoomChange: (Float) -> Unit
) {
    val levels = listOf(0.6f, 1.0f, 2.0f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        levels.forEach { value ->
            val active = abs(currentZoom - value) < 0.08f

            val bg by animateColorAsState(
                targetValue = if (active) FrostWhite else Color(0x33000000),
                label = "zoomBg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) AeroCyan else FrostBorder,
                label = "zoomBorder"
            )

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.dp, borderColor, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onZoomChange(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (value) {
                        0.6f -> ".6"
                        1.0f -> "1x"
                        2.0f -> "2x"
                        else -> "${value}x"
                    },
                    color = if (active) AeroCyan else Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ModeStrip(
    currentMode: CameraMode,
    modifier: Modifier = Modifier,
    onModeChange: (CameraMode) -> Unit
) {
    val modes = listOf(
        CameraMode.NIGHT to "GECE",
        CameraMode.PORTRAIT to "PORTRE",
        CameraMode.PHOTO to "FOTOĞRAF",
        CameraMode.VIDEO to "VİDEO"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { (itemMode, label) ->
            val active = currentMode == itemMode
            val textColor by animateColorAsState(
                targetValue = if (active) Color(0xFF142030) else Color.White.copy(alpha = 0.6f),
                label = "modeText"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onModeChange(itemMode) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun BottomControlBar(
    modifier: Modifier = Modifier,
    isProcessing: Boolean,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
    onGallery: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Galeri Önizleme Butonu
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FrostWhite)
                .border(1.dp, FrostBorder, RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onGallery
                )
        )

        // Deklanşör Butonu (S25 / iPhone tarzı)
        Box(
            modifier = Modifier
                .size(76.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.width * 0.65f
                        ),
                        radius = size.width * 0.65f,
                        center = center
                    )
                }
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(3.5.dp, Color.White.copy(alpha = 0.95f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isProcessing,
                    onClick = onShutter
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (isProcessing) Color(0xFFB0BEC5) else Color.White)
            )
        }

        // Kamera Çevir Butonu (Ön / Arka)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(FrostWhite)
                .border(1.dp, FrostBorder, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFlip
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Cameraswitch,
                contentDescription = "Kamerayı Çevir",
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Multi-frame çekim esnasında görünen şık HUD kartı.
 */
@Composable
private fun ProcessingHud(message: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xCC000000)
        ),
        modifier = Modifier.padding(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = AeroCyan,
                strokeWidth = 2.5.dp
            )
            Text(
                text = message.ifEmpty { "İşleniyor..." },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Exynos NPU Sahne Algılayıcı Rozeti (Samsung S25 AI Badge).
 */
@Composable
private fun AiSceneBadge(scene: com.a56camera.pipeline.SceneOptimizer.SceneType) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xD9101C2B),
        border = androidx.compose.foundation.BorderStroke(1.dp, AeroCyan.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = scene.icon, fontSize = 15.sp)
            Text(
                text = scene.labelTr,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

