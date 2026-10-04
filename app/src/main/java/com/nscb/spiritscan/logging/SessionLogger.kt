package com.nscb.spiritscan.logging

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session CSV evidence log (SpiritScan sealed-evidence style, simplified).
 * Writes to app external files: SpiritScan/session_*.csv
 */
object SessionLogger {
    private const val TAG = "SessionLog"
    private val writing = AtomicBoolean(false)
    @Volatile private var file: File? = null
    @Volatile private var active = false
    @Volatile private var rows = 0

    fun isActive(): Boolean = active
    fun rowCount(): Int = rows

    fun start(context: Context): String {
        if (active) return file?.absolutePath ?: ""
        return try {
            val dir = context.getExternalFilesDir("SpiritScan")
                ?: File(context.filesDir, "SpiritScan").also { it.mkdirs() }
            if (!dir.exists()) dir.mkdirs()
            val name = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            val f = File(dir, name)
            FileOutputStream(f, false).use { out ->
                out.write(
                    ("ts,magUt,zMag,residual,jones,qida,audioZ,hopHz,speechRes," +
                        "camScore,freqPll,freqJitter,fp1,banner,note\n")
                        .toByteArray(Charsets.UTF_8)
                )
            }
            file = f
            rows = 0
            active = true
            Log.i(TAG, "session start ${f.absolutePath}")
            f.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            active = false
            ""
        }
    }

    fun stop() {
        active = false
        Log.i(TAG, "session stop rows=$rows path=${file?.absolutePath}")
    }

    fun log(
        magUt: Float,
        zMag: Float,
        residual: Float,
        jones: String,
        qida: Float,
        audioZ: Float,
        hopHz: Float,
        speechRes: Float,
        camScore: Float,
        freqPll: Float,
        freqJitter: Float,
        fp1: String,
        banner: String,
        note: String
    ) {
        if (!active) return
        val f = file ?: return
        if (!writing.compareAndSet(false, true)) return
        try {
            val ts = System.currentTimeMillis()
            val line = buildString {
                append(ts).append(',')
                append("%.2f".format(magUt)).append(',')
                append("%.2f".format(zMag)).append(',')
                append("%.3f".format(residual)).append(',')
                append(jones.replace(',', ';')).append(',')
                append("%.3f".format(qida)).append(',')
                append("%.2f".format(audioZ)).append(',')
                append("%.1f".format(hopHz)).append(',')
                append("%.3f".format(speechRes)).append(',')
                append("%.3f".format(camScore)).append(',')
                append("%.3f".format(freqPll)).append(',')
                append("%.3f".format(freqJitter)).append(',')
                append(fp1.replace(',', ';')).append(',')
                append(banner.replace(',', ';')).append(',')
                append(note.replace(',', ';').replace('\n', ' '))
                append('\n')
            }
            FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            rows++
        } catch (e: Exception) {
            Log.w(TAG, "log failed: ${e.message}")
        } finally {
            writing.set(false)
        }
    }

    /** Bookmark current moment with a user note. */
    fun bookmark(note: String = "BOOKMARK") {
        if (!active) return
        log(
            magUt = 0f, zMag = 0f, residual = 0f, jones = "—", qida = 0f,
            audioZ = 0f, hopHz = 0f, speechRes = 0f, camScore = 0f,
            freqPll = 0f, freqJitter = 0f, fp1 = "—", banner = "BOOKMARK", note = note
        )
    }

    fun path(): String = file?.absolutePath ?: ""
}
