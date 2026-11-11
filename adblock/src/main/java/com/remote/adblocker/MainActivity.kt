package com.remote.adblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var blockedNow: TextView
    private lateinit var blockedTotal: TextView
    private lateinit var listInfo: TextView
    private lateinit var update: Button
    private lateinit var recent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var listSize = 0
    private var updating = false
    /** Total saved before this run started, so the screen can add the live count. */
    private var totalBase = 0L

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startBlocker()
            } else {
                Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        blockedNow = findViewById(R.id.blocked_now)
        blockedTotal = findViewById(R.id.blocked_total)
        listInfo = findViewById(R.id.list_info)
        update = findViewById(R.id.update)
        recent = findViewById(R.id.recent)

        toggle.setOnClickListener {
            if (AdBlockVpnService.running) stopBlocker() else askPermissionsThenStart()
        }
        update.setOnClickListener { updateList() }
        findViewById<Button>(R.id.allow_list).setOnClickListener { editAllowList() }

        totalBase = BlockListStore.totalBlocked(this) - Stats.blocked.get()
        countList()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun render() {
        val on = AdBlockVpnService.running
        status.setText(if (on) R.string.status_on else R.string.status_off)
        status.setTextColor(ContextCompat.getColor(this, if (on) R.color.ok_green else R.color.text_dim))
        toggle.setText(if (on) R.string.turn_off else R.string.turn_on)
        toggle.setBackgroundResource(if (on) R.drawable.bg_button_stop else R.drawable.bg_button_start)

        val session = Stats.blocked.get()
        blockedNow.text = session.toString()
        blockedTotal.text = maxOf(totalBase + session, session).toString()

        val updatedAt = BlockListStore.updatedAt(this)
        val date = if (updatedAt == 0L) {
            getString(R.string.list_builtin)
        } else {
            getString(R.string.list_updated, DateFormat.getDateFormat(this).format(Date(updatedAt)))
        }
        listInfo.text = if (listSize == 0) date else getString(R.string.list_info, listSize, date)
        update.isEnabled = !updating
        update.setText(if (updating) R.string.updating else R.string.update_list)

        val hosts = Stats.recentBlocked()
        recent.text = if (hosts.isEmpty()) getString(R.string.recent_empty) else hosts.joinToString("\n")
    }

    private fun askPermissionsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Either answer is fine: the notification is only a convenience.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpn()
        }
    }

    private fun requestVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnPermission.launch(consent) else startBlocker()
    }

    private fun startBlocker() {
        ContextCompat.startForegroundService(
            this, Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START),
        )
        handler.postDelayed({ render() }, 300)
    }

    private fun stopBlocker() {
        startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
        handler.postDelayed({ render() }, 300)
    }

    /** Tells a running blocker to pick up a new block list or allow list. */
    private fun reloadBlocker() {
        if (AdBlockVpnService.running) {
            startService(
                Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RELOAD),
            )
        }
    }

    private fun countList() {
        val app = applicationContext
        thread(name = "adblock-count") {
            val size = BlockListStore.load(app).size
            handler.post {
                listSize = size
                if (!isDestroyed) render()
            }
        }
    }

    private fun updateList() {
        if (updating) return
        updating = true
        render()
        val app = applicationContext
        thread(name = "adblock-update") {
            val error = try {
                BlockListStore.update(app)
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            handler.post {
                updating = false
                if (error == null) {
                    Toast.makeText(app, R.string.update_done, Toast.LENGTH_SHORT).show()
                    reloadBlocker()
                    countList()
                } else {
                    Toast.makeText(app, getString(R.string.update_failed, error), Toast.LENGTH_LONG).show()
                }
                if (!isDestroyed) render()
            }
        }
    }

    private fun editAllowList() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_URI
            minLines = 4
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            hint = "example.com"
            setText(BlockListStore.allowed(this@MainActivity).sorted().joinToString("\n"))
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.allow_list)
            .setMessage(R.string.allow_list_hint)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                BlockListStore.saveAllowed(this, input.text.toString())
                reloadBlocker()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
