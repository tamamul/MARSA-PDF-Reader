package com.marsa.pdfreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentPdf(
    val uri: String,
    val name: String,
    val time: Long,
    val page: Int
)

// Daftar PDF yang pernah dibuka (disimpan lokal di SharedPreferences).
object RecentStore {

    private const val PREFS = "recent"
    private const val KEY = "list"
    private const val MAX_ITEMS = 20

    fun load(ctx: Context): List<RecentPdf> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()

        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RecentPdf(
                    o.getString("uri"),
                    o.optString("name", "Dokumen PDF"),
                    o.optLong("time"),
                    o.optInt("page")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<RecentPdf>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("uri", r.uri)
                    .put("name", r.name)
                    .put("time", r.time)
                    .put("page", r.page)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun pageOf(ctx: Context, uri: String): Int =
        load(ctx).firstOrNull { it.uri == uri }?.page ?: 0

    // Naikkan ke urutan teratas; halaman terakhir lama tetap dipertahankan.
    fun touch(ctx: Context, uri: String, name: String) {
        val old = load(ctx)
        val page = old.firstOrNull { it.uri == uri }?.page ?: 0

        val updated = listOf(RecentPdf(uri, name, System.currentTimeMillis(), page)) +
            old.filter { it.uri != uri }

        save(ctx, updated.take(MAX_ITEMS))
    }

    fun updatePage(ctx: Context, uri: String, page: Int) {
        save(ctx, load(ctx).map { if (it.uri == uri) it.copy(page = page) else it })
    }

    fun remove(ctx: Context, uri: String) {
        save(ctx, load(ctx).filter { it.uri != uri })
    }
}
