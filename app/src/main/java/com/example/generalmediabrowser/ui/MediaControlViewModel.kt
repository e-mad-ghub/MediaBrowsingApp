package com.example.generalmediabrowser.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.generalmediabrowser.media.MediaSessionRepository
import com.example.generalmediabrowser.media.MediaSessionState
import com.example.generalmediabrowser.media.model.MediaApp
import com.example.generalmediabrowser.media.model.NowPlaying
import com.example.generalmediabrowser.media.model.PlaybackStatus
import com.example.generalmediabrowser.media.model.SearchRequest
import com.example.generalmediabrowser.media.model.SpotifySearchType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MediaUiState(
    val title: String = "",
    val artist: String = "",
    val searchType: SpotifySearchType = SpotifySearchType.Track,
    val availableApps: List<MediaApp> = emptyList(),
    val selectedApp: MediaApp? = null,
    val nowPlaying: NowPlaying? = null,
    val playbackStatus: PlaybackStatus = PlaybackStatus.Idle,
    val notificationAccessGranted: Boolean = true
)

class MediaControlViewModel(
    private val repository: MediaSessionRepository
) : ViewModel() {

    private val search = MutableStateFlow(repository.loadSavedSearchRequest())

    val uiState: StateFlow<MediaUiState> = combine(
        repository.sessionState,
        search
    ) { sessionState: MediaSessionState, search: SearchRequest ->
        MediaUiState(
            title = search.title,
            artist = search.artist,
            searchType = search.searchType,
            availableApps = sessionState.availableApps,
            selectedApp = sessionState.selectedApp,
            nowPlaying = sessionState.nowPlaying,
            playbackStatus = sessionState.playbackStatus,
            notificationAccessGranted = sessionState.notificationAccessGranted
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MediaUiState(
            title = search.value.title,
            artist = search.value.artist,
            searchType = search.value.searchType,
            availableApps = repository.sessionState.value.availableApps,
            selectedApp = repository.sessionState.value.selectedApp,
            nowPlaying = repository.sessionState.value.nowPlaying,
            playbackStatus = repository.sessionState.value.playbackStatus,
            notificationAccessGranted = repository.sessionState.value.notificationAccessGranted
        )
    )

    init {
        repository.refreshActiveSessions()
    }

    fun onTitleChanged(newValue: String) {
        val updated = search.value.copy(title = newValue)
        search.value = updated
        repository.saveSearchRequest(updated)
    }

    fun onArtistChanged(newValue: String) {
        val updated = search.value.copy(artist = newValue)
        search.value = updated
        repository.saveSearchRequest(updated)
    }

    fun onSearchTypeChanged(newType: SpotifySearchType) {
        val updated = search.value.copy(searchType = newType)
        search.value = updated
        repository.saveSearchRequest(updated)
    }

    fun refreshSessions() {
        repository.refreshActiveSessions()
    }

    fun selectApp(app: MediaApp) {
        repository.selectApp(app.packageName)
    }

    fun playFromSearch() {
        val request = search.value
        if (request.isEmpty) return
        val targetPackage = uiState.value.selectedApp?.packageName

        viewModelScope.launch {
            if (targetPackage == "com.spotify.music") {
                if (!repository.isSpotifyConfigured()) {
                    Log.w("MediaControlViewModel", "Spotify not configured (clientId/redirectUri). Skipping playFromSearch.")
                    return@launch
                }
                val uri = repository.resolveSpotifyUri(request)
                if (uri != null) {
                    Log.d("MediaControlViewModel", "Resolved Spotify URI: $uri")
                    repository.playSpotifyUri(uri)
                } else {
                    Log.w("MediaControlViewModel", "Spotify URI resolution failed; skipping playback.")
                }
                return@launch
            }
            repository.playFromSearch(request)
        }
    }

    fun playOrPause() {
        when (uiState.value.playbackStatus) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering -> repository.pause()
            else -> repository.play()
        }
    }

    fun next() {
        repository.skipToNext()
    }

    fun previous() {
        repository.skipToPrevious()
    }

    fun fastForward() {
        repository.fastForward()
    }

    fun rewind() {
        repository.rewind()
    }

    override fun onCleared() {
        repository.dispose()
        super.onCleared()
    }
}

class MediaControlViewModelFactory(
    private val repository: MediaSessionRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaControlViewModel::class.java)) {
            return MediaControlViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
