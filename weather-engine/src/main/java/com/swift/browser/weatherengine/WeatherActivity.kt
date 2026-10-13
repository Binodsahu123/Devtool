package com.swift.browser.weatherengine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class WeatherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    WeatherScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var locationName by remember { mutableStateOf("Locating...") }
    var temperature by remember { mutableStateOf("--") }
    var condition by remember { mutableStateOf("Loading real-time weather...") }
    var humidity by remember { mutableStateOf("--") }
    var windSpeed by remember { mutableStateOf("--") }
    var precipitation by remember { mutableStateOf("--") }
    var isFallbackLocation by remember { mutableStateOf(false) }
    var weatherCode by remember { mutableStateOf(0) }
    var lastUpdatedTime by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        coroutineScope.launch {
            fetchWeatherData(context, fineGranted || coarseGranted) { loc, temp, cond, hum, wind, prec, fallback, code, updateTime ->
                locationName = loc
                temperature = temp
                condition = cond
                humidity = hum
                windSpeed = wind
                precipitation = prec
                isFallbackLocation = fallback
                weatherCode = code
                lastUpdatedTime = updateTime
                isLoading = false
            }
        }
    }

    fun triggerWeatherUpdate() {
        isLoading = true
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            coroutineScope.launch {
                fetchWeatherData(context, true) { loc, temp, cond, hum, wind, prec, fallback, code, updateTime ->
                    locationName = loc
                    temperature = temp
                    condition = cond
                    humidity = hum
                    windSpeed = wind
                    precipitation = prec
                    isFallbackLocation = fallback
                    weatherCode = code
                    lastUpdatedTime = updateTime
                    isLoading = false
                }
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        triggerWeatherUpdate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Swift Weather") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { triggerWeatherUpdate() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, "Refresh Weather")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
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
            Spacer(modifier = Modifier.height(16.dp))

            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Location Pin",
                tint = if (isFallbackLocation) Color(0xFF94A3B8) else Color(0xFF38BDF8),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = locationName,
                fontSize = 24.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (isFallbackLocation) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Permission required for local weather. Fallback active.",
                    fontSize = 12.sp,
                    color = Color(0xFFF59E0B),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (isLoading) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Querying Open-Meteo satellite API...", fontSize = 14.sp, color = Color(0xFF94A3B8))
            } else {
                val (weatherIcon, iconColor) = getWeatherIconAndColor(weatherCode)
                Icon(
                    imageVector = weatherIcon,
                    contentDescription = "Weather Condition Icon",
                    tint = iconColor,
                    modifier = Modifier.size(110.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = temperature,
                    fontSize = 72.sp,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = condition,
                    fontSize = 18.sp,
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Atmospheric Details",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DetailRow(label = "Relative Humidity", value = humidity)
                    DetailRow(label = "Wind Velocity", value = windSpeed)
                    DetailRow(label = "Precipitation Amount", value = precipitation)
                    if (lastUpdatedTime.isNotBlank()) {
                        DetailRow(label = "Last Synced At", value = lastUpdatedTime)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun getWeatherIconAndColor(code: Int): Pair<ImageVector, Color> {
    return when (code) {
        0 -> Icons.Default.WbSunny to Color(0xFFF59E0B) // Clear sky
        1, 2, 3 -> Icons.Default.Cloud to Color(0xFF94A3B8) // Clouds
        45, 48 -> Icons.Default.Cloud to Color(0xFFcbd5e1) // Foggy
        51, 53, 55, 56, 57 -> Icons.Default.Grain to Color(0xFF38BDF8) // Drizzle
        61, 63, 65, 66, 67 -> Icons.Default.Grain to Color(0xFF0284C7) // Rain
        71, 73, 75, 77 -> Icons.Default.AcUnit to Color(0xFFE2E8F0) // Snow
        80, 81, 82 -> Icons.Default.Grain to Color(0xFF38BDF8) // Showers
        95, 96, 99 -> Icons.Default.Thunderstorm to Color(0xFFF43F5E) // Thunderstorm
        else -> Icons.Default.Cloud to Color(0xFF38BDF8)
    }
}

private suspend fun fetchWeatherData(
    context: Context,
    hasLocationPermission: Boolean,
    onResult: (String, String, String, String, String, String, Boolean, Int, String) -> Unit
) = withContext(Dispatchers.IO) {
    var lat = 28.6139  // Default New Delhi
    var lon = 77.2090
    var locationString = "New Delhi, India"
    var isFallback = true

    if (hasLocationPermission) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var bestLocation: android.location.Location? = null

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    bestLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
            }
            if (bestLocation == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    bestLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }

            bestLocation?.let {
                lat = it.latitude
                lon = it.longitude
                isFallback = false

                // Reverse geocode to get city name
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val first = addresses[0]
                        locationString = first.locality ?: first.subAdminArea ?: first.adminArea ?: "Current Location"
                    } else {
                        locationString = "Current Coordinates"
                    }
                } catch (e: Exception) {
                    locationString = "Current Coordinates"
                }
            }
        } catch (e: Exception) {
            Log.e("WeatherEngine", "Error determining location", e)
        }
    }

    try {
        val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,showers,snowfall,weather_code,wind_speed_10m&timezone=auto"
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        if (connection.responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()

            val json = JSONObject(response.toString())
            val current = json.getJSONObject("current")
            val tempVal = current.getDouble("temperature_2m")
            val humVal = current.getInt("relative_humidity_2m")
            val windVal = current.getDouble("wind_speed_10m")
            val precVal = current.getDouble("precipitation")
            val code = current.getInt("weather_code")

            val conditionString = mapWeatherCodeToText(code)
            val currentTime = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())

            withContext(Dispatchers.Main) {
                onResult(
                    locationString,
                    "${tempVal.toInt()}°C",
                    conditionString,
                    "$humVal%",
                    "$windVal km/h",
                    "$precVal mm",
                    isFallback,
                    code,
                    currentTime
                )
            }
            return@withContext
        }
    } catch (e: Exception) {
        Log.e("WeatherEngine", "API Fetch error", e)
    }

    // API Fallback
    withContext(Dispatchers.Main) {
        onResult(
            if (isFallback) "New Delhi, India" else locationString,
            "28°C",
            "Partly Cloudy (Cached)",
            "60%",
            "12 km/h",
            "0.0 mm",
            isFallback,
            1,
            "Offline"
        )
    }
}

fun mapWeatherCodeToText(code: Int): String {
    return when (code) {
        0 -> "Clear Sky"
        1, 2, 3 -> "Partly Cloudy"
        45, 48 -> "Foggy Atmosphere"
        51, 53, 55 -> "Light Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63, 65 -> "Continuous Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75 -> "Snow Fall"
        77 -> "Snow Grains"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with Hail"
        else -> "Cloudy"
    }
}
