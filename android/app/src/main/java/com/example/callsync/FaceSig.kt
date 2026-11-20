package com.example.callsync

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONArray
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * মুখের ১০টা landmark-এর মধ্যে ৪৫টা দূরত্ব, চোখের দূরত্ব দিয়ে normalise করা।
 * এটা মুখের জ্যামিতিক ছাপ (signature). server এই ছাপ মিলায়।
 */
object FaceSig {
    private val POINTS = intArrayOf(
        FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE,
        FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_BOTTOM,
        FaceLandmark.LEFT_EAR, FaceLandmark.RIGHT_EAR, FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK
    )

    fun of(face: Face): JSONArray? {
        val pts = ArrayList<PointF>()
        for (t in POINTS) pts.add(face.getLandmark(t)?.position ?: return null)
        val eye = dist(pts[0], pts[1])
        if (eye < 8f) return null
        val out = JSONArray()
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                val v = (dist(pts[i], pts[j]) / eye * 10000f).roundToInt() / 10000.0
                out.put(v)
            }
        }
        return out
    }

    private fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
