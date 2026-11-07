package com.marsa.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentUri: Uri? = null

    private var exportPage = 0
    private var exportPng = true

    private lateinit var documentView: PdfDocumentView
    private lateinit var fileNameView: TextView
    private lateinit var emptyView: TextView

    private val bg = Color.rgb(14, 19, 30)
    private val surface = Color.rgb(27, 35, 50)
    private val accent = Color.rgb(67, 133, 255)
    private val textColor = Color.rgb(242, 245, 252)

    companion object {
        private const val PICK_PDF = 1001
        private const val CREATE_IMAGE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildInterface()

        // Jika activity di-recreate, ambil URI dari savedInstanceState
        val restoredUri = savedInstanceState?.getString("current_uri")
        if (restoredUri != null) {
            openPdf(Uri.parse(restoredUri))
        } else {
            handleIncomingPdf(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString("current_uri", it.toString()) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ============================================================
    // UI
    // ============================================================

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ---------- HEADER ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            setBackgroundColor(bg)
        }

        val logo = TextView(this).apply {
            text = "M"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(11).toFloat()
            }
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))

        fileNameView = TextView(this).apply {
            text = "MARSA PDF Reader"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(
            fileNameView,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        )

        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setOnClickListener { showMenu(it) }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(42), dp(48)))

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60)
            )
        )

        // ---------- VIEWER (FrameLayout, agar emptyView bisa overlay) ----------
        val viewer = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(35, 42, 55))
        }

        documentView = PdfDocumentView(this)
        viewer.addView(
            documentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        emptyView = TextView(this).apply {
            text = "MARSA PDF Reader\n\nBuka dokumen PDF untuk mulai membaca."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        viewer.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        root.addView(
            viewer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    // ============================================================
    // MENU
    // ============================================================

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)

        popup.menu.add("Buka PDF").setOnMenuItemClickListener {
            choosePdf()
            true
        }

        popup.menu.add("Bagikan PDF").setOnMenuItemClickListener {
            sharePdf()
            true
        }

        popup.menu.add("Simpan halaman sebagai gambar").setOnMenuItemClickListener {
            showExportDialog()
            true
        }

        popup.menu.add("Cetak PDF").setOnMenuItemClickListener {
            startPrintFlow()
            true
        }

        popup.menu.add("Fit to Screen").setOnMenuItemClickListener {
            documentView.fitToScreen()
            true
        }

        popup.menu.add("Informasi aplikasi").setOnMenuItemClickListener {
            AlertDialog.Builder(this)
                .setTitle("MARSA PDF Reader")
                .setMessage(
                    "Pembaca PDF ringan dan offline.\n\n" +
                    "Dikembangkan untuk MARSA.\n" +
                    "Android 8.0 ke atas."
                )
                .setPositiveButton("Tutup", null)
                .show()
            true
        }

        popup.show()
    }

    // ============================================================
    // FILE PICKER
    // ============================================================

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, PICK_PDF)
    }

    @Deprecated("Use Activity Result API in a future update")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_PDF &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            openPdf(data.data!!)
        }

        if (requestCode == CREATE_IMAGE &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            saveImage(data.data!!)
        }
    }

    // ============================================================
    // EXPORT HALAMAN KE GAMBAR (PNG / JPG)
    // ============================================================

    private fun showExportDialog() {
        if (currentUri == null) {
            Toast.makeText(this, "Belum ada PDF yang dibuka.", Toast.LENGTH_SHORT).show()
            return
        }

        val page = documentView.currentPageIndex()

        AlertDialog.Builder(this)
            .setTitle("Simpan halaman ${page + 1} sebagai")
            .setItems(arrayOf("PNG (tajam)", "JPG (lebih kecil)")) { _, which ->
                startExport(page, which == 0)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun startExport(page: Int, png: Boolean) {
        exportPage = page
        exportPng = png

        val ext = if (png) "png" else "jpg"
        val baseName = fileNameView.text.toString().removeSuffix(".pdf")

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (png) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_TITLE, "$baseName-hal${page + 1}.$ext")
        }
        startActivityForResult(intent, CREATE_IMAGE)
    }

    private fun saveImage(uri: Uri) {
        val page = exportPage
        val format =
            if (exportPng) Bitmap.CompressFormat.PNG
            else Bitmap.CompressFormat.JPEG

        // Render + tulis file di thread background agar UI tidak macet.
        Thread {
            var ok = false

            try {
                val bmp = documentView.renderPageForExport(page, 2000)

                if (bmp != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            ok = bmp.compress(format, 95, out)
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Gagal menyimpan gambar", e)
            } catch (e: OutOfMemoryError) {
                Log.e("MainActivity", "Memori habis saat export gambar", e)
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Gambar tersimpan" else "Gagal menyimpan gambar",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    // ============================================================
    // CETAK KE SERVER (GURU)
    // ============================================================

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun startPrintFlow() {
        val uri = currentUri ?: run {
            toast("Belum ada PDF yang dibuka.")
            return
        }

        val size = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }

        if (size > 20L * 1024 * 1024) {
            toast("Ukuran PDF maksimal 20 MB.")
            return
        }

        toast("Memuat printer...")

        Thread {
            try {
                val printers = PrintClient.fetchPrinters()
                runOnUiThread { showPrintDialog(uri, printers) }
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal memuat printer: ${e.message}") }
            }
        }.start()
    }

    private fun showPrintDialog(uri: Uri, printers: List<PrintPrinter>) {
        if (printers.isEmpty()) {
            toast("Tidak ada printer yang online.")
            return
        }

        val prefs = getSharedPreferences("print", MODE_PRIVATE)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                printers.map {
                    if (it.location.isBlank()) it.name else "${it.name} - ${it.location}"
                }
            )
        }

        val pinInput = EditText(this).apply {
            hint = "PIN (5 digit)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(5))
            setText(prefs.getString("pin", ""))
        }

        val copiesInput = EditText(this).apply {
            hint = "Jumlah salinan (1-10)"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2))
            setText("1")
        }

        box.addView(spinner)
        box.addView(pinInput)
        box.addView(copiesInput)

        AlertDialog.Builder(this)
            .setTitle("Cetak PDF")
            .setView(box)
            .setPositiveButton("Cetak") { _, _ ->
                val pin = pinInput.text.toString().trim()
                val copies = copiesInput.text.toString().toIntOrNull() ?: 0

                if (!Regex("^[0-9]{5}$").matches(pin)) {
                    toast("PIN harus 5 digit angka.")
                    return@setPositiveButton
                }

                if (copies !in 1..10) {
                    toast("Jumlah salinan harus 1-10.")
                    return@setPositiveButton
                }

                submitPrint(
                    uri,
                    printers[spinner.selectedItemPosition].id,
                    pin,
                    copies
                )
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun submitPrint(uri: Uri, printerId: Int, pin: String, copies: Int) {
        toast("Mengirim dokumen...")

        Thread {
            val result = try {
                PrintClient.submit(this, uri, pin, printerId, copies)
            } catch (e: Exception) {
                PrintResult(false, "Gagal mengirim: ${e.message}")
            }

            if (!result.ok) {
                runOnUiThread { toast(result.message) }
                return@Thread
            }

            // PIN hanya disimpan setelah server menerimanya.
            getSharedPreferences("print", MODE_PRIVATE)
                .edit().putString("pin", pin).apply()

            runOnUiThread { toast("Masuk antrean: ${result.printerName}") }

            val token = result.statusToken
            if (token != null) pollPrintStatus(token)
        }.start()
    }

    // Berjalan di thread background: cek status tiap 3 detik, maksimal 90 detik.
    private fun pollPrintStatus(token: String) {
        val inProgress = setOf("pending", "processing", "printing")
        var last = "pending"

        repeat(30) {
            try {
                val st = PrintClient.fetchStatus(token)

                if (st.status != last) {
                    last = st.status
                    runOnUiThread { toast("Status cetak: ${st.status}") }
                }

                if (st.status !in inProgress) {
                    if (st.error != null) {
                        runOnUiThread { toast("Gagal: ${st.error}") }
                    }
                    return
                }
            } catch (e: Exception) {
                // abaikan, coba lagi pada putaran berikut
            }

            Thread.sleep(3000)
        }
    }

    // ============================================================
    // INCOMING INTENT (VIEW / SEND)
    // ============================================================

    private fun handleIncomingPdf(source: Intent?) {
        if (source == null) return

        val uri: Uri? = when (source.action) {
            Intent.ACTION_VIEW -> source.data

            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }

            else -> null
        }

        if (uri != null) {
            openPdf(uri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPdf(intent)
    }

    // ============================================================
    // OPEN / CLOSE PDF
    // ============================================================

    private fun getDisplayName(uri: Uri): String {
        var name = "Dokumen PDF"
        try {
            val cursor: Cursor? = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index) ?: name
                    }
                }
            }
        } catch (_: Exception) {
            // fallback ke nama default
        }
        return name
    }

    private fun grantReadPermissionIfPossible(uri: Uri) {
        if (uri.scheme != "content") {
            return
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "Tidak dapat menyimpan permission URI PDF", e)
        }
    }

    private fun openPdf(uri: Uri) {
        try {
            closePdf()

            // ACTION_VIEW / ACTION_SEND tidak selalu punya persistable permission.
            // Hanya content:// URI yang bisa dipersisten; file:// dan uri non-persistable
            // harus diabaikan agar aplikasi tidak crash.
            grantReadPermissionIfPossible(uri)

            val fd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw Exception("File tidak dapat dibuka.")

            val pdf: PdfRenderer
            try {
                pdf = PdfRenderer(fd)
            } catch (e: Exception) {
                try { fd.close() } catch (_: Exception) {}
                throw Exception("PDF tidak valid atau rusak", e)
            }

            if (pdf.pageCount == 0) {
                try { pdf.close() } catch (_: Exception) {}
                try { fd.close() } catch (_: Exception) {}
                throw Exception("PDF tidak memiliki halaman")
            }

            descriptor = fd
            renderer = pdf
            currentUri = uri

            documentView.setRenderer(pdf)
            emptyView.visibility = View.GONE
            fileNameView.text = getDisplayName(uri)

            Toast.makeText(this, "PDF berhasil dibuka", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            closePdf()
            Toast.makeText(
                this,
                "Gagal membuka PDF: ${e.message ?: "unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun closePdf() {
        // Lepas renderer dari view DULU supaya onDraw tidak memakai renderer yang ditutup.
        if (::documentView.isInitialized) {
            documentView.setRenderer(null)
        }

        try { renderer?.close() } catch (_: Exception) {}
        try { descriptor?.close() } catch (_: Exception) {}

        renderer = null
        descriptor = null
        currentUri = null

        if (::fileNameView.isInitialized) {
            fileNameView.text = "MARSA PDF Reader"
        }
        if (::emptyView.isInitialized) {
            emptyView.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // SHARE
    // ============================================================

    private fun sharePdf() {
        val uri = currentUri
        if (uri == null) {
            Toast.makeText(
                this,
                "Belum ada PDF yang dibuka.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Bagikan dokumen PDF"))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Gagal membagikan: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onDestroy() {
        closePdf()
        if (::documentView.isInitialized) {
            documentView.release()
        }
        super.onDestroy()
    }
}
