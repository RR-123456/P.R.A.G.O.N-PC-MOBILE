package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/** Settings screen (assets/settings.html): pairing (QR / manual), permissions, forget this PC. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pairing = false

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon."); return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF0A0A0F.toInt())
            settings.javaScriptEnabled = true
            addJavascriptInterface(Bridge2(), "PragonNative")
            loadUrl("file:///android_asset/settings.html")
        }
        setContentView(web)
    }

    inner class Bridge2 {
        @JavascriptInterface fun getState(): String {
            val pm = getSystemService(PowerManager::class.java)
            val host = Prefs.host(this@SettingsActivity)
            return JSONObject()
                .put("paired", Bridge.status.startsWith("Connected"))
                .put("host", if (host.isBlank()) "" else "$host:${Prefs.port(this@SettingsActivity)}")
                .put("status", Bridge.status)
                .put("a11y", PragonAccessibilityService.instance != null)
                .put("overlay", Settings.canDrawOverlays(this@SettingsActivity))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .toString()
        }
        @JavascriptInterface fun scanQr() {
            runOnUiThread {
                scan.launch(
                    ScanOptions().setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                        .setBeepEnabled(false).setOrientationLocked(false)
                )
            }
        }
        @JavascriptInterface fun connect(hostPort: String, key: String) {
            val parts = hostPort.trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else runOnUiThread { startPairing(host, port, key) }
        }
        @JavascriptInterface fun getAi(): String {
            val own = Prefs.aiKey(this@SettingsActivity)
            return JSONObject()
                .put("own", own.isNotBlank())
                .put("pc", Prefs.pcAiKey(this@SettingsActivity).isNotBlank())
                .put("model", Prefs.aiModel(this@SettingsActivity))
                .put("hint", if (own.length > 8) own.take(4) + "..." + own.takeLast(3) else "")
                .toString()
        }
        @JavascriptInterface fun saveAi(key: String, model: String) {
            val cur = Prefs.aiKey(this@SettingsActivity)
            Prefs.saveAi(this@SettingsActivity, if (key.isBlank()) cur else key.trim(), model.trim())
            toast("Saved.")
        }
        @JavascriptInterface fun clearAiKey() {
            Prefs.saveAi(this@SettingsActivity, "", Prefs.aiModel(this@SettingsActivity))
            toast("Your key was removed.")
        }
        @JavascriptInterface fun openA11y() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Find PragonMobile in the list and turn it on.")
            }
        }
        @JavascriptInterface fun openOverlay() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun openBattery() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun forget() {
            runOnUiThread {
                ContextCompat.startForegroundService(
                    this@SettingsActivity,
                    Intent(this@SettingsActivity, PragonService::class.java).setAction(PragonService.ACTION_STOP)
                )
                Prefs.clear(this@SettingsActivity)
                RemoteBridge.socket = null
                Bridge.set("Not paired yet - scan the QR from Pragon")
            }
        }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    private fun startPairing(host: String, port: Int, key: String) {
        pairing = true
        Prefs.save(this, host, port, "")
        ContextCompat.startForegroundService(
            this, Intent(this, PragonService::class.java).putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        )
        Bridge.set("Pairing with $host:$port ...")
    }

    override fun onResume() {
        super.onResume()
        // After a successful pairing, go straight back to PhoneView.
        Bridge.listener = { s -> if (pairing && s.startsWith("Connected")) finish() }
    }

    override fun onPause() { Bridge.listener = null; super.onPause() }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
}
