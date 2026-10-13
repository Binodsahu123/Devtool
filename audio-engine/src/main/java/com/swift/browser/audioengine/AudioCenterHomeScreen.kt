package com.swift.browser.audioengine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.swift.browser.audioengine.model.AudioAlbum
import com.swift.browser.audioengine.model.AudioArtist
import com.swift.browser.audioengine.model.AudioFolder
import com.swift.browser.audioengine.model.AudioPlaylist
import com.swift.browser.audioengine.model.AudioTrackItem
import com.swift.browser.audioengine.model.SortOption
import com.swift.browser.audioengine.online.OnlineMusicEngineScreen
import com.swift.browser.audioengine.online.OnlineMusicWebViewManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCenterHomeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = remember(context) { AudioPlayerEngine.getInstance(context) }

    var selectedTab by remember { mutableIntStateOf(1) } // Default to Offline Music like video
    var selectedTrackForFullPlayer by remember { mutableStateOf<AudioTrackItem?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSortBottomSheet by remember { mutableStateOf(false) }

    // Sub-view detail navigation (e.g., inside Album, Artist, Folder, or Playlist)
    var detailTitle by remember { mutableStateOf<String?>(null) }
    var detailTracks by remember { mutableStateOf<List<AudioTrackItem>?>(null) }

    val localTracks by (engine as AudioPlayerEngine).localLibrary.collectAsState()
    val favorites by engine.favorites.collectAsState()
    val playlists by engine.playlists.collectAsState()
    val albums by engine.albums.collectAsState()
    val artists by engine.artists.collectAsState()
    val folders by engine.folders.collectAsState()
    val activeSortOption by engine.currentSortOption.collectAsState()

    // Permission Checking
    val requiredPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            engine.scanLocalAudio()
        }
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission && localTracks.isEmpty()) {
            engine.scanLocalAudio()
        }
    }

    BackHandler(enabled = true) {
        when {
            showFullPlayer -> {
                showFullPlayer = false
            }
            detailTitle != null -> {
                detailTitle = null
                detailTracks = null
            }
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
            }
            else -> {
                onBack()
            }
        }
    }

    // Dialog States
    var contextTrack by remember { mutableStateOf<AudioTrackItem?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialogForTrack by remember { mutableStateOf<AudioTrackItem?>(null) }
    var showRenameDialogForTrack by remember { mutableStateOf<AudioTrackItem?>(null) }
    var showDeleteDialogForTrack by remember { mutableStateOf<AudioTrackItem?>(null) }

    var newPlaylistNameInput by remember { mutableStateOf("") }
    var renameTrackNameInput by remember { mutableStateOf("") }

    // Sort Bottom Sheet matching video (0:26, 0:33, 0:38)
    if (showSortBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortBottomSheet = false },
            containerColor = Color(0xFF141D2E),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Sort Music By",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val sortOptionsList = listOf(
                    SortOption.NAME_A_TO_Z to "Name (A to Z)",
                    SortOption.NAME_Z_TO_A to "Name (Z to A)",
                    SortOption.DATE_NEW_TO_OLD to "Date (Newest First)",
                    SortOption.DATE_OLD_TO_NEW to "Date (Oldest First)",
                    SortOption.SIZE_LARGE_TO_SMALL to "Size (Large to Small)",
                    SortOption.SIZE_SMALL_TO_LARGE to "Size (Small to Large)",
                    SortOption.LENGTH_LONG_TO_SHORT to "Length (Longest First)",
                    SortOption.LENGTH_SHORT_TO_LONG to "Length (Shortest First)"
                )

                sortOptionsList.forEach { (option, label) ->
                    val isSelected = option == activeSortOption
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                engine.setSortOption(option)
                                showSortBottomSheet = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                engine.setSortOption(option)
                                showSortBottomSheet = false
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFF00E676),
                                unselectedColor = Color(0xFF475569)
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF00E676) else Color(0xFFCBD5E1),
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showSortBottomSheet = false }) {
                        Text("Close", color = Color(0xFF00E676), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Dialog Renderers
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistNameInput,
                    onValueChange = { newPlaylistNameInput = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistNameInput.isNotBlank()) {
                            engine.createPlaylist(newPlaylistNameInput)
                            showCreatePlaylistDialog = false
                            newPlaylistNameInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Create", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showAddToPlaylistDialogForTrack != null) {
        val targetTrack = showAddToPlaylistDialogForTrack!!
        AlertDialog(
            onDismissRequest = { showAddToPlaylistDialogForTrack = null },
            title = { Text("Add '${targetTrack.title}' to Playlist", fontWeight = FontWeight.Bold) },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists created yet. Create a playlist first.")
                } else {
                    Column {
                        playlists.forEach { pl ->
                            TextButton(
                                onClick = {
                                    engine.addTrackToPlaylist(pl.id, targetTrack)
                                    showAddToPlaylistDialogForTrack = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(pl.name, color = Color.White, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddToPlaylistDialogForTrack = null }) {
                    Text("Close", color = Color(0xFF00E676))
                }
            }
        )
    }

    if (showRenameDialogForTrack != null) {
        val targetTrack = showRenameDialogForTrack!!
        AlertDialog(
            onDismissRequest = { showRenameDialogForTrack = null },
            title = { Text("Rename Song", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameTrackNameInput,
                    onValueChange = { renameTrackNameInput = it },
                    label = { Text("Song Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameTrackNameInput.isNotBlank()) {
                            engine.renameAudio(targetTrack, renameTrackNameInput)
                            showRenameDialogForTrack = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text("Rename", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogForTrack = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showDeleteDialogForTrack != null) {
        val targetTrack = showDeleteDialogForTrack!!
        AlertDialog(
            onDismissRequest = { showDeleteDialogForTrack = null },
            title = { Text("Delete Track", fontWeight = FontWeight.Bold) },
            text = { Text("Delete '${targetTrack.title}' from local storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        engine.deleteAudio(targetTrack)
                        showDeleteDialogForTrack = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF43F5E))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogForTrack = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // Context Menu Bottom Sheet for Track Actions
    if (contextTrack != null) {
        val tr = contextTrack!!
        ModalBottomSheet(
            onDismissRequest = { contextTrack = null },
            containerColor = Color(0xFF141D2E)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = tr.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tr.artist ?: "Unknown"} • ${tr.album ?: "Unknown Album"}",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                HorizontalDivider(color = Color(0xFF1E293B))

                ListItem(
                    headlineContent = { Text("Play Now", color = Color.White) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00E676)) },
                    modifier = Modifier.clickable {
                        engine.playTrack(tr, localTracks)
                        selectedTrackForFullPlayer = tr
                        contextTrack = null
                        showFullPlayer = true
                    }
                )
                ListItem(
                    headlineContent = { Text(if (tr.isFavorite) "Remove from Favorites" else "Add to Favorites", color = Color.White) },
                    leadingContent = {
                        Icon(
                            if (tr.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (tr.isFavorite) Color(0xFF00E676) else Color.White
                        )
                    },
                    modifier = Modifier.clickable {
                        engine.toggleFavorite(tr)
                        contextTrack = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Add to Playlist", color = Color.White) },
                    leadingContent = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = Color.White) },
                    modifier = Modifier.clickable {
                        val selected = tr
                        contextTrack = null
                        showAddToPlaylistDialogForTrack = selected
                    }
                )
                ListItem(
                    headlineContent = { Text("Share Track", color = Color.White) },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null, tint = Color.White) },
                    modifier = Modifier.clickable {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, tr.title)
                            putExtra(Intent.EXTRA_TEXT, "Check out ${tr.title} by ${tr.artist}")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                        contextTrack = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Rename", color = Color.White) },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White) },
                    modifier = Modifier.clickable {
                        val selected = tr
                        contextTrack = null
                        renameTrackNameInput = selected.title
                        showRenameDialogForTrack = selected
                    }
                )
                ListItem(
                    headlineContent = { Text("Delete Track", color = Color(0xFFF43F5E)) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF43F5E)) },
                    modifier = Modifier.clickable {
                        val selected = tr
                        contextTrack = null
                        showDeleteDialogForTrack = selected
                    }
                )
            }
        }
    }

    if (showFullPlayer) {
        AudioPlayerScreen(
            track = selectedTrackForFullPlayer,
            onBack = { showFullPlayer = false }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (detailTitle != null) {
                            Text(detailTitle!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        } else if (isSearchActive) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        if (selectedTab == 0) "Search online songs, artists..." else "Search songs, artists, albums...",
                                        color = Color.Gray,
                                        fontSize = 14.sp
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (selectedTab == 0 && searchQuery.isNotBlank()) {
                                            OnlineMusicWebViewManager.search(searchQuery, context)
                                            isSearchActive = false
                                        }
                                    }
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text("Music Player", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (detailTitle != null) {
                                    detailTitle = null
                                    detailTracks = null
                                } else if (isSearchActive) {
                                    isSearchActive = false
                                    searchQuery = ""
                                } else {
                                    onBack()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        if (detailTitle == null) {
                            IconButton(onClick = { isSearchActive = !isSearchActive }) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = {
                                if (selectedTab == 0) {
                                    OnlineMusicWebViewManager.reload()
                                } else {
                                    engine.scanLocalAudio()
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Library", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B111E))
                )
            },
            bottomBar = {
                MiniOfflineAudioPlayer(
                    onExpandPlayer = { showFullPlayer = true }
                )
            },
            containerColor = Color(0xFF0B111E),
            modifier = modifier
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (detailTitle != null && detailTracks != null) {
                    // Category Track Detail Sub-view
                    TrackListView(
                        tracks = filterTracks(detailTracks!!, searchQuery),
                        onTrackClick = { track ->
                            engine.playTrack(track, detailTracks!!)
                            selectedTrackForFullPlayer = track
                            showFullPlayer = true
                        },
                        onShuffleAll = {
                            if (detailTracks!!.isNotEmpty()) {
                                val shuffled = detailTracks!!.shuffled()
                                engine.playTrack(shuffled.first(), shuffled)
                                selectedTrackForFullPlayer = shuffled.first()
                                showFullPlayer = true
                            }
                        },
                        onSortClick = { showSortBottomSheet = true },
                        onLongClick = { track -> contextTrack = track }
                    )
                } else {
                    // Category Tabs Header with custom Green Indicator & Icons + Text
                    val tabTitles = listOf("Online Music", "Offline Music", "Favorites", "Playlist", "Folder", "Album", "Artist")
                    val tabIcons = listOf(
                        Icons.Default.Public,
                        Icons.Default.MusicNote,
                        Icons.Default.Favorite,
                        Icons.Default.PlaylistPlay,
                        Icons.Default.Folder,
                        Icons.Default.Album,
                        Icons.Default.Person
                    )

                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF0B111E),
                        contentColor = Color.White,
                        edgePadding = 8.dp,
                        indicator = { tabPositions ->
                            if (selectedTab < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                    color = Color(0xFF00E676),
                                    height = 3.dp
                                )
                            }
                        },
                        divider = { HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.8.dp) }
                    ) {
                        tabTitles.forEachIndexed { index, title ->
                            val isSelected = selectedTab == index
                            Tab(
                                selected = isSelected,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        title,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF00E676) else Color(0xFF94A3B8)
                                    )
                                },
                                icon = {
                                    Icon(
                                        tabIcons[index],
                                        contentDescription = title,
                                        tint = if (isSelected) Color(0xFF00E676) else Color(0xFF64748B),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }

                    when (selectedTab) {
                        0 -> OnlineMusicEngineScreen()
                        1 -> {
                            if (!hasAudioPermission && localTracks.isEmpty()) {
                                PermissionPromptView(
                                    onGrantPermission = { permissionLauncher.launch(requiredPermission) }
                                )
                            } else {
                                TrackListView(
                                    tracks = filterTracks(localTracks, searchQuery),
                                    onTrackClick = { track ->
                                        engine.playTrack(track, localTracks)
                                        selectedTrackForFullPlayer = track
                                        showFullPlayer = true
                                    },
                                    onShuffleAll = {
                                        if (localTracks.isNotEmpty()) {
                                            val shuffled = localTracks.shuffled()
                                            engine.playTrack(shuffled.first(), shuffled)
                                            selectedTrackForFullPlayer = shuffled.first()
                                            showFullPlayer = true
                                        }
                                    },
                                    onSortClick = { showSortBottomSheet = true },
                                    onLongClick = { track -> contextTrack = track }
                                )
                            }
                        }
                        2 -> {
                            if (favorites.isEmpty()) {
                                EmptyStateView(icon = Icons.Default.FavoriteBorder, message = "No favorites found")
                            } else {
                                TrackListView(
                                    tracks = filterTracks(favorites, searchQuery),
                                    onTrackClick = { track ->
                                        engine.playTrack(track, favorites)
                                        selectedTrackForFullPlayer = track
                                        showFullPlayer = true
                                    },
                                    onShuffleAll = {
                                        val shuffled = favorites.shuffled()
                                        engine.playTrack(shuffled.first(), shuffled)
                                        selectedTrackForFullPlayer = shuffled.first()
                                        showFullPlayer = true
                                    },
                                    onSortClick = { showSortBottomSheet = true },
                                    onLongClick = { track -> contextTrack = track }
                                )
                            }
                        }
                        3 -> PlaylistsCategoryView(
                            playlists = playlists,
                            onCreatePlaylist = { showCreatePlaylistDialog = true },
                            onPlaylistClick = { pl ->
                                detailTitle = pl.name
                                detailTracks = pl.tracks
                            },
                            onDeletePlaylist = { plId -> engine.deletePlaylist(plId) }
                        )
                        4 -> FoldersCategoryView(
                            folders = folders,
                            onFolderClick = { folder ->
                                val tracksInFolder = localTracks.filter { it.folderPath == folder.name || it.filePath.contains("/${folder.name}/") }
                                detailTitle = folder.name
                                detailTracks = tracksInFolder
                            }
                        )
                        5 -> AlbumsCategoryView(
                            albums = albums,
                            onAlbumClick = { album ->
                                val tracksInAlbum = localTracks.filter { it.album == album.name }
                                detailTitle = album.name
                                detailTracks = tracksInAlbum
                            }
                        )
                        6 -> ArtistsCategoryView(
                            artists = artists,
                            onArtistClick = { artist ->
                                val tracksByArtist = localTracks.filter { it.artist == artist.name }
                                detailTitle = artist.name
                                detailTracks = tracksByArtist
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionPromptView(onGrantPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "We need permission to access local audio.",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onGrantPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(48.dp)
            ) {
                Text("Grant Permission", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun EmptyStateView(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF475569),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = Color(0xFF94A3B8),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun TrackListView(
    tracks: List<AudioTrackItem>,
    onTrackClick: (AudioTrackItem) -> Unit,
    onShuffleAll: () -> Unit,
    onSortClick: () -> Unit,
    onLongClick: (AudioTrackItem) -> Unit
) {
    if (tracks.isEmpty()) {
        EmptyStateView(icon = Icons.Default.MusicOff, message = "No music found")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Row: Shuffle all(X) and Sort Icon matching video (0:21)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onShuffleAll() }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Shuffle",
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Shuffle all(${tracks.size})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    IconButton(onClick = onSortClick) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            items(tracks, key = { it.id }) { track ->
                TrackItemRow(
                    track = track,
                    onClick = { onTrackClick(track) },
                    onMoreClick = { onLongClick(track) }
                )
            }
        }
    }
}

@Composable
fun TrackItemRow(
    track: AudioTrackItem,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Square music icon matching video (0:21)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val durationText = formatTrackDuration(track.durationMs)
            val artistText = if (track.artist.isNullOrBlank()) "<unknown>" else track.artist
            Text(
                text = "$artistText • $durationText",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = Color(0xFF64748B)
            )
        }
    }
}

@Composable
fun AlbumsCategoryView(
    albums: List<AudioAlbum>,
    onAlbumClick: (AudioAlbum) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyStateView(icon = Icons.Default.Album, message = "No albums found")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(albums) { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAlbumClick(album) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Album, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(album.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${album.trackCount} tracks", color = Color.Gray, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF475569))
                }
            }
        }
    }
}

@Composable
fun ArtistsCategoryView(
    artists: List<AudioArtist>,
    onArtistClick: (AudioArtist) -> Unit
) {
    if (artists.isEmpty()) {
        EmptyStateView(icon = Icons.Default.Person, message = "No artists found")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(artists) { artist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArtistClick(artist) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${artist.trackCount} tracks", color = Color.Gray, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF475569))
                }
            }
        }
    }
}

@Composable
fun FoldersCategoryView(
    folders: List<AudioFolder>,
    onFolderClick: (AudioFolder) -> Unit
) {
    if (folders.isEmpty()) {
        EmptyStateView(icon = Icons.Default.Folder, message = "No audio folders found")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(folders) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFolderClick(folder) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(folder.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${folder.trackCount} items", color = Color.Gray, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF475569))
                }
            }
        }
    }
}

@Composable
fun PlaylistsCategoryView(
    playlists: List<AudioPlaylist>,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (AudioPlaylist) -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Playlists (${playlists.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = onCreatePlaylist,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Playlist", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        if (playlists.isEmpty()) {
            EmptyStateView(icon = Icons.Default.QueueMusic, message = "No playlists created yet")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(playlists) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistClick(playlist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(playlist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.tracks.size} tracks", color = Color.Gray, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onDeletePlaylist(playlist.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF64748B))
                        }
                    }
                }
            }
        }
    }
}

private fun filterTracks(tracks: List<AudioTrackItem>, query: String): List<AudioTrackItem> {
    if (query.isBlank()) return tracks
    val lower = query.lowercase()
    return tracks.filter {
        it.title.lowercase().contains(lower) ||
                (it.artist?.lowercase()?.contains(lower) == true) ||
                (it.album?.lowercase()?.contains(lower) == true)
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%d:%02d", min, sec)
}
