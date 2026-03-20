package com.birdmidi.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var audioAnalyzer: AudioAnalyzer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioAnalyzer = AudioAnalyzer(this)
        setContent {
            BirdMidiTheme {
                BirdMidiApp(audioAnalyzer)
            }
        }
    }
}

@Composable
fun BirdMidiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            secondary = Color(0xFFFF4081),
            background = Color(0xFF0A0A1A),
            surface = Color(0xFF1A1A2E)
        ),
        content = content
    )
}

@Composable
fun BirdMidiApp(analyzer: AudioAnalyzer) {
    val context = LocalContext.current
    val midiExporter = remember { MidiExporter(context) }
    
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) }
    var bars by remember { mutableFloatStateOf(4f) }
    val uiPitch by analyzer.uiPitch.collectAsState()
    val amplitude by analyzer.amplitude.collectAsState()
    val uiConfidence by analyzer.uiConfidence.collectAsState()
    val sampleRate by analyzer.sampleRate.collectAsState()
    val errorState by analyzer.errorState.collectAsState()
    val modelInfo by analyzer.modelInfo.collectAsState()
    val detectedNotes by analyzer.detectedNotes.collectAsState()
    
    val amplitudeHistory = remember { mutableStateListOf<Float>() }
    
    LaunchedEffect(amplitude) {
        if (isRecording) {
            amplitudeHistory.add(amplitude)
            if (amplitudeHistory.size > 300) {
                amplitudeHistory.removeAt(0)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
            analyzer.startRecording()
        } else {
            Toast.makeText(context, "Audio recording permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    val midiExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        uri?.let {
            val notes = detectedNotes
            if (notes.isNotEmpty()) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { stream ->
                        midiExporter.writeMidiToStream(stream, notes, recordingDuration, bars.toInt())
                        Toast.makeText(context, "Export Successful!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // App Branding (Icon at the top center)
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = "BirdMidi Icon",
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0A0A1A))
                    .padding(16.dp)
            )
            
            Text(
                "v1.3.3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            
            // Main Content Area
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Visualizer Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF121225))
                        .padding(16.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val centerY = height / 2
                        val step = width / 300f
                        
                        val gradient = Brush.verticalGradient(
                            colors = listOf(Color(0xFF00E5FF), Color(0xFF007BFF)),
                            startY = 0f,
                            endY = height
                        )
                        
                        for (i in 0 until amplitudeHistory.size) {
                            val x = i * step
                            val amp = amplitudeHistory[i] * height * 3
                            drawLine(
                                brush = gradient,
                                start = Offset(x, centerY - amp),
                                end = Offset(x, centerY + amp),
                                strokeWidth = 3f
                            )
                        }
                    }
                    
                    if (uiPitch > 0) {
                        Text(
                            "%.0f Hz".format(uiPitch),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OUTPUT SETTINGS", style = MaterialTheme.typography.labelLarge)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Bars: ${bars.toInt()}", style = MaterialTheme.typography.bodyLarge)
                            Text("Steps: ${bars.toInt() * 16}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = bars,
                            onValueChange = { bars = it },
                            valueRange = 1f..8f,
                            steps = 7,
                            modifier = Modifier.weight(2f)
                        )
                    }
                    
                    // Debug Info
                    Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Detections: ${detectedNotes.size}", style = MaterialTheme.typography.labelSmall)
                        Text("Rate: ${sampleRate / 1000}k", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("Hz: ${if (uiPitch > 0) uiPitch.toInt().toString() else "---"}", style = MaterialTheme.typography.labelSmall)
                        Text("Conf: ${(uiConfidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    
                    if (errorState.isNotEmpty()) {
                        Text(
                            text = "Error: $errorState",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Red
                        )
                    } else {
                        Text(
                            text = modelInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            }

            // Main Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = {
                        if (isRecording) {
                            recordingDuration = analyzer.stopRecording()
                            isRecording = false
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                amplitudeHistory.clear()
                                isRecording = true
                                analyzer.startRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    containerColor = if (isRecording) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Filled.Mic,
                        contentDescription = "Record",
                        modifier = Modifier.size(40.dp)
                    )
                }
                
                AnimatedVisibility(
                    visible = !isRecording && detectedNotes.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    IconButton(
                        onClick = {
                            if (detectedNotes.isNotEmpty()) {
                                midiExportLauncher.launch("bird_song_${System.currentTimeMillis()}.mid")
                            } else {
                                Toast.makeText(context, "No notes detected yet!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.padding(start = 32.dp).size(64.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Export", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
