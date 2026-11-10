package com.remote.androidtv

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.remote.androidtv.protocol.AdbClient
import com.remote.androidtv.protocol.AdbKey
import com.remote.androidtv.protocol.AdbNotAllowedException
import com.remote.androidtv.protocol.Launcher
import com.remote.androidtv.protocol.TvTools
import java.io.File
import java.util.concurrent.Executors

/** Android TV maintenance over ADB: clear app caches, and change the home screen (launcher). */
class TvToolsActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var adb: AdbClient? = null
    private var tools: TvTools? = null

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var content: View
    private lateinit var cleanResult: TextView
    private lateinit var launcherList: LinearLayout
    private lateinit var disableStock: CheckBox
    private lateinit var host: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)
        host = TvConnection.host ?: return finish()

        status = findViewById(R.id.tools_status)
        progress = findViewById(R.id.tools_progress)
        content = findViewById(R.id.tools_content)
        cleanResult = findViewById(R.id.clean_result)
        launcherList = findViewById(R.id.launcher_list)
        disableStock = findViewById(R.id.disable_stock)

        findViewById<Button>(R.id.retry_button).setOnClickListener { connect() }
        findViewById<Button>(R.id.clean_button).setOnClickListener { clean() }
        findViewById<Button>(R.id.restore_button).setOnClickListener { confirmRestore() }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refreshLaunchers() }
        connect()
    }

    override fun onDestroy() {
        io.execute { adb?.close() }
        io.shutdown()
        super.onDestroy()
    }

    private fun setBusy(message: String?, busy: Boolean = message != null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = message ?: ""
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Runs [work] on the I/O thread with the connected tools; errors are shown in the status line. */
    private fun <T> run(message: String, work: (TvTools) -> T, done: (T) -> Unit) {
        val t = tools ?: return connect()
        setBusy(message)
        io.execute {
            val result = runCatching { work(t) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { setBusy(null); done(it) }
                    .onFailure {
                        setBusy(getString(R.string.tools_failed, it.localizedMessage ?: ""), busy = false)
                        if (it is java.io.IOException) {
                            // Connection to the TV is gone: the next tap on "retry" reconnects.
                            tools = null
                            findViewById<View>(R.id.retry_button).visibility = View.VISIBLE
                        }
                    }
            }
        }
    }

    private fun connect() {
        content.visibility = View.GONE
        findViewById<View>(R.id.retry_button).visibility = View.GONE
        setBusy(getString(R.string.tools_connecting, host))
        io.execute {
            val client = AdbClient(host, AdbKey(File(filesDir, "adb")), "TV Remote@${Build.MODEL}")
            val result = runCatching {
                client.connect { main.post { setBusy(getString(R.string.tools_allow_on_tv)) } }
                adb?.close()
                adb = client
                TvTools(client).also { tools = it }.launchers()
            }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { launchers ->
                    setBusy(null)
                    content.visibility = View.VISIBLE
                    showLaunchers(launchers)
                }.onFailure { e ->
                    val msg = when (e) {
                        is AdbNotAllowedException -> getString(R.string.tools_not_allowed)
                        else -> getString(R.string.tools_enable_adb, e.localizedMessage ?: "")
                    }
                    setBusy(msg, busy = false)
                    findViewById<View>(R.id.retry_button).visibility = View.VISIBLE
                }
            }
        }
    }

    private fun clean() {
        cleanResult.text = ""
        run(getString(R.string.cleaning), { it.clean() }) { freed ->
            cleanResult.text = if (freed == null) getString(R.string.clean_done)
            else getString(R.string.clean_done_freed, Formatter.formatShortFileSize(this, freed))
        }
    }

    private fun refreshLaunchers() = run(getString(R.string.loading_launchers), { it.launchers() }, ::showLaunchers)

    private fun showLaunchers(launchers: List<Launcher>) {
        launcherList.removeAllViews()
        if (launchers.isEmpty()) {
            launcherList.addView(TextView(this).apply {
                setText(R.string.no_launchers)
                setTextColor(getColor(R.color.text_dim))
            })
        }
        launchers.forEach { launcher ->
            val item = layoutInflater.inflate(R.layout.item_device, launcherList, false) as TextView
            item.text = if (launcher.isCurrent) getString(R.string.launcher_current, launcher.label) else launcher.label
            item.isActivated = launcher.isCurrent
            if (launcher.isCurrent) item.setTextColor(getColor(R.color.ok_green))
            item.setOnClickListener { confirmLauncher(launcher) }
            launcherList.addView(item)
        }
    }

    private fun confirmLauncher(launcher: Launcher) {
        if (launcher.isCurrent) return
        val disable = disableStock.isChecked && !launcher.isStock
        AlertDialog.Builder(this)
            .setTitle(R.string.change_launcher)
            .setMessage(
                getString(R.string.change_launcher_confirm, launcher.label) +
                    if (disable) "\n\n" + getString(R.string.disable_stock_warning) else ""
            )
            .setPositiveButton(R.string.change) { _, _ ->
                run(getString(R.string.changing_launcher), { it.setLauncher(launcher, disable); it.launchers() }) {
                    showLaunchers(it)
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.launcher_changed, launcher.label)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_launcher)
            .setMessage(R.string.restore_launcher_confirm)
            .setPositiveButton(R.string.restore) { _, _ ->
                run(getString(R.string.changing_launcher), { it.restoreStock(); it.launchers() }) {
                    showLaunchers(it)
                    status.visibility = View.VISIBLE
                    status.setText(R.string.launcher_restored)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
