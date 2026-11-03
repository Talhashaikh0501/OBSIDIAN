
package com.obsidian.assistant

import androidx.core.content.ContextCompat

import androidx.compose.ui.platform.LocalContext

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import android.content.pm.PackageManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.Manifest

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val VoidBlack = Color(0xFF020305)
private val ChromeWhite = Color(0xFFF1F5F8)
private val LiquidSilver = Color(0xFFB9C5CE)
private val IceBlue = Color(0xFFBCEBFF)
private val DimSilver = Color(0xFF697985)

@Composable
fun ObsidianHomeScreen() {
    val context = LocalContext.current
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
    }

    val speechEngine = remember(context) { OfflineSpeechEngine(context.applicationContext) }
    val voiceEngine = remember(context) { OfflineVoiceEngine(context.applicationContext) }
    val neuralVoiceEngine = remember(context) { NeuralVoiceEngine(context.applicationContext) }
    val microphoneRecorder = remember { MicrophoneRecorder() }
    val scope = rememberCoroutineScope()
    var engineReady by remember { mutableStateOf(speechEngine.isModelReady()) }
    var isRecording by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var engineStatus by remember {
        mutableStateOf(if (engineReady) "APEX OFFLINE VOICE READY" else "APEX MODEL SETUP REQUIRES INTERNET")
    }
    var transcript by remember { mutableStateOf("") }
    var voiceReady by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("INITIALIZING OFFLINE VOICE") }
    var isSpeaking by remember { mutableStateOf(false) }
    var neuralReady by remember { mutableStateOf(neuralVoiceEngine.isReady) }
    var neuralBusy by remember { mutableStateOf(false) }
    var neuralSpeaking by remember { mutableStateOf(false) }
    var neuralStatus by remember { mutableStateOf("ROBOTIC VOICE NOT INSTALLED • APPROX. 145 MB") }

    DisposableEffect(Unit) {
        voiceEngine.initialize { ready, message ->
            scope.launch(Dispatchers.Main) {
                voiceReady = ready
                voiceStatus = message
            }
        }
        onDispose {
            microphoneRecorder.cancel()
            voiceEngine.shutdown()
            neuralVoiceEngine.shutdown()
        }
    }

    val handleActivation: () -> Unit = {
        when {
            !micPermissionGranted -> microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            isBusy -> Unit
            !engineReady -> {
                isBusy = true
                engineStatus = "PREPARING HINGLISH APEX MODEL"
                scope.launch {
                    try {
                        speechEngine.prepare { message ->
                            scope.launch(Dispatchers.Main) { engineStatus = message }
                        }
                        engineReady = true
                        engineStatus = "APEX OFFLINE VOICE READY"
                    } catch (error: Exception) {
                        engineStatus = error.message?.take(90) ?: "APEX MODEL DOWNLOAD FAILED"
                    } finally {
                        isBusy = false
                    }
                }
            }
            !isRecording -> {
                try {
                    microphoneRecorder.start()
                    transcript = ""
                    isRecording = true
                    engineStatus = "LISTENING — TAP TO STOP"
                } catch (error: Exception) {
                    engineStatus = error.message ?: "MICROPHONE START FAILED"
                }
            }
            else -> {
                isRecording = false
                val samples = microphoneRecorder.stop()
                if (samples.isEmpty()) {
                    engineStatus = "NO AUDIO CAPTURED — TRY AGAIN"
                } else {
                    isBusy = true
                    engineStatus = "PROCESSING SPEECH ON DEVICE"
                    scope.launch {
                        try {
                            val result = speechEngine.transcribe(samples)
                            transcript = result.ifBlank { "No speech detected. Please try again." }
                            engineStatus = "LOCAL TRANSCRIPTION COMPLETE"
                        } catch (error: Exception) {
                            engineStatus = error.message?.take(90) ?: "TRANSCRIPTION FAILED"
                        } finally {
                            isBusy = false
                        }
                    }
                }
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "obsidian_core")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(24000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_rotation"
    )

    val reverseRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(17000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reverse_rotation"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    val glow by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "energy_glow"
    )

    val scan by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_motion"
    )

    val stars = remember {
        val random = Random(42)
        List(190) {
            Triple(
                random.nextFloat(),
                random.nextFloat(),
                random.nextFloat()
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
    ) {
        // COSMIC BACKGROUND
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF26333D).copy(alpha = 0.42f),
                        Color(0xFF101820).copy(alpha = 0.22f),
                        VoidBlack
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.43f),
                    radius = size.width * 0.9f
                )
            )

            stars.forEachIndexed { index, star ->
                val x = star.first * size.width
                val y = star.second * size.height
                val twinkle = 0.35f +
                        (0.5f + 0.5f * sin(glow * 5f + index)) * 0.55f

                val radius = (0.5f + star.third * 1.25f) *
                        if (index % 19 == 0) 1.8f else 1f

                drawCircle(
                    color = ChromeWhite.copy(alpha = twinkle * 0.65f),
                    radius = radius.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            // Distant atmospheric light
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        IceBlue.copy(alpha = glow * 0.11f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.43f),
                    radius = size.width * 0.65f
                ),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.5f, size.height * 0.43f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "OBSIDIAN",
                        color = ChromeWhite,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 7.sp
                    )

                    Text(
                        "ARTIFICIAL INTELLIGENCE",
                        color = DimSilver,
                        fontSize = 7.sp,
                        letterSpacing = 2.5.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (micPermissionGranted) IceBlue else DimSilver,
                                CircleShape
                            )
                    )

                    Text(
                        when {
                            isRecording -> "LISTENING"
                            engineReady -> "VOICE READY"
                            micPermissionGranted -> "MIC READY"
                            else -> "DORMANT"
                        },
                        color = LiquidSilver,
                        fontSize = 9.sp,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "BEYOND HUMAN COMPREHENSION",
                color = DimSilver,
                fontSize = 8.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // CENTRAL SILVER ENTITY
            Box(
                modifier = Modifier.size(width = 330.dp, height = 370.dp),
                contentAlignment = Alignment.Center
            ) {
                // ROTATING ENERGY SYSTEM
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension * 0.43f

                    // Energy atmosphere
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                IceBlue.copy(alpha = glow * 0.22f),
                                LiquidSilver.copy(alpha = glow * 0.08f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius * 1.5f * pulse
                        ),
                        radius = radius * 1.5f * pulse,
                        center = center
                    )

                    // Outer orbit
                    rotate(rotation, center) {
                        drawOval(
                            color = ChromeWhite.copy(alpha = 0.55f),
                            topLeft = Offset(
                                center.x - radius * 1.25f,
                                center.y - radius * 0.42f
                            ),
                            size = Size(radius * 2.5f, radius * 0.84f),
                            style = Stroke(width = 1.1.dp.toPx())
                        )

                        // Orbital energy markers
                        for (i in 0 until 36) {
                            rotate(i * 10f, center) {
                                drawLine(
                                    color = IceBlue.copy(alpha = 0.55f),
                                    start = Offset(center.x, center.y - radius * 1.22f),
                                    end = Offset(center.x, center.y - radius * 1.28f),
                                    strokeWidth = 1.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }

                    // Inner orbit
                    rotate(reverseRotation, center) {
                        drawOval(
                            color = IceBlue.copy(alpha = 0.34f),
                            topLeft = Offset(
                                center.x - radius * 1.04f,
                                center.y - radius * 0.63f
                            ),
                            size = Size(radius * 2.08f, radius * 1.26f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Mechanical circular arcs
                    rotate(rotation * 0.45f, center) {
                        drawArc(
                            color = ChromeWhite.copy(alpha = 0.72f),
                            startAngle = 195f,
                            sweepAngle = 75f,
                            useCenter = false,
                            topLeft = Offset(
                                center.x - radius * 1.12f,
                                center.y - radius * 1.12f
                            ),
                            size = Size(radius * 2.24f, radius * 2.24f),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        drawArc(
                            color = IceBlue.copy(alpha = 0.65f),
                            startAngle = 15f,
                            sweepAngle = 48f,
                            useCenter = false,
                            topLeft = Offset(
                                center.x - radius * 1.12f,
                                center.y - radius * 1.12f
                            ),
                            size = Size(radius * 2.24f, radius * 2.24f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // SILVER SURFER PORTRAIT
                Box(
                    modifier = Modifier
                        .size(width = 265.dp, height = 335.dp)
                        .graphicsLayer {
                            scaleX = 0.96f * pulse
                            scaleY = 0.96f * pulse
                        }
                        .clip(CircleShape)
                        .background(Color(0xFF080B0F)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            id = R.drawable.obsidian_silver_entity
                        ),
                        contentDescription = "OBSIDIAN silver humanoid entity",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Dark cinematic edge shading
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.12f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.42f)
                                    )
                                )
                            )
                    )

                    // Moving scan beam
                    Canvas(Modifier.fillMaxSize()) {
                        val scanY = ((scan + 1f) / 2f) * size.height

                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    IceBlue.copy(alpha = 0.12f),
                                    ChromeWhite.copy(alpha = 0.75f),
                                    IceBlue.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            ),
                            start = Offset(0f, scanY),
                            end = Offset(size.width, scanY),
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    // Chrome border
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(
                                width = 1.2.dp,
                                color = ChromeWhite.copy(alpha = 0.48f),
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "THE SILVER INTELLIGENCE",
                color = ChromeWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(9.dp))

            Text(
                engineStatus,
                color = if (isRecording) IceBlue else DimSilver,
                fontSize = 8.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            if (transcript.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    transcript,
                    color = ChromeWhite,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                voiceStatus,
                color = if (voiceReady) IceBlue else DimSilver,
                fontSize = 7.sp,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111820))
                    .border(1.dp, LiquidSilver.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .clickable(enabled = voiceReady && !isSpeaking) {
                        isSpeaking = true
                        voiceEngine.speak("I am Obsidian. Systems are online. State your command.") { success ->
                            scope.launch(Dispatchers.Main) {
                                isSpeaking = false
                                if (!success) voiceStatus = "VOICE PLAYBACK FAILED"
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        isSpeaking -> "OBSIDIAN IS SPEAKING"
                        voiceReady -> "TEST ROBOTIC VOICE"
                        else -> "OFFLINE VOICE UNAVAILABLE"
                    },
                    color = if (voiceReady) ChromeWhite else DimSilver,
                    fontSize = 9.sp,
                    letterSpacing = 2.2.sp
                )
            }

            Spacer(Modifier.height(5.dp))
            Text(
                neuralStatus,
                color = if (neuralReady) IceBlue else DimSilver,
                fontSize = 7.sp,
                letterSpacing = 1.1.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B1117))
                    .border(1.dp, IceBlue.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !neuralBusy && !neuralSpeaking) {
                        if (!neuralReady) {
                            neuralBusy = true
                            neuralStatus = "PREPARING OFFLINE NEURAL VOICE"
                            scope.launch {
                                try {
                                    neuralVoiceEngine.prepare { message ->
                                        scope.launch(Dispatchers.Main) { neuralStatus = message }
                                    }
                                    neuralReady = true
                                    neuralStatus = "SUPERTONIC 3 • MALE VOICE M2 READY"
                                } catch (error: Exception) {
                                    neuralStatus = error.message?.take(80) ?: "NEURAL VOICE SETUP FAILED"
                                } finally {
                                    neuralBusy = false
                                }
                            }
                        } else {
                            neuralSpeaking = true
                            scope.launch {
                                try {
                                    val success = neuralVoiceEngine.speak(
                                        "I am Obsidian. Systems are online. State your command."
                                    )
                                    neuralStatus = if (success) {
                                        "SUPERTONIC M2 PLAYBACK COMPLETE"
                                    } else {
                                        "NEURAL VOICE PLAYBACK FAILED"
                                    }
                                } catch (error: Exception) {
                                    neuralStatus = error.message?.take(80) ?: "NEURAL VOICE PLAYBACK FAILED"
                                } finally {
                                    neuralSpeaking = false
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        neuralBusy -> "PREPARING NEURAL VOICE..."
                        neuralSpeaking -> "OBSIDIAN IS SPEAKING"
                        neuralReady -> "TEST NEURAL VOICE"
                        else -> "INSTALL NEURAL VOICE • 145 MB"
                    },
                    color = if (neuralReady || neuralBusy) ChromeWhite else LiquidSilver,
                    fontSize = 8.sp,
                    letterSpacing = 1.7.sp
                )
            }

            Spacer(Modifier.weight(1f))

            // ACTIVATION CONTROL
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF687783),
                                Color(0xFF202A32),
                                Color(0xFF687783)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = ChromeWhite.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { handleActivation() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        isBusy -> "INITIALIZING..."
                        isRecording -> "TAP TO STOP"
                        engineReady -> "TAP TO SPEAK"
                        micPermissionGranted -> "PREPARE APEX VOICE"
                        else -> "ENABLE VOICE ACCESS"
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp
                )
            }

            Spacer(Modifier.height(13.dp))

            Text(
                when {
                    isRecording -> "SPEAK NOW • MAXIMUM 60 SECONDS"
                    engineReady -> "ENGLISH + HINGLISH • ON-DEVICE PROCESSING"
                    micPermissionGranted -> "ONE-TIME APEX DOWNLOAD • APPROX. 1 GB"
                    else -> "TAP TO GRANT MICROPHONE PERMISSION"
                },
                color = DimSilver,
                fontSize = 8.sp,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(25.dp))

            Text(
                "CONCEIVED AND DEVELOPED",
                color = Color(0xFF59636C),
                fontSize = 7.sp,
                letterSpacing = 2.5.sp
            )

            Text(
                "BY TALHA",
                color = LiquidSilver,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(22.dp))
        }
    }
}
