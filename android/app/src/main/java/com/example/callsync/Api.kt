package com.example.callsync

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpError(val code: Int, body: String) : IOException("HTTP $code: $body")

object Api {
    fun post(url: String, body: JSONObject, token: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("X-Api-Token", token)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return readResponse(c)
        } finally {
            c.disconnect()
        }
    }

    /** multipart/form-data upload of one file plus text fields. */
    fun upload(url: String, token: String, fields: Map<String, String>, file: File, fileName: String, mime: String): JSONObject {
        val boundary = "----CallSync" + System.currentTimeMillis()
        val head = StringBuilder()
        for ((k, v) in fields) {
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                .append(v).append("\r\n")
        }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(safeName).append("\"\r\n")
            .append("Content-Type: ").append(mime).append("\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setRequestProperty("X-Api-Token", token)
            c.setFixedLengthStreamingMode(headBytes.size.toLong() + file.length() + tailBytes.size)
            c.outputStream.use { os ->
                os.write(headBytes)
                file.inputStream().use { it.copyTo(os, 64 * 1024) }
                os.write(tailBytes)
            }
            return readResponse(c)
        } finally {
            c.disconnect()
        }
    }

    private fun readResponse(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code, text.take(300))
        return try { JSONObject(text) } catch (e: Exception) { throw IOException("Bad server reply: ${text.take(120)}") }
    }
}
