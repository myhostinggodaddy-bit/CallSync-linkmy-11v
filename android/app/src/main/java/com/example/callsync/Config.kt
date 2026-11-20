package com.example.callsync

object Config {
    /** Folder on your hosting that contains register.php and sync.php (no trailing slash). */
    const val SERVER_URL = "https://linkmy.in/backend"

    /** How often the background service uploads new calls. */
    const val SYNC_INTERVAL_MS = 5L * 60 * 1000

    /** Version of the screens bundled inside the APK (assets/www/index.html). */
    const val UI_VERSION = 7
}
