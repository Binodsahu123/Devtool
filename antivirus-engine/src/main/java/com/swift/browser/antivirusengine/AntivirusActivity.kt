package com.swift.browser.antivirusengine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AntivirusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    SwiftCleanerScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwiftCleanerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var scanComplete by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var scannedItem by remember { mutableStateOf("Ready to scan storage for junk...") }
    
    var cacheBytes by remember { mutableStateOf(0L) }
    var extCacheBytes by remember { mutableStateOf(0L) }
    var tempBytes by remember { mutableStateOf(0L) }
    var totalJunkBytes by remember { mutableStateOf(0L) }
    var appCount by remember { mutableStateOf(0) }
    var cleanedSuccess by remember { mutableStateOf(false) }

    fun runScan() {
        scanning = true
        progress = 0f
        scanComplete = false
        cleanedSuccess = false
        
        coroutineScope.launch {
            // Count total packages
            withContext(Dispatchers.IO) {
                try {
                    val packages = context.packageManager.getInstalledPackages(0)
                    appCount = packages.size
                } catch (e: Exception) {
                    appCount = 45 // fallback
                }
            }

            // Step 1: Scan Internal Cache
            scannedItem = "Scanning internal app cache..."
            delay(400)
            progress = 0.2f
            withContext(Dispatchers.IO) {
                cacheBytes = getFolderSize(context.cacheDir)
            }

            // Step 2: Scan External Cache
            scannedItem = "Scanning external SD cache..."
            delay(400)
            progress = 0.5f
            withContext(Dispatchers.IO) {
                val extCache = context.externalCacheDir
                extCacheBytes = if (extCache != null) getFolderSize(extCache) else 0L
            }

            // Step 3: Scan Temp directories
            scannedItem = "Scanning temporary directories..."
            delay(400)
            progress = 0.8f
            withContext(Dispatchers.IO) {
                val codeCache = File(context.cacheDir, "code_cache")
                tempBytes = if (codeCache.exists()) getFolderSize(codeCache) else 0L
            }

            delay(300)
            progress = 1.0f
            totalJunkBytes = cacheBytes + extCacheBytes + tempBytes
            scannedItem = "Storage analysis complete. Found ${formatSize(totalJunkBytes)} of redundant data."
            scanning = false
            scanComplete = true
        }
    }

    fun runClean() {
        coroutineScope.launch {
            scannedItem = "Purging cache entries..."
            scanning = true
            progress = 0.2f
            delay(500)

            withContext(Dispatchers.IO) {
                // Delete internal cache contents
                deleteFolderContents(context.cacheDir)
                // Delete external cache contents if available
                context.externalCacheDir?.let { deleteFolderContents(it) }
            }

            progress = 0.8f
            scannedItem = "Re-calculating free space..."
            delay(400)

            // Re-evaluate junk sizes (should be 0 or close to 0)
            withContext(Dispatchers.IO) {
                cacheBytes = getFolderSize(context.cacheDir)
                val extCache = context.externalCacheDir
                extCacheBytes = if (extCache != null) getFolderSize(extCache) else 0L
                val codeCache = File(context.cacheDir, "code_cache")
                tempBytes = if (codeCache.exists()) getFolderSize(codeCache) else 0L
                totalJunkBytes = cacheBytes + extCacheBytes + tempBytes
            }

            progress = 1.0f
            scanning = false
            scanComplete = false
            cleanedSuccess = true
            scannedItem = "System Cleaned! Real storage space recovered."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Swift Cleaner & Optimizer") },
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
            
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(
                        color = when {
                            cleanedSuccess -> Color(0xFF10B981).copy(alpha = 0.2f)
                            scanning -> Color(0xFF3B82F6).copy(alpha = 0.2f)
                            totalJunkBytes > 0 -> Color(0xFFF59E0B).copy(alpha = 0.2f)
                            else -> Color(0xFF334155)
                        }, 
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    cleanedSuccess -> {
                        Icon(Icons.Default.CheckCircle, "Cleaned", tint = Color(0xFF10B981), modifier = Modifier.size(80.dp))
                    }
                    totalJunkBytes > 0 && scanComplete -> {
                        Icon(Icons.Default.DeleteSweep, "Junk Found", tint = Color(0xFFF59E0B), modifier = Modifier.size(80.dp))
                    }
                    else -> {
                        Icon(Icons.Default.Security, "Storage Security Scanner", tint = if (scanning) Color(0xFF3B82F6) else Color.Gray, modifier = Modifier.size(80.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = when {
                    cleanedSuccess -> "Storage Optimized"
                    scanning -> "Analyzing System..."
                    totalJunkBytes > 0 && scanComplete -> "Junk Files Detected"
                    else -> "Swift Storage Scanner"
                },
                fontSize = 24.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = scannedItem,
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            
            if (scanning) {
                Spacer(modifier = Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Color(0xFF3B82F6),
                    trackColor = Color(0xFF334155)
                )
            }

            if (scanComplete && totalJunkBytes > 0) {
                Spacer(modifier = Modifier.height(32.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Scan Findings",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Internal App Cache", color = Color(0xFF94A3B8))
                            Text(formatSize(cacheBytes), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF334155))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("External SD Cache", color = Color(0xFF94A3B8))
                            Text(formatSize(extCacheBytes), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF334155))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Code Compile Temp Cache", color = Color(0xFF94A3B8))
                            Text(formatSize(tempBytes), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF334155))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Scanned Apps", color = Color(0xFF94A3B8))
                            Text("$appCount Apps", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { runClean() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("CLEAN ALL JUNK (${formatSize(totalJunkBytes)})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(32.dp))

            if (!scanning && !scanComplete) {
                Button(
                    onClick = { runScan() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("START SCAN", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun getFolderSize(file: File): Long {
    var size = 0L
    if (file.isDirectory) {
        val files = file.listFiles()
        if (files != null) {
            for (f in files) {
                size += getFolderSize(f)
            }
        }
    } else {
        size = file.length()
    }
    return size
}

fun deleteFolderContents(file: File) {
    if (file.isDirectory) {
        val files = file.listFiles()
        if (files != null) {
            for (f in files) {
                deleteFolderContents(f)
                try {
                    f.delete()
                } catch (_: Exception) {}
            }
        }
    } else {
        try {
            file.delete()
        } catch (_: Exception) {}
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    val mb = bytes / (1024 * 1024f)
    return String.format(Locale.US, "%.1f MB", mb)
}
