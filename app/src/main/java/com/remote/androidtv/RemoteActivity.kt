package com.remote.androidtv

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.remote.androidtv.protocol.KeyAction
import com.remote.androidtv.protocol.RemoteKey
import com.remote.androidtv.protocol.TvApp

class RemoteActivity : AppCompatActivity(), TvConnection.StateListener {

    private lateinit var title: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        TvConnection.init(applicationContext)
        title = findViewById(R.id.tv_name)
        status = findViewById(R.id.connection_status)
        title.text = TvConnection.tvName ?: ""

        mapOf(
            R.id.key_power to RemoteKey.POWER,
            R.id.key_input to RemoteKey.INPUT,
            R.id.key_settings to RemoteKey.SETTINGS,
            R.id.key_back to RemoteKey.BACK,
            R.id.key_home to RemoteKey.HOME,
            R.id.key_menu to RemoteKey.MENU,
            R.id.key_up to RemoteKey.UP,
            R.id.key_down to RemoteKey.DOWN,
            R.id.key_left to RemoteKey.LEFT,
            R.id.key_right to RemoteKey.RIGHT,
            R.id.key_ok to RemoteKey.OK,
            R.id.key_vol_up to RemoteKey.VOLUME_UP,
            R.id.key_vol_down to RemoteKey.VOLUME_DOWN,
            R.id.key_mute to RemoteKey.MUTE,
            R.id.key_ch_up to RemoteKey.CHANNEL_UP,
            R.id.key_ch_down to RemoteKey.CHANNEL_DOWN,
            R.id.key_rewind to RemoteKey.REWIND,
            R.id.key_play to RemoteKey.PLAY_PAUSE,
            R.id.key_forward to RemoteKey.FAST_FORWARD,
        ).forEach { (id, key) -> bindKey(findViewById(id), key) }

        findViewById<View>(R.id.app_youtube).setOnClickListener {
            it.haptic(); TvConnection.launch(TvApp.YOUTUBE)
        }
        findViewById<View>(R.id.app_netflix).setOnClickListener {
            it.haptic(); TvConnection.launch(TvApp.NETFLIX)
        }
        status.setOnClickListener { reconnect() }
    }

    override fun onStart() {
        super.onStart()
        TvConnection.addListener(this)
        onConnectionChanged(TvConnection.isConnected)
        if (!TvConnection.isConnected) reconnect()
    }

    override fun onStop() {
        TvConnection.removeListener(this)
        super.onStop()
    }

    override fun onConnectionChanged(connected: Boolean) {
        status.setText(if (connected) R.string.connected else R.string.disconnected_tap)
        status.isActivated = connected
    }

    override fun onVolumeChanged(level: Int, max: Int, muted: Boolean) {
        if (max > 0) {
            status.text = getString(R.string.volume_status, if (muted) 0 else level * 100 / max)
        }
    }

    private fun reconnect() {
        val host = TvConnection.host ?: return finish()
        status.setText(R.string.reconnecting)
        TvConnection.connect(host, TvConnection.tvName, TvConnection.brand) { result ->
            // Pairing lives on the start screen; go back there if the TV forgot us.
            if (result == TvConnection.Result.NeedsPairing || result == TvConnection.Result.Denied) finish()
            else onConnectionChanged(TvConnection.isConnected)
        }
    }

    /** The phone's own volume buttons control the TV while the remote is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { TvConnection.press(RemoteKey.VOLUME_UP); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { TvConnection.press(RemoteKey.VOLUME_DOWN); true }
        else -> super.onKeyDown(keyCode, event)
    }

    /** Tap = one press. Holding a key keeps it held on the TV (scrolling, seeking, long-press menus). */
    private fun bindKey(view: View, key: RemoteKey) {
        var longPressed = false
        val startLong = Runnable {
            longPressed = true
            view.haptic()
            TvConnection.press(key, KeyAction.PRESS)
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    longPressed = false
                    v.postDelayed(startLong, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(startLong)
                    if (longPressed) {
                        TvConnection.press(key, KeyAction.RELEASE)
                    } else {
                        v.haptic()
                        TvConnection.press(key)
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(startLong)
                    if (longPressed) TvConnection.press(key, KeyAction.RELEASE)
                }
            }
            true
        }
    }

    private fun View.haptic() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private companion object {
        const val LONG_PRESS_MS = 500L
    }
}
