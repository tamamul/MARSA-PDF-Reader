package com.marsa.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentUri: Uri? = null

    private lateinit var documentView: PdfDocumentView
    private lateinit var fileNameView: TextView
    private lateinit var emptyView: TextView

    private val bg = Color.rgb(14, 19, 30)
    private val surface = Color.rgb(27, 35, 50)
    private val accent = Color.rgb(67, 133, 255)
    private val textColor = Color.rgb(242, 245, 252)

    companion object {
        private const val PICK_PDF = 1001
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

    private fun openPdf(uri: Uri) {
        try {
            closePdf()

            // Ambil persistable permission jika memungkinkan (untuk share nanti).
            // ACTION_VIEW / SEND tidak selalu memberi flag ini — abaikan jika gagal.
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // bukan error fatal
            }

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