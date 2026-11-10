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
import android.text.method.DigitsKeyListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentUri: Uri? = null

    private var exportPage = 0
    private var exportPng = true

    private lateinit var documentView: PdfDocumentView
    private lateinit var fileNameView: TextView
    private lateinit var emptyView: View
    private lateinit var homeList: LinearLayout
    private lateinit var pageView: TextView

    private var nightMode = false

    private var printDialog: AlertDialog? = null
    private var printMessage: TextView? = null
    private var printBar: ProgressBar? = null

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

        nightMode = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("night", false)
        documentView.setNightMode(nightMode)
        refreshHome()

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

        pageView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { showJumpDialog() }
        }
        header.addView(
            pageView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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

        documentView.onPageChanged = { page, total ->
            pageView.text = "${page + 1} / $total"
            pageView.visibility = View.VISIBLE
        }

        homeList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        emptyView = ScrollView(this).apply {
            addView(homeList)
        }

        viewer.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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

        popup.menu.add("Lompat ke halaman").setOnMenuItemClickListener {
            showJumpDialog()
            true
        }

        popup.menu.add(if (nightMode) "Mode terang" else "Mode malam")
            .setOnMenuItemClickListener {
                toggleNightMode()
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

        popup.menu.add("Tutup PDF (ke beranda)").setOnMenuItemClickListener {
            closePdf()
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

    private fun startPrintFlow(target: Uri? = currentUri) {
        val uri = target ?: run {
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
            toast("Belum ada printer terdaftar.")
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
                    val label =
                        if (it.location.isBlank()) it.name
                        else "${it.name} - ${it.location}"
                    if (it.online) "\u25CF $label" else "\u25CB $label (offline)"
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

        val rangeInput = EditText(this).apply {
            hint = "Halaman (kosong = semua), mis. 1-3,5"
            inputType = InputType.TYPE_CLASS_TEXT
            keyListener = DigitsKeyListener.getInstance("0123456789,-")
            filters = arrayOf(InputFilter.LengthFilter(50))
        }

        box.addView(spinner)
        box.addView(pinInput)
        box.addView(copiesInput)
        box.addView(rangeInput)

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

                val range = rangeInput.text.toString().trim()

                if (range.isNotEmpty() &&
                    !Regex("^[1-9][0-9]*(-[1-9][0-9]*)?(,[1-9][0-9]*(-[1-9][0-9]*)?)*$").matches(range)
                ) {
                    toast("Format halaman salah. Contoh: 1-3,5")
                    return@setPositiveButton
                }

                val printer = printers[spinner.selectedItemPosition]

                if (!printer.online) {
                    toast("Printer sedang offline. Pilih printer lain.")
                    return@setPositiveButton
                }

                submitPrint(uri, printer.id, pin, copies, range)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showPrintProgress(text: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }

        val bar = ProgressBar(this)
        val msg = TextView(this).apply {
            this.text = text
            textSize = 15f
        }

        box.addView(bar, LinearLayout.LayoutParams(dp(36), dp(36)))
        box.addView(
            msg,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
        )

        printBar = bar
        printMessage = msg
        printDialog = AlertDialog.Builder(this)
            .setTitle("Cetak")
            .setView(box)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun updatePrintProgress(text: String, finished: Boolean) {
        runOnUiThread {
            printMessage?.text = text
            if (finished) printBar?.visibility = View.GONE
        }
    }

    private fun submitPrint(
        uri: Uri,
        printerId: Int,
        pin: String,
        copies: Int,
        pageRange: String
    ) {
        showPrintProgress("Mengirim dokumen...")

        Thread {
            val result = try {
                PrintClient.submit(this, uri, pin, printerId, copies, pageRange)
            } catch (e: Exception) {
                PrintResult(false, "Gagal mengirim: ${e.message}")
            }

            if (!result.ok) {
                updatePrintProgress(result.message, true)
                return@Thread
            }

            // PIN hanya disimpan setelah server menerimanya.
            getSharedPreferences("print", MODE_PRIVATE)
                .edit().putString("pin", pin).apply()

            updatePrintProgress("Masuk antrean: ${result.printerName}", false)

            val token = result.statusToken
            if (token == null) {
                updatePrintProgress("Masuk antrean: ${result.printerName}", true)
                return@Thread
            }

            pollPrintStatus(token)
        }.start()
    }

    // Berjalan di thread background: cek status tiap 3 detik, maksimal 90 detik.
    private fun pollPrintStatus(token: String) {
        val inProgress = setOf("pending", "printing")

        repeat(30) {
            try {
                val st = PrintClient.fetchStatus(token)

                if (st.status in inProgress) {
                    val label =
                        if (st.status == "pending") "Menunggu di antrean..."
                        else "Sedang mencetak..."
                    updatePrintProgress(label, false)
                } else {
                    val label =
                        if (st.status == "done") "Selesai dicetak"
                        else "Gagal: ${st.error ?: "printer tidak merespons"}"
                    updatePrintProgress(label, true)
                    return
                }
            } catch (e: Exception) {
                // abaikan, coba lagi pada putaran berikut
            }

            Thread.sleep(3000)
        }

        updatePrintProgress("Masih diproses server. Cek printer sebentar lagi.", true)
    }

    // ============================================================
    // LOMPAT HALAMAN, MODE MALAM
    // ============================================================

    private fun showJumpDialog() {
        val total = documentView.pageCount()
        if (currentUri == null || total == 0) return

        val input = EditText(this).apply {
            hint = "1 - $total"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
        }

        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(this)
            .setTitle("Lompat ke halaman")
            .setView(box)
            .setPositiveButton("Pergi") { _, _ ->
                val n = input.text.toString().toIntOrNull()

                if (n == null || n !in 1..total) {
                    toast("Masukkan angka 1-$total.")
                    return@setPositiveButton
                }

                documentView.goToPage(n - 1)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toggleNightMode() {
        nightMode = !nightMode

        getSharedPreferences("settings", MODE_PRIVATE)
            .edit().putBoolean("night", nightMode).apply()

        documentView.setNightMode(nightMode)
    }

    // ============================================================
    // BERANDA: DAFTAR PDF TERAKHIR
    // ============================================================

    private fun refreshHome() {
        homeList.removeAllViews()

        homeList.addView(TextView(this).apply {
            text = "MARSA PDF Reader"
            textSize = 20f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        })

        val openButton = TextView(this).apply {
            text = "Buka PDF"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { choosePdf() }
        }
        homeList.addView(
            openButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { topMargin = dp(16) }
        )

        val recents = RecentStore.load(this)

        val label = TextView(this).apply {
            text =
                if (recents.isEmpty()) "Belum ada PDF yang pernah dibuka."
                else "PDF terakhir"
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        homeList.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(8)
            }
        )

        val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID"))

        for (item in recents) {
            homeList.addView(
                buildRecentRow(item, fmt),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }
    }

    private fun buildRecentRow(item: RecentPdf, fmt: SimpleDateFormat): View {
        val uri = Uri.parse(item.uri)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { openPdf(uri) }
        }

        card.addView(TextView(this).apply {
            text = item.name
            textSize = 15f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })

        card.addView(TextView(this).apply {
            text = "Halaman ${item.page + 1} \u2022 ${fmt.format(Date(item.time))}"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        fun action(label: String, color: Int, onClick: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { onClick() }
            })
        }

        action("Buka", accent) { openPdf(uri) }
        action("Cetak", textColor) { startPrintFlow(uri) }
        action("Bagikan", textColor) { sharePdf(uri) }
        action("Hapus", Color.rgb(255, 120, 120)) {
            try {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            RecentStore.remove(this, item.uri)
            refreshHome()
        }

        card.addView(actions)
        return card
    }

    private fun saveLastPage() {
        val uri = currentUri ?: return
        if (documentView.pageCount() > 0) {
            RecentStore.updatePage(
                this,
                uri.toString(),
                documentView.currentPageIndex()
            )
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
            val displayName = getDisplayName(uri)
            fileNameView.text = displayName

            RecentStore.touch(this, uri.toString(), displayName)

            val lastPage = RecentStore.pageOf(this, uri.toString())
            if (lastPage > 0) {
                documentView.post { documentView.goToPage(lastPage) }
            }

            Toast.makeText(this, "PDF berhasil dibuka", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            RecentStore.remove(this, uri.toString())
            closePdf()
            Toast.makeText(
                this,
                "Gagal membuka PDF: ${e.message ?: "unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun closePdf() {
        if (::documentView.isInitialized) {
            saveLastPage()
        }

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
        if (::pageView.isInitialized) {
            pageView.visibility = View.GONE
        }
        if (::emptyView.isInitialized) {
            emptyView.visibility = View.VISIBLE
        }
        if (::homeList.isInitialized) {
            refreshHome()
        }
    }

    // ============================================================
    // SHARE
    // ============================================================

    private fun sharePdf(target: Uri? = currentUri) {
        val uri = target
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentUri != null) {
            closePdf()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        saveLastPage()
        super.onPause()
    }

    override fun onDestroy() {
        closePdf()
        if (::documentView.isInitialized) {
            documentView.release()
        }
        super.onDestroy()
    }
}
