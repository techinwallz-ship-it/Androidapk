package com.example.pisignage

/**
 * Single source of truth for endpoints and storage keys that were previously hardcoded across
 * several files. Values are byte-identical to the old literals — this is a centralization, not a
 * behavior change. (AUDIT P3 #8)
 */
object AppConfig {
    const val API_BASE_URL = "https://api.inwallz.in"
    const val SOCKET_URL = "https://api.inwallz.in"

    const val PREFS_NAME = "signage"
    const val KEY_PAIRING = "pairing_code"
    const val KEY_PLAYLIST = "last_playlist"
}
