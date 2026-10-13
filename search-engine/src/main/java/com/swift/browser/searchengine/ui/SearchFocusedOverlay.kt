package com.swift.browser.searchengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swift.browser.searchengine.SearchSuggestion
import com.swift.browser.searchengine.SuggestionType

data class SearchTabPreview(
    val title: String,
    val url: String
)

data class SearchHistoryPreview(
    val title: String,
    val url: String
)

data class SearchExtensionSuggestion(
    val content: String,
    val description: String,
    val deletable: Boolean = false
)

@Composable
fun SearchFocusedOverlay(
    activeTab: SearchTabPreview?,
    searchSuggestions: List<SearchSuggestion>,
    currentInputUrl: String,
    addressBarPosition: String,
    history: List<SearchHistoryPreview>,
    onSearch: (String) -> Unit,
    onEdit: (String) -> Unit,
    onClose: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    isGlass: Boolean = false,
    extensionSuggestions: List<SearchExtensionSuggestion> = emptyList(),
    onExtensionSuggestionSelect: (SearchExtensionSuggestion) -> Unit = {},
    onExtensionSuggestionDelete: ((SearchExtensionSuggestion) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = if (isGlass) Color(0xF20A0E17) else MaterialTheme.colorScheme.background
            )
            .statusBarsPadding()
    ) {
        // Chrome-style focused omnibox: pinned at the top, with the keyboard focused
        // immediately. The results below can scroll independently.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            color = if (isGlass) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
                }
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = if (isGlass) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = currentInputUrl,
                    onValueChange = onEdit,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Go,
                        keyboardType = KeyboardType.Uri
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val query = currentInputUrl.trim()
                            if (query.isNotEmpty()) {
                                keyboardController?.hide()
                                onSearch(query)
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (currentInputUrl.isBlank()) {
                                Text(
                                    text = "Search or type URL",
                                    color = if (isGlass) Color.White.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (currentInputUrl.isNotEmpty()) {
                    IconButton(onClick = { onEdit("") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
                IconButton(onClick = onVoiceClick, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice search")
                }
            }
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Web page card and search suggestions live below the fixed omnibox.
            if (activeTab != null && activeTab.url != "swift://newtab" && activeTab.url != "swift://newtab-incognito") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        contentColor = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = if (isGlass) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(20.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = if (isGlass) Color.White else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeTab.title.ifEmpty { "Active Webpage" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = activeTab.url,
                                fontSize = 12.sp,
                                color = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, activeTab.url)
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share via"))
                            } }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                clipboardManager.setText(AnnotatedString(activeTab.url))
                                android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                            } }) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onEdit(activeTab.url)
                            } }) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Extension Suggestions
            if (extensionSuggestions.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        contentColor = if (isGlass) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "Extension Suggestions",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        extensionSuggestions.forEach { extSug ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onExtensionSuggestionSelect(extSug) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = extSug.description.ifBlank { extSug.content },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (extSug.description.isNotBlank() && extSug.content != extSug.description) {
                                        Text(
                                            text = extSug.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (extSug.deletable) {
                                    IconButton(
                                        onClick = { onExtensionSuggestionDelete?.invoke(extSug) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete suggestion",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Second: Search suggestions list under the Web Page edit card
            if (searchSuggestions.isNotEmpty() || currentInputUrl.isEmpty()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        contentColor = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        if (searchSuggestions.isNotEmpty()) {
                            searchSuggestions.forEach { suggestion ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (suggestion.type == SuggestionType.HISTORY || suggestion.type == SuggestionType.BOOKMARK) {
                                                onSearch(suggestion.url)
                                            } else {
                                                onSearch(suggestion.title)
                                            }
                                        }
                                        .padding(vertical = 10.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val icon = when (suggestion.type) {
                                        SuggestionType.SEARCH -> Icons.Default.Search
                                        SuggestionType.HISTORY -> Icons.Default.History
                                        SuggestionType.BOOKMARK -> Icons.Default.Star
                                    }
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = suggestion.type.name,
                                        tint = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = suggestion.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (suggestion.type == SuggestionType.HISTORY || suggestion.type == SuggestionType.BOOKMARK) {
                                            Text(
                                                text = suggestion.url,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isGlass) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                    if (suggestion.type == SuggestionType.SEARCH) {
                                        IconButton(
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { onEdit(suggestion.title) } },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowBack,
                                                contentDescription = "Refine search",
                                                tint = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 135f)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Show recent history if search is empty
                            if (history.isNotEmpty()) {
                                history.take(8).forEach { historyItem ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSearch(historyItem.url) }
                                            .padding(vertical = 10.dp, horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = "History",
                                            tint = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = historyItem.title.ifBlank { historyItem.url },
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = historyItem.url,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isGlass) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { onEdit(historyItem.url) } },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowBack,
                                                contentDescription = "Refine",
                                                tint = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 135f)
                                            )
                                        }
                                    }
                                }
                            }

                            // Trending Searches
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Trending Searches",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            val trending = listOf("ChatGPT", "Local Weather", "Breaking News", "Amazon", "Android 15")
                            trending.forEach { trend ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSearch(trend) }
                                        .padding(vertical = 10.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TrendingUp,
                                        contentDescription = "Trending",
                                        tint = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = trend,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { onEdit(trend) } },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowBack,
                                            contentDescription = "Refine",
                                            tint = if (isGlass) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 135f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxWidth()) {
            bottomBar()
        }
    }
}
