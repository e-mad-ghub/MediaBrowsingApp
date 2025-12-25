package com.example.generalmediabrowser.media.model

data class MediaApp(
    val packageName: String,
    val displayName: String
)

data class NowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?
)

enum class PlaybackStatus {
    Idle,
    Playing,
    Paused,
    Buffering,
    Stopped
}
