package com.example.generalmediabrowser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.generalmediabrowser.media.model.MediaApp
import com.example.generalmediabrowser.media.model.NowPlaying
import com.example.generalmediabrowser.media.model.PlaybackStatus
import com.example.generalmediabrowser.media.model.SpotifySearchType
import com.example.generalmediabrowser.ui.theme.GeneralMediaBrowserTheme

@Composable
fun MediaControlScreen(
    viewModel: MediaControlViewModel,
    onRequestNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MediaControlContent(
        state = uiState,
        onTitleChanged = viewModel::onTitleChanged,
        onArtistChanged = viewModel::onArtistChanged,
        onSearchTypeChanged = viewModel::onSearchTypeChanged,
        onPlayFromSearch = viewModel::playFromSearch,
        onSelectApp = viewModel::selectApp,
        onPlayPause = viewModel::playOrPause,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onFastForward = viewModel::fastForward,
        onRewind = viewModel::rewind,
        onRefreshSessions = viewModel::refreshSessions,
        onRequestNotificationAccess = onRequestNotificationAccess,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaControlContent(
    state: MediaUiState,
    onTitleChanged: (String) -> Unit,
    onArtistChanged: (String) -> Unit,
    onSearchTypeChanged: (SpotifySearchType) -> Unit,
    onPlayFromSearch: () -> Unit,
    onSelectApp: (MediaApp) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFastForward: () -> Unit,
    onRewind: () -> Unit,
    onRefreshSessions: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Media Browser") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRefreshSessions) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh sessions")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.notificationAccessGranted) {
                NotificationAccessCallout(onRequestNotificationAccess)
            }

            MediaAppPicker(
                apps = state.availableApps,
                selected = state.selectedApp,
                onSelect = onSelectApp
            )

            SearchRow(
                title = state.title,
                artist = state.artist,
                searchType = state.searchType,
                onTitleChanged = onTitleChanged,
                onArtistChanged = onArtistChanged,
                onSearchTypeChanged = onSearchTypeChanged,
                onPlayFromSearch = onPlayFromSearch,
                enabled = state.selectedApp != null
            )

            NowPlayingCard(
                nowPlaying = state.nowPlaying,
                playbackStatus = state.playbackStatus
            )

            PlaybackControls(
                isPlaying = state.playbackStatus == PlaybackStatus.Playing || state.playbackStatus == PlaybackStatus.Buffering,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onFastForward = onFastForward,
                onRewind = onRewind,
                enabled = state.selectedApp != null
            )
        }
    }
}

@Composable
private fun NotificationAccessCallout(onRequestNotificationAccess: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Enable notification access to list and control media apps.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRequestNotificationAccess) {
                Text("Open notification access settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaAppPicker(
    apps: List<MediaApp>,
    selected: MediaApp?,
    onSelect: (MediaApp) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = selected?.displayName ?: "Choose media app"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Target app") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            colors = TextFieldDefaults.colors()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            apps.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(app)
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    title: String,
    artist: String,
    searchType: SpotifySearchType,
    onTitleChanged: (String) -> Unit,
    onArtistChanged: (String) -> Unit,
    onSearchTypeChanged: (SpotifySearchType) -> Unit,
    onPlayFromSearch: () -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChanged,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Words)
        )
        OutlinedTextField(
            value = artist,
            onValueChange = onArtistChanged,
            label = { Text("Artist") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, capitalization = KeyboardCapitalization.Words),
            keyboardActions = KeyboardActions(onSearch = { onPlayFromSearch() })
        )
        SpotifyTypeSelector(
            selected = searchType,
            onSelected = onSearchTypeChanged
        )
        Button(
            onClick = onPlayFromSearch,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Play from search")
        }
    }
}

@Composable
private fun NowPlayingCard(
    nowPlaying: NowPlaying?,
    playbackStatus: PlaybackStatus
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Now playing", style = MaterialTheme.typography.titleMedium)
            if (nowPlaying == null) {
                Text(
                    text = "No track information from the selected app.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = nowPlaying.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(nowPlaying.artist, nowPlaying.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Status: ${playbackStatus.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpotifyTypeSelector(
    selected: SpotifySearchType,
    onSelected: (SpotifySearchType) -> Unit
) {
    @OptIn(ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            SpotifySearchType.Track,
            SpotifySearchType.Artist,
            SpotifySearchType.Album,
            SpotifySearchType.Playlist,
            SpotifySearchType.Show,
            SpotifySearchType.Episode,
            SpotifySearchType.Audiobook,
            SpotifySearchType.Chapter
        ).forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelected(type) },
                label = { Text(type.displayName()) }
            )
        }
    }
}

private fun SpotifySearchType.displayName(): String = when (this) {
    SpotifySearchType.Track -> "Track"
    SpotifySearchType.Artist -> "Artist"
    SpotifySearchType.Album -> "Album"
    SpotifySearchType.Playlist -> "Playlist"
    SpotifySearchType.Show -> "Podcast/Show"
    SpotifySearchType.Episode -> "Episode"
    SpotifySearchType.Audiobook -> "Audiobook"
    SpotifySearchType.Chapter -> "Chapter"
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFastForward: () -> Unit,
    onRewind: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onRewind, enabled = enabled) {
                Icon(imageVector = Icons.Filled.FastRewind, contentDescription = "Rewind")
            }
            IconButton(onClick = onPrevious, enabled = enabled) {
                Icon(imageVector = Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = onPlayPause, enabled = enabled) {
                val icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
                val label = if (isPlaying) "Pause" else "Play"
                Icon(imageVector = icon, contentDescription = label)
            }
            IconButton(onClick = onNext, enabled = enabled) {
                Icon(imageVector = Icons.Filled.SkipNext, contentDescription = "Next")
            }
            IconButton(onClick = onFastForward, enabled = enabled) {
                Icon(imageVector = Icons.Filled.FastForward, contentDescription = "Fast forward")
            }
        }
    }
}

@Preview
@Composable
private fun MediaControlContentPreview() {
    GeneralMediaBrowserTheme {
        MediaControlContent(
            state = MediaUiState(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                searchType = SpotifySearchType.Track,
                availableApps = listOf(
                    MediaApp("com.spotify.music", "Spotify"),
                    MediaApp("com.amazon.mp3", "Amazon Music")
                ),
                selectedApp = MediaApp("com.spotify.music", "Spotify"),
                nowPlaying = NowPlaying(
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    album = "A Night at the Opera"
                ),
                playbackStatus = PlaybackStatus.Playing,
                notificationAccessGranted = true
            ),
            onTitleChanged = {},
            onArtistChanged = {},
            onSearchTypeChanged = {},
            onPlayFromSearch = {},
            onSelectApp = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onFastForward = {},
            onRewind = {},
            onRefreshSessions = {},
            onRequestNotificationAccess = {}
        )
    }
}
