package com.example.generalmediabrowser.media.model

enum class SpotifySearchType {
    Track,
    Album,
    Playlist,
    Artist,
    Show,
    Episode,
    Audiobook,
    Chapter
}

data class SearchRequest(
    val query: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val searchType: SpotifySearchType = SpotifySearchType.Track
) {
    val isEmpty: Boolean
        get() = query.isBlank() && title.isBlank() && artist.isBlank() && album.isBlank()
}
