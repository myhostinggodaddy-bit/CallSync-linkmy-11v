package com.example.callsync

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Attendance flow:
 * QR scan → server QR check (instant) → face check → GPS → submit → "Welcome to Dreandesk".
 */
class AttendanceActivity : ComponentActivity() {

    private enum class Step { QR, FACE, LOCATION, DONE }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BG = Color.parseColor("#0E0E15")
    private val MUTED = Color.parseColor("#9A9AB2")
    private val GREEN = Color.parseColor("#22C55E")
    private val RED = Color.parseColor("#F87171")
    private val ACCENT = Color.parseColor("#F97316")

    private lateinit var prefs: Prefs
    private lateinit var camera: PreviewView
    private lateinit var hint: TextView
    private lateinit var panel: LinearLayout
    private lateinit var root: FrameLayout

    private var step = Step.QR
    private var qrPayload: String? = null
    private var faceSig: JSONArray? = null
    private var stable = 0
    private var busy = false
    private var checkingQr = false
    private var lastQrFailAt = 0L
    private var provider: ProcessCameraProvider? = null
    private val analysisExec = Executors.newSingleThreadExecutor()

    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.token == null) { finish(); return }
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        if (hasPerm(Manifest.permission.CAMERA)) startStep()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 77)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun baseUrl(): String = (prefs.baseUrl ?: Config.SERVER_URL).trimEnd('/')

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "Attendance"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        camera = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        col.addView(camera, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(12) })

        hint = TextView(this).apply {
            textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER
        }
        col.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(panel, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startStep() {
        panel.removeAllViews()
        hint.setTextColor(MUTED)
        camera.visibility = View.VISIBLE
        when (step) {
            Step.QR -> {
                hint.text = "Point the camera at today's attendance QR code"
                bindCamera(CameraSelector.DEFAULT_BACK_CAMERA) { scanQr(it) }
            }
            Step.FACE -> {
                hint.text = "Look at the front camera. Open your eyes and hold still."
                bindCamera(CameraSelector.DEFAULT_FRONT_CAMERA) { detectFace(it) }
            }
            Step.LOCATION -> {
                hint.text = "Getting your location..."
                provider?.unbindAll()
                camera.visibility = View.GONE
                fetchLocation()
            }
            Step.DONE -> Unit
        }
    }

    private fun bindCamera(selector: CameraSelector, analyzer: (ImageProxy) -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(camera.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExec, analyzer)
            p.unbindAll()
            p.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun scanQr(proxy: ImageProxy) {
        val media = proxy.image
        if (busy || checkingQr || step != Step.QR || media == null) { proxy.close(); return }
        busy = true
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(img)
            .addOnSuccessListener { codes ->
                val raw = codes.firstOrNull()?.rawValue
                // একই ভুল QR বারবার server-এ না যাক: ফেল হলে ৩ সেকেন্ড অপেক্ষা
                val cooledDown = System.currentTimeMillis() - lastQrFailAt > 3000
                if (raw != null && raw.startsWith("CSATT:") && !checkingQr && cooledDown) {
                    checkingQr = true
                    checkQr(raw)
                }
            }
            .addOnCompleteListener { busy = false; proxy.close() }
    }

    /** Scan-এর সাথেই server যাচাই করে। ঠিক হলে face check-এ যায়, ভুল হলে কারণ দেখায়। */
    private fun checkQr(raw: String) {
        runOnUiThread { hint.setTextColor(MUTED); hint.text = "Checking QR..." }
        Thread {
            try {
                Api.post("${baseUrl()}/attendance_check_qr.php", JSONObject().put("qr", raw), prefs.token)
                qrPayload = raw
                runOnUiThread {
                    checkingQr = false
                    step = Step.FACE
                    startStep()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    checkingQr = false
                    lastQrFailAt = System.currentTimeMillis()
                    hint.setTextColor(RED)
                    hint.text = serverMessage(e)
                }
            }
        }.start()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun detectFace(proxy: ImageProxy) {
        val media = proxy.image
        if (busy || step != Step.FACE || media == null) { proxy.close(); return }
        busy = true
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        faceDetector.process(img)
            .addOnSuccessListener { faces ->
                if (step != Step.FACE) return@addOnSuccessListener
                if (faces.size != 1) {
                    stable = 0
                    hint.text = if (faces.isEmpty()) "Look at the front camera" else "Only one face, please"
                    return@addOnSuccessListener
                }
                val f = faces[0]
                val open = (f.leftEyeOpenProbability ?: 0f) > 0.7f && (f.rightEyeOpenProbability ?: 0f) > 0.7f
                if (!open) {
                    stable = 0
                    hint.text = "Open your eyes and look straight"
                    return@addOnSuccessListener
                }
                stable++
                if (stable >= 4) {
                    val sig = FaceSig.of(f)
                    if (sig == null) {
                        stable = 0
                        hint.text = "Hold still and come closer"
                    } else {
                        faceSig = sig
                        step = Step.LOCATION
                        runOnUiThread { startStep() }
                    }
                }
            }
            .addOnCompleteListener { busy = false; proxy.close() }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 78)
            return
        }
        val cts = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc == null) fail("GPS location not found. Turn on location and try again.")
                else submit(loc.latitude, loc.longitude, loc.accuracy.toDouble())
            }
            .addOnFailureListener { fail("Location error: ${it.message}") }
    }

    private fun submit(lat: Double, lng: Double, acc: Double) {
        hint.setTextColor(MUTED)
        hint.text = "Submitting attendance..."
        Thread {
            try {
                val body = JSONObject()
                    .put("qr", qrPayload)
                    .put("lat", lat)
                    .put("lng", lng)
                    .put("accuracy", acc)
                    .put("face", faceSig)
                val res = Api.post("${baseUrl()}/attendance_submit.php", body, prefs.token)
                AttendanceStore.save(
                    this,
                    res.optString("check_in_ist").ifEmpty { null },
                    res.optString("check_out_ist").ifEmpty { null }
                )
                runOnUiThread { showDone(res) }
            } catch (e: Exception) {
                runOnUiThread { fail(serverMessage(e)) }
            }
        }.start()
    }

    /** Server-এর error বার্তা বের করে। */
    private fun serverMessage(e: Exception): String {
        val body = e.message?.substringAfter(": ", "") ?: ""
        return try {
            JSONObject(body).optString("error").ifEmpty { "Could not submit attendance." }
        } catch (x: Exception) {
            "Could not connect: ${e.message ?: ""}"
        }
    }

    private fun showDone(res: JSONObject) {
        step = Step.DONE
        provider?.unbindAll()
        camera.visibility = View.GONE
        hint.text = ""
        panel.removeAllViews()

        val action = res.optString("action")
        val time = res.optString("time_ist")
        val label = when (action) {
            "in" -> "Check-in saved at $time"
            "out" -> "Check-out saved at $time"
            else -> res.optString("message")
        }
        panel.addView(TextView(this).apply {
            text = "Welcome to Dreandesk"; textSize = 26f; setTextColor(GREEN)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        panel.addView(TextView(this).apply {
            text = label; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        if (res.optBoolean("enrolled")) {
            panel.addView(TextView(this).apply {
                text = "Your face is saved for next time."; textSize = 13f; setTextColor(MUTED); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        panel.addView(Button(this).apply {
            text = "Done"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
    }

    /** ভুল হলে বার্তা দেখায় এবং আবার শুরু করার বোতাম দেয়। */
    private fun fail(msg: String) {
        busy = false
        hint.setTextColor(RED)
        hint.text = msg
        panel.removeAllViews()
        panel.addView(Button(this).apply {
            text = "Try again"
            setBackgroundColor(ACCENT)
            setTextColor(Color.WHITE)
            setOnClickListener { restart() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    private fun restart() {
        step = Step.QR
        qrPayload = null
        faceSig = null
        stable = 0
        checkingQr = false
        lastQrFailAt = 0L
        startStep()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            77 -> if (hasPerm(Manifest.permission.CAMERA)) startStep()
                  else fail("Camera permission is needed for attendance.")
            78 -> if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) fetchLocation()
                  else fail("Location permission is needed for attendance.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        provider?.unbindAll()
        analysisExec.shutdown()
    }
}
