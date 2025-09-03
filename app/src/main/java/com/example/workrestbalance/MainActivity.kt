
package com.example.workrestbalance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

enum class Mode { WORK, BREAK, LONGBREAK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Preferences (simple SharedPreferences)
    val prefs = remember { ctx.getSharedPreferences("wrb", Context.MODE_PRIVATE) }
    var workMin by remember { mutableIntStateOf(prefs.getInt("work", 50)) }
    var breakMin by remember { mutableIntStateOf(prefs.getInt("break", 10)) }
    var longBreakMin by remember { mutableIntStateOf(prefs.getInt("longbreak", 20)) }
    var cyclesToLong by remember { mutableIntStateOf(prefs.getInt("cyclesToLong", 3)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("autoStart", true)) }
    var vibrateOnEnd by remember { mutableStateOf(prefs.getBoolean("vibrate", true)) }

    var mode by remember { mutableStateOf(Mode.WORK) }
    var running by remember { mutableStateOf(false) }
    var cyclesSinceLong by remember { mutableIntStateOf(0) }
    var completed by remember { mutableIntStateOf(0) }

    fun savePrefs() {
        prefs.edit()
            .putInt("work", workMin)
            .putInt("break", breakMin)
            .putInt("longbreak", longBreakMin)
            .putInt("cyclesToLong", cyclesToLong)
            .putBoolean("autoStart", autoStart)
            .putBoolean("vibrate", vibrateOnEnd)
            .apply()
    }

    LaunchedEffect(workMin, breakMin, longBreakMin, cyclesToLong, autoStart, vibrateOnEnd) { savePrefs() }

    // duration per mode
    fun durationFor(m: Mode) = when (m) {
        Mode.WORK -> workMin * 60_000L
        Mode.BREAK -> breakMin * 60_000L
        Mode.LONGBREAK -> longBreakMin * 60_000L
    }

    var millisLeft by remember { mutableLongStateOf(durationFor(mode)) }

    // Notifications permission request for Android 13+
    val requestNotif = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* ignore result */ }
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        createNotificationChannel(ctx)
    }

    // Timer
    var timer by remember { mutableStateOf<CountDownTimer?>(null) }
    fun startTimer() {
        timer?.cancel()
        running = true
        timer = object : CountDownTimer(millisLeft, 1000) {
            override fun onTick(ms: Long) { millisLeft = ms }
            override fun onFinish() {
                millisLeft = 0
                if (vibrateOnEnd) vibrate(ctx)
                showNotify(ctx,
                    if (mode == Mode.WORK) "Пора отдохнуть" else "Возвращаемся к работе",
                    if (mode == Mode.WORK) "Сделай перерыв" else "Новый цикл начинается"
                )
                if (mode == Mode.WORK) {
                    completed += 1
                    cyclesSinceLong += 1
                    mode = if (cyclesSinceLong >= cyclesToLong) {
                        cyclesSinceLong = 0
                        Mode.LONGBREAK
                    } else Mode.BREAK
                } else {
                    mode = Mode.WORK
                }
                millisLeft = durationFor(mode)
                if (autoStart) startTimer() else running = false
            }
        }.start()
    }
    fun pauseTimer() { timer?.cancel(); running = false }
    fun resetTimer() { pauseTimer(); millisLeft = durationFor(mode) }

    val total = durationFor(mode).toFloat()
    val progress by animateFloatAsState(targetValue = 1f - (millisLeft / total), label = "progress")

    var tab by remember { mutableIntStateOf(0) } // 0 timer, 1 settings

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (mode == Mode.WORK) Color(0xFFF0F7FF) else Color(0xFFF2FFF6)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopAppBar(
                title = { Text("Work&Rest Balance", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { tab = 1 - tab }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                // TIMER SCREEN
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val label = when (mode) {
                        Mode.WORK -> "Работа"
                        Mode.BREAK -> "Перерыв"
                        Mode.LONGBREAK -> "Длинный перерыв"
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(label) },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    TimerDial(progress = progress, millisLeft = millisLeft)
                    Spacer(Modifier.height(12.dp))
                    Text("Прогресс: ${(progress * 100).toInt()}%", color = Color.Gray, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { if (running) pauseTimer() else startTimer() },
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (running) "Пауза" else "Старт")
                        }
                        OutlinedButton(onClick = { resetTimer() }, shape = RoundedCornerShape(24.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp)); Text("Сброс")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == Mode.WORK, onClick = { mode = Mode.WORK; resetTimer() }, label = { Text("Работа") })
                        FilterChip(selected = mode == Mode.BREAK, onClick = { mode = Mode.BREAK; resetTimer() }, label = { Text("Перерыв") })
                        FilterChip(selected = mode == Mode.LONGBREAK, onClick = { mode = Mode.LONGBREAK; resetTimer() }, label = { Text("Длинный") })
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Сделано циклов: $completed  •  До длинного: ${cyclesToLong - cyclesSinceLong}",
                        color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                // SETTINGS
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Настройки", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    SettingsSlider("Работа (мин)", workMin, 10, 120) { workMin = it; if (mode == Mode.WORK) resetTimer() }
                    SettingsSlider("Перерыв (мин)", breakMin, 1, 60) { breakMin = it; if (mode != Mode.WORK) resetTimer() }
                    SettingsSlider("Длинный перерыв (мин)", longBreakMin, 5, 60) { longBreakMin = it; if (mode == Mode.LONGBREAK) resetTimer() }
                    SettingsSlider("Циклов до длинного", cyclesToLong, 1, 8) { cyclesToLong = it }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AssistChip(onClick = { autoStart = !autoStart }, label = { Text(if (autoStart) "Автозапуск: Вкл" else "Автозапуск: Выкл") })
                        AssistChip(onClick = { vibrateOnEnd = !vibrateOnEnd }, label = { Text(if (vibrateOnEnd) "Вибрация: Вкл" else "Вибрация: Выкл") })
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Совет: 20-20-20 — каждые 20 минут смотри вдаль 20 секунд", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsSlider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("$label: $value", fontWeight = FontWeight.Medium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0)
        )
    }
}

@Composable
fun TimerDial(progress: Float, millisLeft: Long) {
    val minutes = (millisLeft / 1000) / 60
    val seconds = (millisLeft / 1000) % 60
    val label = String.format("%02d:%02d", minutes, seconds)

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 16f
            // Background circle
            drawArc(
                color = Color(0xFFE2E8F0),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                size = Size(size.width, size.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Progress
            drawArc(
                color = Color(0xFF38BDF8),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                size = Size(size.width, size.height),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text("осталось", color = Color.Gray)
        }
    }
}

fun vibrate(ctx: Context) {
    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    } else @Suppress("DEPRECATION") {
        v.vibrate(300)
    }
}

fun createNotificationChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel("wrb", "Work&Rest", NotificationManager.IMPORTANCE_DEFAULT)
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

fun showNotify(ctx: Context, title: String, text: String) {
    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notif = NotificationCompat.Builder(ctx, "wrb")
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setAutoCancel(true)
        .build()
    manager.notify(1, notif)
}
