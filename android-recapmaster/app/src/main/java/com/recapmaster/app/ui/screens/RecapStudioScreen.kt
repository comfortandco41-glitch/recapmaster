package com.recapmaster.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recapmaster.app.engine.BlurBoxConfig
import com.recapmaster.app.pipeline.PipelineStage
import com.recapmaster.app.pipeline.RecapPipelineManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapStudioScreen(pipelineManager: RecapPipelineManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipelineState by pipelineManager.state.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var selectedVoice by remember { mutableStateOf("my-MM-ThihaNeural") }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // Blur box
    var blurEnabled by remember { mutableStateOf(false) }
    var blurX by remember { mutableFloatStateOf(0.78f) }
    var blurY by remember { mutableFloatStateOf(0.04f) }
    var blurW by remember { mutableFloatStateOf(0.18f) }
    var blurH by remember { mutableFloatStateOf(0.08f) }
    var blurStrength by remember { mutableIntStateOf(16) }

    // Subtitle placement
    var subtitlePlacement by remember { mutableStateOf("bottom") }

    val isProcessing = pipelineState.stage != PipelineStage.IDLE &&
            pipelineState.stage != PipelineStage.COMPLETED &&
            pipelineState.stage != PipelineStage.FAILED

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFFA855F7))
                        Text("RecapMaster AI Studio", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF18181B),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF09090B)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: URL Input
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF18181B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Input Video (YouTube or Bilibili)", fontWeight = FontWeight.SemiBold, color = Color.White)
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://www.youtube.com/... or https://b23.tv/...", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFFA855F7)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFA855F7),
                            unfocusedBorderColor = Color(0xFF27272A)
                        )
                    )

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        placeholder = { Text("Gemini API Key (for Burmese translation)", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF38BDF8)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF27272A)
                        )
                    )
                }
            }

            // Card 2: Voice & Dubbing
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF18181B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("2. Burmese Dubbing Voice (Microsoft Edge TTS)", fontWeight = FontWeight.SemiBold, color = Color.White)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedVoice == "my-MM-ThihaNeural",
                            onClick = { selectedVoice = "my-MM-ThihaNeural" },
                            label = { Text("👨 Thiha (Male)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedVoice == "my-MM-NilarNeural",
                            onClick = { selectedVoice = "my-MM-NilarNeural" },
                            label = { Text("👩 Nilar (Female)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Speed Slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Playback Speed (Audio + Video Linked):", fontSize = 12.sp, color = Color.LightGray)
                            Text("${String.format("%.2f", playbackSpeed)}×", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24))
                        }
                        Slider(
                            value = playbackSpeed,
                            onValueChange = { playbackSpeed = it },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFBBF24), activeTrackColor = Color(0xFFFBBF24))
                        )
                    }
                }
            }

            // Card 3: Watermark Blur Box & Subtitles
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF18181B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("3. Logo / Subtitle Blur Removal", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Switch(checked = blurEnabled, onCheckedChange = { blurEnabled = it })
                    }

                    if (blurEnabled) {
                        Text("Box Position X: ${(blurX * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
                        Slider(value = blurX, onValueChange = { blurX = it }, valueRange = 0f..1f)

                        Text("Box Position Y: ${(blurY * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
                        Slider(value = blurY, onValueChange = { blurY = it }, valueRange = 0f..1f)

                        Text("Box Width: ${(blurW * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
                        Slider(value = blurW, onValueChange = { blurW = it }, valueRange = 0.05f..1f)

                        Text("Box Height: ${(blurH * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
                        Slider(value = blurH, onValueChange = { blurH = it }, valueRange = 0.02f..0.5f)
                    }

                    Divider(color = Color(0xFF27272A))

                    Text("Burmese Subtitle Placement", fontWeight = FontWeight.SemiBold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("bottom", "top", "center").forEach { placement ->
                            FilterChip(
                                selected = subtitlePlacement == placement,
                                onClick = { subtitlePlacement = placement },
                                label = { Text(placement.capitalize()) }
                            )
                        }
                    }
                }
            }

            // Generate Button
            Button(
                onClick = {
                    scope.launch {
                        pipelineManager.executePipeline(
                            videoUrl = urlInput,
                            geminiApiKey = geminiKey,
                            voiceName = selectedVoice,
                            playbackSpeed = playbackSpeed,
                            blurBox = BlurBoxConfig(
                                enabled = blurEnabled,
                                xPct = blurX,
                                yPct = blurY,
                                wPct = blurW,
                                hPct = blurH,
                                strength = blurStrength
                            ),
                            subtitlePlacement = subtitlePlacement
                        )
                    }
                },
                enabled = !isProcessing && urlInput.isNotBlank() && geminiKey.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7))
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Processing On-Device...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("⚡ Generate Movie Recap Video", fontWeight = FontWeight.Bold)
                }
            }

            // Progress Banner
            if (isProcessing) {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1E1B4B))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🎬 ${pipelineState.message}", color = Color(0xFFC084FC), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = { pipelineState.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFFA855F7),
                            trackColor = Color(0xFF312E81)
                        )
                    }
                }
            }

            // Completion Banner
            if (pipelineState.stage == PipelineStage.COMPLETED && pipelineState.finalVideoUri != null) {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF064E3B))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399))
                            Text("Recap Video Ready!", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Text(
                            "The recap video has been exported to your phone's Gallery (Movies/RecapMaster). All temporary source video files have been cleaned up.",
                            fontSize = 12.sp,
                            color = Color(0xFFA7F3D0)
                        )
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(pipelineState.finalVideoUri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Play Recap Video"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open in Video Player")
                        }
                    }
                }
            }

            // Error Banner
            if (pipelineState.stage == PipelineStage.FAILED) {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF450A0A))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("❌ Error Occurred", fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
                        Text(pipelineState.error ?: "Unknown error", fontSize = 12.sp, color = Color(0xFFFCA5A5))
                    }
                }
            }
        }
    }
}
