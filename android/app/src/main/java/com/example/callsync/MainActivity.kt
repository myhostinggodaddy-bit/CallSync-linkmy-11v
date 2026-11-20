package com.example.callsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    lateinit var web: WebView

    fun js(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0E0E15")
        window.navigationBarColor = Color.parseColor("#17171F")

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0E0E15"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
            setSupportZoom(false)
            allowContentAccess = false
            allowFileAccess = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                return !(u.startsWith("file:///android_asset/") || u.startsWith(UiUpdater.cachedPrefix(this@MainActivity)))
            }
        }
        web.addJavascriptInterface(Bridge(this), "Android")
        setContentView(web)
        web.loadUrl(UiUpdater.currentUrl(this))
        checkUiUpdate()

        if (Prefs(this).token != null) {
            SyncWorker.schedule(this)
            ServiceStarter.start(this)
        }

        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_CALL_LOG)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_PHONE_STATE)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.READ_CONTACTS)
        // call recordings (on by default): "Music and audio" on Android 13+, storage before that
        if (Prefs(this).recEnabled && checkSelfPermission(DeviceInfo.audioPermission()) != PackageManager.PERMISSION_GRANTED)
            need.add(DeviceInfo.audioPermission())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun checkUiUpdate() {
        Thread {
            if (UiUpdater.check(this)) {
                runOnUiThread { web.loadUrl(UiUpdater.currentUrl(this)) }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        js("window.onResume && window.onResume()")
        checkUiUpdate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ServiceStarter.start(this)
        js("window.onResume && window.onResume()")
    }
}
