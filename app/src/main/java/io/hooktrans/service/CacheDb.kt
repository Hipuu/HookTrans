package io.hooktrans.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.hooktrans.core.Logs

/**
 * Persistent translation memory. Keyed by engine + language pair + source text so switching
 * engine or language never returns a stale string.
 */
class CacheDb(ctx: Context) : SQLiteOpenHelper(ctx, "translations.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE t (k TEXT PRIMARY KEY, v TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_ts ON t(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS t")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        runCatching { db.enableWriteAheadLogging() }
    }

    fun get(keys: List<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(keys.size)
        try {
            val db = readableDatabase
            // Chunked to stay well under SQLite's variable limit.
            keys.chunked(200).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.rawQuery("SELECT k, v FROM t WHERE k IN ($placeholders)", chunk.toTypedArray())
                    .use { c ->
                        while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
                    }
            }
        } catch (t: Throwable) {
            Logs.w("cache read failed", t)
        }
        return out
    }

    fun put(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                entries.forEach { (k, v) ->
                    db.insertWithOnConflict(
                        "t", null,
                        ContentValues().apply {
                            put("k", k); put("v", v); put("ts", now)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Logs.w("cache write failed", t)
        }
    }

    fun count(): Long = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM t", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    } catch (t: Throwable) {
        0L
    }

    fun clear() {
        runCatching { writableDatabase.execSQL("DELETE FROM t") }
    }
}
