package com.marsa.pdfreader

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PrintPrinter(val id: Int, val name: String, val location: String)

data class PrintResult(
    val ok: Boolean,
    val message: String,
    val statusToken: String? = null,
    val printerName: String? = null
)

data class PrintStatus(val status: String, val error: String?)

// Klien API cetak guru. Semua fungsi memblokir: panggil dari thread background.
object PrintClient {

    // GANTI dengan domain server print Anda (wajib https).
    private const val BASE_URL = "https://chat.marsa9.com/printserver-ci4/public"

    private fun open(path: String, method: String): HttpURLConnection {
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val stream =
            if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun parseJson(body: String): JSONObject {
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw Exception("Respons server tidak valid.")
        }
    }

    fun fetchPrinters(): List<PrintPrinter> {
        val c = open("/api/teacher/printers", "GET")
        try {
            val json = parseJson(readBody(c))

            if (!json.optBoolean("ok")) {
                throw Exception(json.optString("error", "Gagal memuat printer."))
            }

            val arr = json.getJSONArray("printers")

            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PrintPrinter(
                    o.getInt("id"),
                    o.optString("nama_printer"),
                    o.optString("lokasi")
                )
            }
        } finally {
            c.disconnect()
        }
    }

    // PDF di-stream langsung dari Uri (tidak dimuat penuh ke memori).
    fun submit(
        context: Context,
        uri: Uri,
        pin: String,
        printerId: Int,
        copies: Int
    ): PrintResult {

        val boundary = "----marsa" + UUID.randomUUID().toString().replace("-", "")
        val c = open("/api/teacher/jobs", "POST")

        try {
            c.doOutput = true
            c.setChunkedStreamingMode(0)
            c.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            DataOutputStream(c.outputStream.buffered()).use { out ->

                fun field(name: String, value: String) {
                    out.writeBytes(
                        "--$boundary\r\n" +
                            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                            "$value\r\n"
                    )
                }

                field("pin", pin)
                field("printer_id", printerId.toString())
                field("jumlah_copy", copies.toString())

                out.writeBytes(
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"pdf\"; filename=\"dokumen.pdf\"\r\n" +
                        "Content-Type: application/pdf\r\n\r\n"
                )

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("File tidak dapat dibaca.")

                input.use { it.copyTo(out) }

                out.writeBytes("\r\n--$boundary--\r\n")
            }

            val code = c.responseCode
            val json = parseJson(readBody(c))

            if (code == 201 && json.optBoolean("ok")) {

                val job = json.getJSONObject("job")

                // Token diambil dari ujung status_url, URL dibuat sendiri di fetchStatus.
                val token = job.optString("status_url").substringAfterLast('/')

                if (!Regex("^[a-f0-9]{64}$").matches(token)) {
                    return PrintResult(true, "Masuk antrean cetak.", null, job.optString("printer_name"))
                }

                return PrintResult(
                    true,
                    json.optString("message"),
                    token,
                    job.optString("printer_name")
                )
            }

            return PrintResult(
                false,
                json.optString("error", "Gagal mengirim (kode $code).")
            )

        } finally {
            c.disconnect()
        }
    }

    fun fetchStatus(token: String): PrintStatus {
        val c = open("/api/teacher/jobs/status/$token", "GET")
        try {
            val json = parseJson(readBody(c))

            if (!json.optBoolean("ok")) {
                throw Exception(json.optString("error", "Job tidak ditemukan."))
            }

            val job = json.getJSONObject("job")

            val error =
                if (job.isNull("error_message")) null
                else job.getString("error_message").takeIf { it.isNotBlank() }

            return PrintStatus(job.optString("status"), error)
        } finally {
            c.disconnect()
        }
    }
}
