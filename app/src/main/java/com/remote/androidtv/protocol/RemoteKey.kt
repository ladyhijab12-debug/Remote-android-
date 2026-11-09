package com.remote.androidtv.protocol

import java.io.Closeable

/** Remote buttons, with the code each TV brand expects. */
enum class RemoteKey(val androidCode: Int, val samsungKey: String) {
    POWER(TvKeys.POWER, "KEY_POWER"),
    HOME(TvKeys.HOME, "KEY_HOME"),
    BACK(TvKeys.BACK, "KEY_RETURN"),
    UP(TvKeys.DPAD_UP, "KEY_UP"),
    DOWN(TvKeys.DPAD_DOWN, "KEY_DOWN"),
    LEFT(TvKeys.DPAD_LEFT, "KEY_LEFT"),
    RIGHT(TvKeys.DPAD_RIGHT, "KEY_RIGHT"),
    OK(TvKeys.DPAD_CENTER, "KEY_ENTER"),
    VOLUME_UP(TvKeys.VOLUME_UP, "KEY_VOLUP"),
    VOLUME_DOWN(TvKeys.VOLUME_DOWN, "KEY_VOLDOWN"),
    MUTE(TvKeys.VOLUME_MUTE, "KEY_MUTE"),
    CHANNEL_UP(TvKeys.CHANNEL_UP, "KEY_CHUP"),
    CHANNEL_DOWN(TvKeys.CHANNEL_DOWN, "KEY_CHDOWN"),
    INPUT(TvKeys.TV_INPUT, "KEY_SOURCE"),
    SETTINGS(TvKeys.SETTINGS, "KEY_MENU"),
    MENU(TvKeys.MENU, "KEY_TOOLS"),
    REWIND(TvKeys.MEDIA_REWIND, "KEY_REWIND"),
    PLAY_PAUSE(TvKeys.MEDIA_PLAY_PAUSE, "KEY_PLAY"),
    FAST_FORWARD(TvKeys.MEDIA_FAST_FORWARD, "KEY_FF"),
}

enum class KeyAction { CLICK, PRESS, RELEASE }

/** Apps that can be opened directly. */
enum class TvApp(val androidLink: String, val samsungAppId: String) {
    YOUTUBE("https://www.youtube.com", "111299001912"),
    NETFLIX("https://www.netflix.com/title", "11101200001"),
}

enum class TvBrand { ANDROID_TV, SAMSUNG }

/** A connected TV of any brand. Methods do network I/O: call them off the main thread. */
interface TvController : Closeable {
    val isConnected: Boolean
    fun press(key: RemoteKey, action: KeyAction = KeyAction.CLICK)
    fun launch(app: TvApp)
}
