package com.remote.androidtv.protocol

import java.io.Closeable

/** Remote buttons, with the code each kind of device expects. */
enum class RemoteKey(
    val androidCode: Int,
    val samsungKey: String,
    /** Button name on LG webOS's input socket. */
    val lgButton: String,
    /** Key name in Roku's External Control Protocol. */
    val rokuKey: String,
) {
    POWER(TvKeys.POWER, "KEY_POWER", "POWER", "Power"),
    HOME(TvKeys.HOME, "KEY_HOME", "HOME", "Home"),
    BACK(TvKeys.BACK, "KEY_RETURN", "BACK", "Back"),
    UP(TvKeys.DPAD_UP, "KEY_UP", "UP", "Up"),
    DOWN(TvKeys.DPAD_DOWN, "KEY_DOWN", "DOWN", "Down"),
    LEFT(TvKeys.DPAD_LEFT, "KEY_LEFT", "LEFT", "Left"),
    RIGHT(TvKeys.DPAD_RIGHT, "KEY_RIGHT", "RIGHT", "Right"),
    OK(TvKeys.DPAD_CENTER, "KEY_ENTER", "ENTER", "Select"),
    VOLUME_UP(TvKeys.VOLUME_UP, "KEY_VOLUP", "VOLUMEUP", "VolumeUp"),
    VOLUME_DOWN(TvKeys.VOLUME_DOWN, "KEY_VOLDOWN", "VOLUMEDOWN", "VolumeDown"),
    MUTE(TvKeys.VOLUME_MUTE, "KEY_MUTE", "MUTE", "VolumeMute"),
    CHANNEL_UP(TvKeys.CHANNEL_UP, "KEY_CHUP", "CHANNELUP", "ChannelUp"),
    CHANNEL_DOWN(TvKeys.CHANNEL_DOWN, "KEY_CHDOWN", "CHANNELDOWN", "ChannelDown"),
    // Roku has no "source list" key: its inputs are tiles on the home screen.
    INPUT(TvKeys.TV_INPUT, "KEY_SOURCE", "INPUT_HUB", "Home"),
    SETTINGS(TvKeys.SETTINGS, "KEY_MENU", "MENU", "Info"),
    MENU(TvKeys.MENU, "KEY_TOOLS", "QMENU", "Info"),
    REWIND(TvKeys.MEDIA_REWIND, "KEY_REWIND", "REWIND", "Rev"),
    PLAY_PAUSE(TvKeys.MEDIA_PLAY_PAUSE, "KEY_PLAY", "PLAY", "Play"),
    FAST_FORWARD(TvKeys.MEDIA_FAST_FORWARD, "KEY_FF", "FASTFORWARD", "Fwd");

    /** Keys worth repeating while the button is held, on devices that cannot hold a key themselves. */
    val repeats: Boolean
        get() = this in setOf(UP, DOWN, LEFT, RIGHT, VOLUME_UP, VOLUME_DOWN, CHANNEL_UP, CHANNEL_DOWN, REWIND, FAST_FORWARD)
}

enum class KeyAction { CLICK, PRESS, RELEASE }

/** Apps that can be opened directly. */
enum class TvApp(val androidLink: String, val samsungAppId: String, val lgAppId: String, val rokuAppId: String) {
    YOUTUBE("https://www.youtube.com", "111299001912", "youtube.leanback.v4", "837"),
    NETFLIX("https://www.netflix.com/title", "11101200001", "netflix", "12"),
}

/** How a device is controlled. [IR] is the phone's infrared emitter; the others work over Wi-Fi. */
enum class TvBrand(val label: String) {
    ANDROID_TV("Android TV"),
    SAMSUNG("Samsung"),
    LG("LG webOS"),
    ROKU("Roku"),
    IR("IR"),
}

/** A connected device of any kind. Methods do I/O: call them off the main thread. */
interface TvController : Closeable {
    val isConnected: Boolean

    /** False if the device only understands single clicks (PRESS and RELEASE are not sent to it). */
    val supportsHold: Boolean get() = true

    fun press(key: RemoteKey, action: KeyAction = KeyAction.CLICK)
    fun launch(app: TvApp)
}
