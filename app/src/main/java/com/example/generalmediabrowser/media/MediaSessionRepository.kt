package com.example.generalmediabrowser.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.app.SearchManager
import android.util.Base64
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import com.example.generalmediabrowser.media.model.MediaApp
import com.example.generalmediabrowser.media.model.NowPlaying
import com.example.generalmediabrowser.media.model.PlaybackStatus
import com.example.generalmediabrowser.media.model.SearchRequest
import com.example.generalmediabrowser.media.model.SpotifySearchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class MediaSessionState(
    val availableApps: List<MediaApp> = emptyList(),
    val selectedApp: MediaApp? = null,
    val nowPlaying: NowPlaying? = null,
    val playbackStatus: PlaybackStatus = PlaybackStatus.Idle,
    val notificationAccessGranted: Boolean = true
)

class MediaSessionRepository(
    private val context: Context
) {
    private val tag = "MediaSessionRepo"
    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val notificationComponent = ComponentName(context, MediaSessionListenerService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs: SharedPreferences = context.getSharedPreferences("media_search_prefs", Context.MODE_PRIVATE)
    private var spotifyRemote: SpotifyAppRemote? = null
    private val spotifyClientId = "6f4b3740a1b94069bcc2f4d16fb2c919"
    private val spotifyRedirectUri = "com.example.generalmediabrowser://spotify-callback"
    private val httpClient = OkHttpClient()

    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var activeController: MediaController? = null
    private var listenerRegistered: Boolean = false

    private val _sessionState = MutableStateFlow(
        MediaSessionState(notificationAccessGranted = hasNotificationAccess())
    )
    val sessionState: StateFlow<MediaSessionState> = _sessionState.asStateFlow()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { newControllers ->
        val controllersFromCallback = newControllers.orEmpty()
        val controllersToUse = if (controllersFromCallback.isNotEmpty()) {
            controllersFromCallback
        } else {
            loadActiveSessions()
        }
        handleUpdatedControllers(controllersToUse)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateNowPlaying(metadata, activeController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateNowPlaying(activeController?.metadata, state)
        }
    }

    init {
        registerSessionListener()
        refreshActiveSessions()
    }

    fun selectApp(packageName: String) {
        Log.d(tag, "selectApp() package=$packageName")
        val controller = controllers.firstOrNull { it.packageName == packageName }
        val selectedApp = _sessionState.value.availableApps.firstOrNull { it.packageName == packageName }
        setActiveController(controller, selectedApp)
    }

    fun loadSavedSearchRequest(): SearchRequest {
        return SearchRequest(
            query = prefs.getString("query", "") ?: "",
            title = prefs.getString("title", "") ?: "",
            artist = prefs.getString("artist", "") ?: "",
            album = prefs.getString("album", "") ?: "",
            searchType = prefs.getString("searchType", SpotifySearchType.Track.name)
                ?.let { runCatching { SpotifySearchType.valueOf(it) }.getOrNull() }
                ?: SpotifySearchType.Track
        )
    }

    fun saveSearchRequest(request: SearchRequest) {
        prefs.edit()
            .putString("query", request.query)
            .putString("title", request.title)
            .putString("artist", request.artist)
            .putString("album", request.album)
            .putString("searchType", request.searchType.name)
            .apply()
    }

    fun isSpotifyConfigured(): Boolean {
        return spotifyClientId.isNotBlank() &&
            spotifyClientId != "YOUR_SPOTIFY_CLIENT_ID" &&
            spotifyRedirectUri.isNotBlank()
    }

    fun saveSpotifyBearerToken(token: String) {
        prefs.edit().putString("spotify_bearer_token", token).apply()
        Log.d(tag, "Saved spotify bearer token")
    }

    suspend fun resolveSpotifyUriFromName(name: String): String? {
        Log.d(tag, "resolveSpotifyUriFromName name=$name")
        if (name.isBlank()) return null
        return resolveSpotifyUri(SearchRequest(query = name))
    }


    suspend fun resolveSpotifyUri(request: SearchRequest): String? {
        Log.d(tag, "resolveSpotifyUri start query=\"${request.query}\" title=\"${request.title}\" artist=\"${request.artist}\" album=\"${request.album}\"")
        // 1. Quick validation for existing URIs
        if (request.query.startsWith("spotify:")) return request.query
        request.query.extractSpotifyTrackUri()?.let { return it }

        // 2. Ensure we have a valid token (get stored or fetch new)
        var token = prefs.getString("spotify_bearer_token", null)
        if (token.isNullOrBlank()) {
            Log.d(tag, "resolveSpotifyUri: no token, fetching new")
            token = fetchClientCredentialsToken()
        } else {
            Log.d(tag, "resolveSpotifyUri: using cached token prefix=${token.take(6)}...")
        }

        if (token == null) {
            Log.w(tag, "resolveSpotifyUri: token unavailable, cannot resolve")
            return null
        }

        val q = buildPrimaryQuery(request).takeIf { it.isNotBlank() } ?: return null
        val types = when (request.searchType) {
            SpotifySearchType.Track -> "track"
            SpotifySearchType.Album -> "album"
            SpotifySearchType.Playlist -> "playlist"
            SpotifySearchType.Artist -> "artist"
            SpotifySearchType.Show -> "show"
            SpotifySearchType.Episode -> "episode"
            SpotifySearchType.Audiobook -> "audiobook"
            SpotifySearchType.Chapter -> "chapter"
        }
        val url = "https://api.spotify.com/v1/search?type=$types&limit=1&q=${Uri.encode(q)}"

        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            runCatching { httpClient.newCall(req).execute() }.getOrElse {
                Log.w(tag, "resolveSpotifyUri network error: ${it.message}")
                return@withContext null
            }.use { response ->
                when {
                    response.code == 401 -> {
                        Log.d(tag, "Token expired, refreshing...")
                        prefs.edit().remove("spotify_bearer_token").apply()
                        val newToken = fetchClientCredentialsToken()
                        return@withContext if (newToken != null) resolveSpotifyUri(request) else null
                    }
                    response.isSuccessful -> {
                        val body = response.body?.string() ?: return@withContext null
                        val root = JSONObject(body)
                        val uri = when (request.searchType) {
                            SpotifySearchType.Track -> root.optJSONObject("tracks")?.optJSONArray("items")?.takeFirstUri()
                                ?: root.optJSONObject("episodes")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Album -> root.optJSONObject("albums")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Playlist -> root.optJSONObject("playlists")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Artist -> root.optJSONObject("artists")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Show -> root.optJSONObject("shows")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Episode -> root.optJSONObject("episodes")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Audiobook -> root.optJSONObject("audiobooks")?.optJSONArray("items")?.takeFirstUri()
                            SpotifySearchType.Chapter -> root.optJSONObject("chapters")?.optJSONArray("items")?.takeFirstUri()
                        }
                        Log.d(tag, "resolveSpotifyUri success query=\"$q\" type=${request.searchType} uri=$uri")
                        return@withContext uri
                    }
                    else -> {
                        Log.w(tag, "resolveSpotifyUri failed status=${response.code}")
                        return@withContext null
                    }
                }
            }
        }
    }


    private suspend fun fetchClientCredentialsToken(): String? = withContext(Dispatchers.IO) {
        Log.d(tag, "fetchClientCredentialsToken: Starting request...")
        
        val clientId = "6f4b3740a1b94069bcc2f4d16fb2c919"
        val clientSecret = "09d3bbb540c74d0fb36e185678eda5a2"

        // Use .trim() on the final string to remove any hidden whitespaces
        val authUrl = "https://accounts.spotify.com/api/token".trim()

        val base64Auth = android.util.Base64.encodeToString(
            "$clientId:$clientSecret".toByteArray(),
            android.util.Base64.NO_WRAP
        )

        val formBody = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .build()

        val request = Request.Builder()
            .url(authUrl) // Use the trimmed URL
            .post(formBody)
            .addHeader("Authorization", "Basic $base64Auth")
            .addHeader("Content-Type", "application/x-www-form-urlencoded") 
            .build()

        return@withContext runCatching {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val token = json.getString("access_token")
                    prefs.edit().putString("spotify_bearer_token", token).apply()
                    Log.d(tag, "fetchClientCredentialsToken: SUCCESS.")
                    token
                } else {
                    Log.w(tag, "fetchClientCredentialsToken: FAILED. Code=${response.code} Body=$responseBody")
                    null
                }
            }
        }.onFailure {
            Log.e(tag, "fetchClientCredentialsToken: Exception: ${it.message}")
        }.getOrNull()
    }



    fun playFromSearch(request: SearchRequest) {
        Log.d(
            tag,
            "playFromSearch() query=\"${request.query}\" title=\"${request.title}\" artist=\"${request.artist}\" album=\"${request.album}\" selected=${_sessionState.value.selectedApp?.packageName}"
        )
        if (request.isEmpty) return
        val extras = buildSearchExtras(request)
        val primaryQuery = buildPrimaryQuery(request)
        val controller = activeController
        if (controller != null && controller.packageName == _sessionState.value.selectedApp?.packageName) {
            val actions = controller.playbackState?.actions ?: 0L
            if (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
                Log.d(tag, "playFromSearch -> transportControls.playFromSearch (controller pkg=${controller.packageName}) query=\"$primaryQuery\" extras=$extras")
                controller.transportControls.playFromSearch(primaryQuery, extras)
                return
            } else {
                Log.d(tag, "playFromSearch skipped: ACTION_PLAY_FROM_SEARCH not supported by pkg=${controller.packageName}")
            }
        }
        val targetPackage = _sessionState.value.selectedApp?.packageName ?: return

        launchSearchIntent(request, targetPackage, extras)
    }

    fun playSpotifyFromSearch(request: SearchRequest) {
        if (request.isEmpty) return
        val extras = buildSearchExtras(request)
        val primaryQuery = buildPrimaryQuery(request).ifBlank { request.query }
        val controller = activeController
        if (controller != null && controller.packageName == "com.spotify.music") {
            val actions = controller.playbackState?.actions ?: 0L
            if (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
                Log.d(tag, "playSpotifyFromSearch -> transportControls.playFromSearch query=\"$primaryQuery\" extras=$extras")
                controller.transportControls.playFromSearch(primaryQuery, extras)
                return
            } else {
                Log.d(tag, "playSpotifyFromSearch: controller lacks PLAY_FROM_SEARCH, falling back to intent")
            }
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(SearchManager.QUERY, primaryQuery)
            putExtras(extras)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { err -> Log.w(tag, "playSpotifyFromSearch intent failed: ${err.message}") }
    }

    suspend fun playSpotifyWithId(request: SearchRequest, resolvedUri: String? = null) {
        val uri = resolvedUri ?: resolveSpotifyUri(request)
        if (uri.isNullOrBlank()) {
            Log.w(tag, "playSpotifyWithId: no uri resolved")
            return
        }
        val mediaId = uri.extractSpotifyTrackId()
        if (mediaId.isNullOrBlank()) {
            Log.w(tag, "playSpotifyWithId: could not extract mediaId from uri=$uri")
            return
        }
        val controller = activeController
        val actions = controller?.playbackState?.actions ?: 0L
        if (controller != null && controller.packageName == "com.spotify.music" &&
            actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L
        ) {
            Log.d(tag, "playSpotifyWithId -> playFromMediaId id=$mediaId")
            controller.transportControls.playFromMediaId(mediaId, null)
        } else {
            Log.w(tag, "playSpotifyWithId: playFromMediaId unsupported; falling back to playSpotifyUri")
            playSpotifyUri(uri)
        }
    }

    private fun String.extractSpotifyTrackId(): String? {
        return when {
            startsWith("spotify:track:") -> substringAfter("spotify:track:")
            contains("open.spotify.com/track/") -> extractSpotifyTrackUri()?.substringAfter("spotify:track:")
            else -> null
        }
    }

    private fun org.json.JSONArray.takeFirstUri(): String? {
        if (length() == 0) return null
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val uri = obj.optString("uri", null)
            if (!uri.isNullOrBlank()) return uri
        }
        return null
    }

    // Hard-coded helper to trigger a target media app to play "Thriller" by Michael Jackson.
    fun playThrillerForPackage(targetPackage: String?) {
        if (targetPackage.isNullOrBlank()) {
            Log.d(tag, "playThrillerForPackage skipped: targetPackage is null/blank")
            return
        }

        // Special-case Spotify: use a direct URI to improve autoplay chances.
        if (targetPackage == "com.spotify.music") {
            playSpotifyUri(uri = "spotify:track:2LlDHnm4y3fT5KAXp21xli")
            return
        }

        val query = "Play Thriller by Michael Jackson"
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_TITLE, "Thriller")
            putString(MediaStore.EXTRA_MEDIA_ARTIST, "Michael Jackson")
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        }

        // Ensure we have the latest controllers and pick the target controller if present.
        var controller = controllers.firstOrNull { it.packageName == targetPackage }
        if (controller == null) {
            val refreshed = loadActiveSessions()
            handleUpdatedControllers(refreshed)
            controller = refreshed.firstOrNull { it.packageName == targetPackage }
        }

        val actions = controller?.playbackState?.actions ?: 0L
        if (controller != null && actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) {
            Log.d(tag, "playThrillerForPackage -> transportControls.playFromSearch pkg=$targetPackage query=\"$query\" extras=$extras actions=${describeActions(actions)}")
            controller.transportControls.playFromSearch(query, extras)
            return
        } else if (controller != null) {
            Log.d(tag, "playThrillerForPackage controller found but PLAY_FROM_SEARCH unsupported; actions=${describeActions(actions)}")
        } else {
            Log.d(tag, "playThrillerForPackage no active controller for pkg=$targetPackage; falling back to intent")
        }

        Log.d(tag, "playThrillerForPackage -> fallback intent to pkg=$targetPackage query=\"$query\" extras=$extras")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(targetPackage)
            putExtra(SearchManager.QUERY, query)
            putExtras(extras)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { err ->
                Log.w(tag, "playThrillerForPackage fallback failed: ${err.message}")
            }
    }

    fun playSpotifyUri(uri: String) {
        if (uri.isBlank()) {
            Log.w(tag, "playSpotifyUri: uri is blank, skipping")
            return
        }
        if (spotifyClientId.isBlank() || spotifyRedirectUri.isBlank()) {
            Log.w(tag, "playSpotifyUri: App Remote not configured (clientId/redirectUri missing), falling back to ACTION_VIEW uri=$uri")
            launchSpotifyIntent(uri)
            return
        }
        val params = ConnectionParams.Builder(spotifyClientId)
            .setRedirectUri(spotifyRedirectUri)
            .showAuthView(true)
            .build()

        Log.w(tag, "playSpotifyUri: playing uri=$uri")

        SpotifyAppRemote.connect(
            context,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    spotifyRemote = appRemote
                    Log.d(tag, "SpotifyAppRemote connected, playing uri=$uri")
                    appRemote.playerApi.play(uri)
                }

                override fun onFailure(throwable: Throwable) {
                    Log.w(tag, "SpotifyAppRemote connection failed: ${throwable.message}, falling back to ACTION_VIEW")
                    if (throwable.message?.contains("authorization", ignoreCase = true) == true) {
                        notifySpotifyAuthRequired()
                    }
                    launchSpotifyIntent(uri)
                }
            }
        )
    }

    private fun playSpotifySearchUri(query: String) {
        if (query.isBlank()) return
        launchSpotifyIntent("spotify:search:${Uri.encode(query)}")
    }

    private fun launchSpotifyIntent(uri: String) {
        if (uri.isBlank()) {
            Log.w(tag, "launchSpotifyIntent skipped: uri is blank")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage("com.spotify.music")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching {
            context.startActivity(intent)
            Log.d(tag, "launchSpotifyIntent ACTION_VIEW for $uri")
        }.onFailure { err ->
            Log.w(tag, "launchSpotifyIntent failed: ${err.message}")
        }
    }

    private fun String.extractSpotifyTrackUri(): String? {
        val marker = "open.spotify.com/track/"
        val index = indexOf(marker)
        if (index == -1) return null
        val start = index + marker.length
        val end = indexOf('?', start).takeIf { it != -1 } ?: length
        val id = substring(start, end)
        if (id.isBlank()) return null
        return "spotify:track:$id"
    }

    private fun disconnectSpotifyRemote() {
        spotifyRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
        spotifyRemote = null
    }

    private fun notifySpotifyAuthRequired() {
        handler.post {
            Toast.makeText(
                context,
                "Spotify needs authorization. Please approve the prompt and try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun play() {
        Log.d(tag, "play() via transportControls pkg=${activeController?.packageName}")
        activeController?.transportControls?.play()
    }

    fun pause() {
        Log.d(tag, "pause() via transportControls pkg=${activeController?.packageName}")
        activeController?.transportControls?.pause()
    }

    fun skipToNext() {
        Log.d(tag, "skipToNext() via transportControls pkg=${activeController?.packageName}")
        activeController?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        Log.d(tag, "skipToPrevious() via transportControls pkg=${activeController?.packageName}")
        activeController?.transportControls?.skipToPrevious()
    }

    fun fastForward() {
        Log.d(tag, "fastForward() via transportControls pkg=${activeController?.packageName}")
        activeController?.transportControls?.fastForward()
    }

    fun rewind() {
        Log.d(tag, "rewind() via transportControls pkg=${activeController?.packageName}")
        activeController?.transportControls?.rewind()
    }

    fun refreshActiveSessions() {
        scope.launch {
            val hasAccess = hasNotificationAccess()
            Log.d(tag, "refreshActiveSessions() hasAccess=$hasAccess listenerRegistered=$listenerRegistered")
            if (hasAccess && !listenerRegistered) {
                registerSessionListener()
            }
            val currentControllers = loadActiveSessions()
            Log.d(tag, "refreshActiveSessions() controllers=${currentControllers.map { it.packageName }}")
            _sessionState.value = _sessionState.value.copy(notificationAccessGranted = hasAccess)
            handleUpdatedControllers(currentControllers)
        }
    }

    fun dispose() {
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: SecurityException) {
            // Listener was never registered due to missing permission.
        }
        activeController?.unregisterCallback(controllerCallback)
        disconnectSpotifyRemote()
        scope.cancel()
    }

    private fun registerSessionListener() {
        if (!hasNotificationAccess()) {
            _sessionState.value = _sessionState.value.copy(notificationAccessGranted = false)
            return
        }

        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionListener,
                notificationComponent,
                handler
            )
            listenerRegistered = true
            Log.d(tag, "registerSessionListener() success")
        } catch (_: SecurityException) {
            _sessionState.value = _sessionState.value.copy(notificationAccessGranted = false)
            listenerRegistered = false
            Log.d(tag, "registerSessionListener() failed SecurityException")
        }
    }

    private fun loadActiveSessions(): List<MediaController> {
        if (!hasNotificationAccess()) return emptyList()
        val primary = try {
            sessionManager.getActiveSessions(notificationComponent).orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (primary.isNotEmpty()) return primary

        // Fallback: some OEMs return only with null component once access is granted.
        val fallback = try {
            sessionManager.getActiveSessions(null).orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        return fallback
    }

    private fun handleUpdatedControllers(newControllers: List<MediaController>) {
        Log.d(tag, "handleUpdatedControllers() incoming=${newControllers.map { it.packageName }}")
        controllers = newControllers
        val installedApps = discoverMediaApps()
        val controllerApps = newControllers
            .map { it.packageName }
            .distinct()
            .map { packageName ->
                MediaApp(packageName = packageName, displayName = resolveAppLabel(packageName))
            }
        val availableApps = (installedApps + controllerApps).distinctBy { it.packageName }

        val selected = _sessionState.value.selectedApp
        val maintainedSelection = selected?.let { existing ->
            availableApps.firstOrNull { it.packageName == existing.packageName }
        }

        _sessionState.value = _sessionState.value.copy(
            availableApps = availableApps,
            selectedApp = maintainedSelection
        )

        if (maintainedSelection != null) {
            val controller = newControllers.firstOrNull { it.packageName == maintainedSelection.packageName }
            setActiveController(controller)
        } else {
            setActiveController(newControllers.firstOrNull())
        }
    }

    private fun setActiveController(controller: MediaController?, forcedSelection: MediaApp? = null) {
        if (activeController == controller) return

        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback)

        val selectedApp = forcedSelection ?: controller?.let {
            MediaApp(packageName = it.packageName, displayName = resolveAppLabel(it.packageName))
        }

        val actions = controller?.playbackState?.actions ?: 0L
        Log.d(
            tag,
            "setActiveController() controllerPkg=${controller?.packageName} selected=${selectedApp?.packageName} actions=${describeActions(actions)}"
        )

        updateState {
            it.copy(
                selectedApp = selectedApp,
                nowPlaying = controller?.metadata.toNowPlaying(),
                playbackStatus = controller?.playbackState.toPlaybackStatus()
            )
        }
    }

    private fun updateNowPlaying(metadata: MediaMetadata?, playbackState: PlaybackState?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        Log.d(tag, "updateNowPlaying() title=$title artist=$artist state=${playbackState?.state}")
        updateState {
            it.copy(
                nowPlaying = metadata.toNowPlaying(),
                playbackStatus = playbackState.toPlaybackStatus()
            )
        }
    }

    private fun updateState(transform: (MediaSessionState) -> MediaSessionState) {
        _sessionState.value = transform(_sessionState.value)
    }

    private fun launchSearchIntent(request: SearchRequest, targetPackage: String, extras: Bundle) {
        Log.d(tag, "launchSearchIntent() request=$request target=$targetPackage")
        val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(targetPackage)
            putExtras(extras)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val mediaSearchIntent = Intent("android.intent.action.MEDIA_SEARCH").apply {
            setPackage(targetPackage)
            putExtras(extras)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        runCatching {
            context.startActivity(searchIntent)
            Log.d(tag, "launchSearchIntent() started MEDIA_PLAY_FROM_SEARCH for $targetPackage")
        }.onFailure { err ->
            Log.w(tag, "launchSearchIntent() MEDIA_PLAY_FROM_SEARCH failed: ${err.message}")
            runCatching {
                context.startActivity(mediaSearchIntent)
                Log.d(tag, "launchSearchIntent() started ACTION_MEDIA_SEARCH for $targetPackage")
            }.onFailure { err2 ->
                Log.w(tag, "launchSearchIntent() ACTION_MEDIA_SEARCH failed: ${err2.message}")
            }
        }
    }

    private fun MediaMetadata?.toNowPlaying(): NowPlaying? {
        if (this == null) return null
        return NowPlaying(
            title = getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = getString(MediaMetadata.METADATA_KEY_ALBUM)
        )
    }

    private fun PlaybackState?.toPlaybackStatus(): PlaybackStatus {
        return when (this?.state) {
            PlaybackState.STATE_PLAYING -> PlaybackStatus.Playing
            PlaybackState.STATE_PAUSED -> PlaybackStatus.Paused
            PlaybackState.STATE_BUFFERING -> PlaybackStatus.Buffering
            PlaybackState.STATE_STOPPED -> PlaybackStatus.Stopped
            else -> PlaybackStatus.Idle
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return runCatching {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    private fun describeActions(actions: Long): String {
        val supported = mutableListOf<String>()
        if (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) supported.add("PLAY_FROM_SEARCH")
        if (actions and PlaybackState.ACTION_PLAY != 0L) supported.add("PLAY")
        if (actions and PlaybackState.ACTION_PAUSE != 0L) supported.add("PAUSE")
        if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) supported.add("NEXT")
        if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) supported.add("PREV")
        if (actions and PlaybackState.ACTION_FAST_FORWARD != 0L) supported.add("FF")
        if (actions and PlaybackState.ACTION_REWIND != 0L) supported.add("REW")
        return supported.ifEmpty { listOf("none") }.joinToString(",")
    }

    private fun buildSearchExtras(request: SearchRequest): Bundle {
        return Bundle().apply {
            // Do not include QUERY for transportControls; only used in intent fallback.
            if (request.artist.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ARTIST, request.artist)
            if (request.title.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_TITLE, request.title)
            if (request.album.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ALBUM, request.album)
            putString(MediaStore.EXTRA_MEDIA_FOCUS, focusForType(request.searchType))
        }
    }

    private fun focusForType(type: SpotifySearchType): String = when (type) {
        SpotifySearchType.Track -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        SpotifySearchType.Episode -> "vnd.android.cursor.item/podcast_episode"
        SpotifySearchType.Audiobook -> "vnd.android.cursor.item/audiobook"
        SpotifySearchType.Chapter -> "vnd.android.cursor.item/audiobook_chapter"
        SpotifySearchType.Album -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
        SpotifySearchType.Artist -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
        SpotifySearchType.Playlist -> MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
        SpotifySearchType.Show -> "vnd.android.cursor.item/podcast" // best-effort for podcast/show
    }

    private fun buildPrimaryQuery(request: SearchRequest): String {
        // Prefer a simple natural query: "artist title album"
        val parts = buildList {
            if (request.artist.isNotBlank()) add(request.artist)
            if (request.title.isNotBlank()) add(request.title)
            if (request.album.isNotBlank()) add(request.album)
        }
        val natural = parts.joinToString(" ").trim()
        return if (natural.isNotBlank()) natural else request.query
    }

    // Provider-specific deep links removed to avoid forcing app UI; using generic media search/play.

    private fun discoverMediaApps(): List<MediaApp> {
        val pm = context.packageManager
        val packageNames = mutableSetOf<String>()

        val musicIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        pm.queryIntentActivities(musicIntent, 0)
            .forEach { packageNames.add(it.activityInfo.packageName) }

        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        pm.queryBroadcastReceivers(mediaButtonIntent, 0)
            .forEach { packageNames.add(it.activityInfo.packageName) }

        val mediaBrowserIntent = Intent("android.media.browse.MediaBrowserService")
        pm.queryIntentServices(mediaBrowserIntent, 0)
            .forEach { info ->
                info.serviceInfo?.packageName?.let { packageNames.add(it) }
            }

        val knownPackages = listOf(
            "com.spotify.music",
            "com.amazon.mp3",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music"
        )
        knownPackages.forEach { pkg ->
            if (runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) {
                packageNames.add(pkg)
            }
        }

        return packageNames.map { pkg ->
            MediaApp(packageName = pkg, displayName = resolveAppLabel(pkg))
        }.sortedBy { it.displayName.lowercase() }
    }
}
