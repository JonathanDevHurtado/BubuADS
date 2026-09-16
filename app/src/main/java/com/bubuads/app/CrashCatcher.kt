package com.bubuads.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captura cualquier crash no controlado y guarda la traza en el almacenamiento
 * de la app para poder diagnosticarlo. No evita el crash, pero deja un registro
 * legible para el usuario (se muestra en un dialogo al abrir la app).
 */
object CrashCatcher {

    private const val TAG = "CrashCatcher"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(context, "uncaught", throwable)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo guardar el crash", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Guarda una traza (desde cualquier hilo) con una etiqueta de contexto.
     *  OOM-safe: si no hay memoria, intenta un write minimo y no propagate. */
    fun saveCrash(context: Context, tag: String, throwable: Throwable) {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes")
            if (dir.mkdirs() || dir.isDirectory) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "crash_$stamp.txt")
                // Usar solo el message del throwable si OOM (evita allocations grandes)
                val text = try {
                    "[$tag] ${Log.getStackTraceString(throwable)}"
                } catch (oom: OutOfMemoryError) {
                    "[$tag] ${throwable.javaClass.name}: ${throwable.message}"
                }
                f.writeText(text)
            }
        } catch (e: Exception) {
            // Silencioso: si no podemos guardar el crash, no hacer nada
        }
    }

    /** Lista los crashes guardados de ejecuciones anteriores. */
    fun pendingCrashLogs(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes")
        return dir.listFiles()?.filter { it.name.startsWith("crash_") } ?: emptyList()
    }

    /** Contenido de los logs pendientes: (ruta, contenido). */
    fun readCrashLogs(context: Context): List<Pair<String, String>> {
        return pendingCrashLogs(context).map { it.absolutePath to it.readText() }
    }

    /** Borra los logs ya mostrados para no avisar dos veces del mismo fallo. */
    fun clearCrashLogs(context: Context) {
        pendingCrashLogs(context).forEach { it.delete() }
    }
}