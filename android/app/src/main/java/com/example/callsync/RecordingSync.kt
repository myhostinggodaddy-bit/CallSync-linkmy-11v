package com.example.callsync

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlin.math.abs

/**
 * Android does not let normal apps record calls. Many phones (Samsung, Xiaomi, Realme, OnePlus, Vivo…)
 * have a built-in call recorder that saves an audio file. This finds the file that belongs to each
 * connected call and uploads it. Needs the "Music and audio" (READ_MEDIA_AUDIO) permission.
 */
object RecordingSync {

    private const val MAX_PER_RUN = 3
    private const val GIVE_UP_AFTER_MS = 6 * 3600 * 1000L
    private val SKIP_FOLDERS = listOf("whatsapp", "telegram", "voice notes", "ringtone", "notification", "alarm")

    data class Audio(val id: Long, val name: String, val path: String, val modSec: Long, val durMs: Long, val size: Long)

    fun run(ctx: Context, base: String, token: String): String? {
        val p = Prefs(ctx)
        if (!p.recEnabled) { p.recLast = "Turned off in Settings"; return null }
        if (!p.recAllowed) { p.recLast = "Your company has turned recording upload off"; return null }
        if (ctx.checkSelfPermission(DeviceInfo.audioPermission()) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            p.recLast = "Permission \"Music and audio\" is not allowed"
            return "Recording permission is missing"
        }
        if (p.recWifiOnly && !DeviceInfo.isWifi(ctx)) { p.recLast = "Waiting for Wi-Fi (Only on Wi-Fi is on)"; return null }

        val now = System.currentTimeMillis()
        val done = p.recDone()
        val doneSet = done.map { it.substring(2) }.toHashSet()
        val calls = CallReader.read(ctx, now - 3 * 24 * 3600 * 1000L).logs
        var uploaded = 0
        var checked = 0
        var notFound = 0

        for (i in calls.length() - 1 downTo 0) {
            if (uploaded >= MAX_PER_RUN) break
            val c = calls.getJSONObject(i)
            val id = c.getString("device_call_id")
            val dur = c.getInt("duration_sec")
            if (dur <= 0 || id in doneSet) continue
            val start = c.getLong("date_ms")
            val end = c.getLong("end_ms")
            if (now - end < 60_000) continue // give the recorder time to save the file

            checked++
            val audio = find(ctx, start, end, dur, c.optString("contact_number"))
            if (audio == null) {
                notFound++
                if (now - end > GIVE_UP_AFTER_MS) { done.add("x:$id"); doneSet.add(id) }
                continue
            }
            if (audio.size > RECORDING_MAX_BYTES) { done.add("x:$id"); doneSet.add(id); continue }

            val tmp = File(ctx.cacheDir, "rec_upload.tmp")
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audio.id)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: continue
                Api.upload("$base/upload_recording.php", token, mapOf("device_call_id" to id),
                    tmp, audio.name, mimeOf(audio.name))
                done.add("u:$id"); doneSet.add(id)
                uploaded++
                p.recUploadedCount = p.recUploadedCount + 1
            } catch (e: HttpError) {
                when (e.code) {
                    403 -> { p.recAllowed = false; p.setRecDone(done); return "Company turned recording upload off" }
                    404 -> { } // call not on the server yet, try next time
                    413, 415 -> { done.add("x:$id"); doneSet.add(id) }
                    else -> { p.setRecDone(done); return "Recording upload failed (${e.code})" }
                }
            } catch (e: Exception) {
                p.setRecDone(done)
                return "Recording upload failed: ${e.message ?: "network"}"
            } finally {
                tmp.delete()
            }
        }
        p.setRecDone(done)
        p.recLast = when {
            uploaded > 0 -> "$uploaded recording(s) uploaded just now"
            checked == 0 -> "No new connected calls to check"
            else -> "No recording file found for $notFound call(s). Is auto call recording on in your phone's dialer?"
        }
        return if (uploaded > 0) "$uploaded recording(s) uploaded" else null
    }

    private const val RECORDING_MAX_BYTES = 30L * 1024 * 1024

    /** The audio file saved between the call's start and a few minutes after its end that looks like a call recording. */
    private fun find(ctx: Context, startMs: Long, endMs: Long, durSec: Int, number: String): Audio? {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE
        )
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        cols.add(pathCol)
        val from = startMs / 1000 - 30
        val to = endMs / 1000 + 600
        val found = mutableListOf<Audio>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
                "${MediaStore.Audio.Media.DATE_MODIFIED} >= ? AND ${MediaStore.Audio.Media.DATE_MODIFIED} <= ?",
                arrayOf(from.toString(), to.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    found.add(Audio(c.getLong(0), c.getString(1) ?: "rec", c.getString(5) ?: "",
                        c.getLong(2), c.getLong(3), c.getLong(4)))
                }
            }
        } catch (e: Exception) { return null }

        val digits = number.filter { it.isDigit() }.takeLast(7)
        var best: Audio? = null
        var bestScore = Long.MAX_VALUE
        for (a in found) {
            val where = (a.path + "/" + a.name).lowercase()
            if (SKIP_FOLDERS.any { it in where }) continue
            val nameHasNumber = digits.length >= 7 && a.name.filter { it.isDigit() }.contains(digits)
            val looksLikeCall = "call" in where || "record" in where || "rec" in where || nameHasNumber
            if (!looksLikeCall) continue
            // the recording must be about as long as the conversation
            if (a.durMs > 0) {
                val diff = abs(a.durMs / 1000 - durSec)
                if (diff > maxOf(15L, durSec / 4L)) continue
            }
            var score = abs(a.modSec - endMs / 1000)
            if (!nameHasNumber) score += 30
            if (score < bestScore) { bestScore = score; best = a }
        }
        return best
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "amr" -> "audio/amr"
        "awb" -> "audio/amr-wb"
        "aac" -> "audio/aac"
        "3gp", "3gpp" -> "audio/3gpp"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /**
     * For Settings → "Check recordings": what the phone has, without uploading anything.
     * {perm, enabled, wifiOnly, onWifi, allowed, uploaded, last, total, callLike, files:[{name, folder, ago, dur, call}]}
     */
    fun diagnose(ctx: Context): org.json.JSONObject {
        val p = Prefs(ctx)
        val perm = ctx.checkSelfPermission(DeviceInfo.audioPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val out = org.json.JSONObject()
            .put("perm", perm).put("enabled", p.recEnabled).put("wifiOnly", p.recWifiOnly)
            .put("onWifi", DeviceInfo.isWifi(ctx)).put("allowed", p.recAllowed)
            .put("uploaded", p.recUploadedCount).put("last", p.recLast)
            .put("android", Build.VERSION.SDK_INT)
        val files = org.json.JSONArray()
        var total = 0
        var callLike = 0
        if (perm) {
            val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
            val since = System.currentTimeMillis() / 1000 - 3 * 24 * 3600
            try {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION, pathCol),
                    "${MediaStore.Audio.Media.DATE_MODIFIED} >= ?", arrayOf(since.toString()),
                    "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val now = System.currentTimeMillis() / 1000
                    while (c.moveToNext()) {
                        total++
                        val name = c.getString(0) ?: ""
                        val path = c.getString(3) ?: ""
                        val where = (path + "/" + name).lowercase()
                        val isCall = SKIP_FOLDERS.none { it in where } &&
                            ("call" in where || "record" in where || "rec" in where)
                        if (isCall) callLike++
                        if (files.length() < 15) {
                            val folder = if (Build.VERSION.SDK_INT >= 29) path.trimEnd('/') else path.substringBeforeLast('/').takeLast(40)
                            val mins = (now - c.getLong(1)) / 60
                            files.put(org.json.JSONObject().put("name", name.take(60)).put("folder", folder)
                                .put("ago", if (mins < 60) "${mins}m ago" else if (mins < 1440) "${mins / 60}h ago" else "${mins / 1440}d ago")
                                .put("dur", c.getLong(2) / 1000).put("call", isCall))
                        }
                    }
                }
            } catch (e: Exception) {
                out.put("error", e.message ?: "Could not read audio files")
            }
        }
        return out.put("total", total).put("callLike", callLike).put("files", files)
    }
}
