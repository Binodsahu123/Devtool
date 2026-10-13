package com.swift.browser.batterysaverengine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

class BatterySaverEngineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    BatterySaverScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatterySaverScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    
    var isEnabled by remember { mutableStateOf(prefs.getBoolean("battery_saver_enabled", false)) }

    var batteryLevel by remember { mutableStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    var batteryTemp by remember { mutableStateOf(0f) }
    var batteryVoltage by remember { mutableStateOf(0f) }
    var batteryHealth by remember { mutableStateOf("Good") }
    var batteryTechnology by remember { mutableStateOf("Li-ion") }

    // System Battery Status Receiver loop
    LaunchedEffect(Unit) {
        while (isActive) {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)

            batteryStatus?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevel = if (level != -1 && scale != -1) {
                    (level * 100 / scale.toFloat()).toInt()
                } else {
                    100
                }

                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val rawTemp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                batteryTemp = rawTemp / 10f

                val rawVoltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                batteryVoltage = if (rawVoltage > 1000) rawVoltage / 1000f else rawVoltage.toFloat()

                val health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                batteryHealth = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                    else -> "Normal"
                }

                batteryTechnology = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"
            }
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Swift Battery Optimizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A))
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // BATTERY SHAPE DRAWN VIA CANVAS FOR MAXIMUM ACCURACY AND ZERO UNRESOLVED IMPORTS
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(
                        color = when {
                            isCharging -> Color(0xFF10B981).copy(alpha = 0.15f)
                            batteryLevel <= 20 -> Color(0xFFEF4444).copy(alpha = 0.15f)
                            isEnabled -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                            else -> Color(0xFF334155).copy(alpha = 0.4f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val iconColor = when {
                        isCharging -> Color(0xFF10B981)
                        batteryLevel <= 20 -> Color(0xFFEF4444)
                        isEnabled -> Color(0xFFF59E0B)
                        else -> Color(0xFF38BDF8)
                    }

                    // Render dynamic hardware battery shape
                    Canvas(modifier = Modifier.size(54.dp, 80.dp)) {
                        val strokeWidth = 3.dp.toPx()
                        val capHeight = 8.dp.toPx()
                        val corner = 6.dp.toPx()
                        
                        // Draw battery cap on top
                        drawRoundRect(
                            color = iconColor,
                            topLeft = Offset((size.width - 18.dp.toPx()) / 2f, 0f),
                            size = Size(18.dp.toPx(), capHeight),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                        
                        // Draw battery outer body
                        drawRoundRect(
                            color = iconColor,
                            topLeft = Offset(0f, capHeight),
                            size = Size(size.width, size.height - capHeight),
                            cornerRadius = CornerRadius(corner, corner),
                            style = Stroke(width = strokeWidth)
                        )
                        
                        // Draw battery filled progress
                        val innerMargin = strokeWidth + 4.dp.toPx()
                        val fillHeightMax = size.height - capHeight - (innerMargin * 2f)
                        val fillHeight = fillHeightMax * (batteryLevel / 100f)
                        val fillWidth = size.width - (innerMargin * 2f)
                        
                        drawRoundRect(
                            color = iconColor,
                            topLeft = Offset(innerMargin, size.height - innerMargin - fillHeight),
                            size = Size(fillWidth, fillHeight),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$batteryLevel%",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isCharging) "Power Source Connected" else "Optimized Performance",
                fontSize = 24.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Extends device battery backup while browsing. Disables intensive scripts and stops high-power WebView layouts.",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Ultra Battery Saver", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Disables auto-image loading & software renders webpages to save GPU cycles.", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            isEnabled = checked
                            prefs.edit().putBoolean("battery_saver_enabled", checked).apply()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, "Diagnostics Indicator", tint = Color(0xFFF59E0B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hardware Diagnostics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Battery Temperature", color = Color(0xFF94A3B8))
                        Text(String.format(Locale.US, "%.1f °C", batteryTemp), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Voltage Rate", color = Color(0xFF94A3B8))
                        Text(String.format(Locale.US, "%.3f V", batteryVoltage), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Battery Health", color = Color(0xFF94A3B8))
                        Text(batteryHealth, color = if (batteryHealth == "Good") Color(0xFF10B981) else Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    }
                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cell Technology", color = Color(0xFF94A3B8))
                        Text(batteryTechnology, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
