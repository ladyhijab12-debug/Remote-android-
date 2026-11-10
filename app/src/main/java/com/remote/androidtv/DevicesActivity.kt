package com.remote.androidtv

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.remote.androidtv.protocol.IrCodeSet
import com.remote.androidtv.protocol.PairingClient
import com.remote.androidtv.protocol.TvBrand

/** Start screen: pick a TV (found automatically or typed by IP), pair if needed, then open the remote. */
class DevicesActivity : AppCompatActivity() {

    private lateinit var discovery: TvDiscovery
    private lateinit var adapter: ArrayAdapter<TvDevice>
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var ipInput: EditText
    private var busy = false

    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        TvConnection.init(applicationContext)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        ipInput = findViewById(R.id.ip_input)
        val lastBrand = prefs.getString(KEY_BRAND, null)?.let { runCatching { TvBrand.valueOf(it) }.getOrNull() }
        // For an infrared remote the saved "host" is a code set, not an address.
        if (lastBrand != TvBrand.IR) ipInput.setText(prefs.getString(KEY_HOST, ""))

        adapter = ArrayAdapter(this, R.layout.item_device, R.id.device_text, mutableListOf<TvDevice>())
        val list = findViewById<ListView>(R.id.device_list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.getItem(pos)?.let { connect(it.host, it.name, it.brand) }
        }

        findViewById<Button>(R.id.connect_button).setOnClickListener {
            val host = ipInput.text.toString().trim()
            if (host.isEmpty()) ipInput.error = getString(R.string.enter_ip) else connect(host, null, null)
        }

        findViewById<Button>(R.id.ir_button).apply {
            visibility = if (TvConnection.hasIr) View.VISIBLE else View.GONE
            setOnClickListener { chooseIrBrand() }
        }

        discovery = TvDiscovery(this) { devices ->
            adapter.clear()
            adapter.addAll(devices)
        }

        val lastHost = prefs.getString(KEY_HOST, null)
        if (savedInstanceState == null && lastHost != null) {
            connect(lastHost, prefs.getString(KEY_NAME, null), lastBrand)
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    private fun setBusy(isBusy: Boolean, message: String?) {
        busy = isBusy
        progress.visibility = if (isBusy) View.VISIBLE else View.GONE
        status.text = message ?: ""
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun connect(host: String, name: String?, brand: TvBrand?) {
        if (busy) return
        setBusy(true, getString(R.string.connecting, name ?: host))
        TvConnection.connect(host, name, brand, onDetected = { detected ->
            // Samsung and LG TVs ask for permission on their screen the first time.
            if (detected == TvBrand.SAMSUNG) setBusy(true, getString(R.string.samsung_allow))
            if (detected == TvBrand.LG) setBusy(true, getString(R.string.lg_allow))
        }) { result ->
            when (result) {
                TvConnection.Result.Ok -> {
                    setBusy(false, null)
                    prefs.edit()
                        .putString(KEY_HOST, host)
                        .putString(KEY_NAME, TvConnection.tvName)
                        .putString(KEY_BRAND, TvConnection.brand?.name)
                        .apply()
                    startActivity(Intent(this, RemoteActivity::class.java))
                }
                TvConnection.Result.NeedsPairing -> startPairing(host, name)
                TvConnection.Result.Denied -> setBusy(
                    false,
                    getString(if (TvConnection.brand == TvBrand.LG) R.string.lg_denied else R.string.samsung_denied),
                )
                is TvConnection.Result.Failed ->
                    setBusy(false, getString(R.string.connect_failed, result.error.localizedMessage ?: ""))
                TvConnection.Result.WrongCode -> setBusy(false, null)
            }
        }
    }

    /** Infrared: there is nothing to find on the network, the user says which brand the TV is. */
    private fun chooseIrBrand() {
        val sets = IrCodeSet.values()
        AlertDialog.Builder(this)
            .setTitle(R.string.ir_choose)
            .setItems(sets.map { it.label }.toTypedArray()) { _, which ->
                connect(sets[which].name, getString(R.string.ir_name, sets[which].label), TvBrand.IR)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPairing(host: String, name: String?) {
        setBusy(true, getString(R.string.pairing_start))
        TvConnection.startPairing(host) { result ->
            if (result == TvConnection.Result.Ok) {
                setBusy(false, null)
                askForCode(host, name, showError = false)
            } else {
                val msg = (result as? TvConnection.Result.Failed)?.error?.localizedMessage ?: ""
                setBusy(false, getString(R.string.pairing_failed, msg))
            }
        }
    }

    private fun askForCode(host: String, name: String?, showError: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(PairingClient.CODE_LENGTH), InputFilter.AllCaps())
            hint = "A1B2C3"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 28f
            if (showError) error = getString(R.string.wrong_code)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(R.string.pair_message)
            .setView(input.also { it.setPadding(pad, pad, pad, pad) })
            .setCancelable(false)
            .setPositiveButton(R.string.pair_button, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> TvConnection.cancelPairing() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString()
                if (PairingClient.normalizeCode(code) == null) {
                    input.error = getString(R.string.code_format)
                    return@setOnClickListener
                }
                dialog.dismiss()
                setBusy(true, getString(R.string.pairing_check))
                TvConnection.finishPairing(code) { result ->
                    when (result) {
                        TvConnection.Result.Ok -> {
                            setBusy(false, null)
                            Toast.makeText(this, R.string.paired, Toast.LENGTH_SHORT).show()
                            connect(host, name, TvBrand.ANDROID_TV)
                        }
                        TvConnection.Result.WrongCode -> {
                            setBusy(false, null)
                            askForCode(host, name, showError = true)
                        }
                        else -> {
                            val msg = (result as? TvConnection.Result.Failed)?.error?.localizedMessage ?: ""
                            setBusy(false, getString(R.string.pairing_failed, msg))
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_NAME = "name"
        const val KEY_BRAND = "brand"
    }
}
