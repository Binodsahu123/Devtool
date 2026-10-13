package com.swift.browser.audioengine

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swift.browser.audioengine.model.AudioTrackItem
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    track: AudioTrackItem?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val engine = remember(context) { AudioPlayerEngine.getInstance(context) }

    val currentTrack by engine.currentTrack.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val currentPos by engine.currentPositionMs.collectAsState()
    val duration by engine.durationMs.collectAsState()
    val queueState by engine.queueState.collectAsState()
    val sleepTimerMinutes by engine.sleepTimerMinutesLeft.collectAsState()

    val displayTrack = currentTrack ?: track
    val title = displayTrack?.title ?: "No Track Playing"
    val artist = displayTrack?.artist ?: "<unknown>"
    val isFavorite = displayTrack?.isFavorite ?: false

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    var showMoreOptionsDialog by remember { mutableStateOf(false) }

    // Vinyl Disc Rotation Animation
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Position updates
    LaunchedEffect(Unit) {
        while (true) {
            engine.updatePosition()
            delay(250)
        }
    }

    val formattedCurrentPos = remember(currentPos) { formatTime(currentPos.toInt()) }
    val formattedDuration = remember(duration) { formatTime(duration.toInt()) }

    // Sleep Timer Dialog
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    listOf(15, 30, 45, 60, 0).forEach { minutes ->
                        TextButton(
                            onClick = {
                                if (minutes > 0) {
                                    engine.startSleepTimer(minutes)
                                } else {
                                    engine.cancelSleepTimer()
                                }
                                showSleepTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (minutes == 0) "Turn Off Timer" else "$minutes Minutes",
                                color = if (sleepTimerMinutes == minutes && minutes > 0) Color(0xFF00E676) else Color.White,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Cancel", color = Color(0xFF00E676))
                }
            }
        )
    }

    // Queue Bottom Sheet
    if (showQueueBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueBottomSheet = false },
            containerColor = Color(0xFF1E293B)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Playing Queue (${queueState.tracks.size})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                HorizontalDivider(color = Color(0xFF334155))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    items(queueState.tracks.size) { index ->
                        val item = queueState.tracks[index]
                        val isCurrent = item.id == displayTrack?.id
                        ListItem(
                            headlineContent = {
                                Text(
                                    item.title,
                                    color = if (isCurrent) Color(0xFF00E676) else Color.White,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    item.artist ?: "Unknown Artist",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (isCurrent) Icons.Default.VolumeUp else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (isCurrent) Color(0xFF00E676) else Color.Gray
                                )
                            },
                            modifier = Modifier.clickable {
                                engine.playTrack(item, queueState.tracks)
                                showQueueBottomSheet = false
                            }
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Now Playing",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Collapse Player",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            displayTrack?.let { tr ->
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, tr.title)
                                    putExtra(Intent.EXTRA_TEXT, "Listening to ${tr.title} by ${tr.artist}")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Audio"))
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B111E)
                )
            )
        },
        containerColor = Color(0xFF0B111E)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Vinyl Record Disc Artwork
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF141D2E)),
                contentAlignment = Alignment.Center
            ) {
                // Outer Vinyl Grooves Canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(if (isPlaying) rotationAngle else 0f)
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f

                    // Disc base background
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF26354D), Color(0xFF0F172A), Color(0xFF020617)),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )

                    // Concentric Vinyl Grooves
                    val grooveRadii = listOf(0.92f, 0.84f, 0.76f, 0.68f, 0.60f, 0.52f, 0.44f)
                    grooveRadii.forEach { factor ->
                        drawCircle(
                            color = Color(0xFF334155).copy(alpha = 0.35f),
                            radius = radius * factor,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                        )
                    }

                    // Center Hub Rim
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = 0.25f),
                        radius = radius * 0.34f,
                        center = center
                    )

                    // Center Vinyl Label Disc
                    drawCircle(
                        color = Color(0xFF1E293B),
                        radius = radius * 0.30f,
                        center = center
                    )

                    // Center Spindle Hole
                    drawCircle(
                        color = Color(0xFF0B111E),
                        radius = radius * 0.08f,
                        center = center
                    )
                }

                // Center Icon / Music Symbol
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFF00E676).copy(alpha = 0.85f),
                    modifier = Modifier.size(32.dp)
                )
            }

            // 2. Song Title & Subtitle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist,
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // 3. Quick Actions Row (Star, Equalizer, Timer, Artist, More)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { displayTrack?.let { engine.toggleFavorite(it) } }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFF00E676) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { /* Equalizer */ }) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Equalizer",
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { showSleepTimerDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = if (sleepTimerMinutes > 0) Color(0xFF00E676) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { /* Artist details */ }) {
                    Icon(
                        imageVector = Icons.Default.PersonOutline,
                        contentDescription = "Artist",
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { showQueueBottomSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 4. Progress Scrubber & Timers
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formattedCurrentPos,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Text(
                        formattedDuration,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Slider(
                    value = currentPos.toFloat().coerceAtMost(duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = { pos ->
                        engine.seekTo(pos.toInt())
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E676),
                        activeTrackColor = Color(0xFF00E676),
                        inactiveTrackColor = Color(0xFF334155)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 5. Bottom Playback Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle / Repeat
                IconButton(onClick = { engine.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (queueState.isShuffle) Color(0xFF00E676) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Previous
                IconButton(onClick = { engine.previous() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Big Green Play/Pause Pill Button
                Surface(
                    onClick = {
                        Handler(Looper.getMainLooper()).post {
                            engine.togglePlayPause()
                        }
                    },
                    modifier = Modifier
                        .width(84.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFF00E676),
                    shadowElevation = 6.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Next
                IconButton(onClick = { engine.next() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Queue / Playlist
                IconButton(onClick = { showQueueBottomSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.PlaylistPlay,
                        contentDescription = "Queue",
                        tint = Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun formatTime(ms: Int): String {
    val secTotal = (ms.coerceAtLeast(0)) / 1000
    val min = secTotal / 60
    val sec = secTotal % 60
    return String.format("%02d:%02d", min, sec)
}
