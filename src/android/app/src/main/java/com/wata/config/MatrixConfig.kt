package com.wata.config

/**
 * Matrix server configuration.
 *
 * These credentials are hardcoded for build-time configuration since the target
 * device (ABBREE PTT handheld) has no keyboard for login screens.
 *
 * To change credentials, modify this file before building the APK.
 * Future: Replace with QR code provisioning or companion config app.
 */
object MatrixConfig {
    /**
     * Matrix homeserver URL.
     * - For emulator: http://10.0.2.2:8008 (localhost forwarding)
     * - For physical device with ADB reverse: http://localhost:8008
     * - For production: https://matrix.example.com
     */
    const val HOMESERVER_URL = "http://macmini.fritz.box:8008"

    /**
     * Username to login with (without @user:server prefix)
     */
    const val USERNAME = "alice"

    /**
     * Password for the user
     */
    const val PASSWORD = "testpass123"

    /**
     * Device display name shown in Matrix client list
     */
    const val DEVICE_NAME = "Wata Android"
}
