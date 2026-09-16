package com.bubuads.app.adblock

import android.content.Context
import android.util.Log
import com.bubuads.app.Config
import com.bubuads.app.CrashCatcher
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Gestor del bloqueador: carga/compila las listas, mantiene una cache
 * de reglas compiladas y decide que peticiones bloquear.
 *
 * Hilo seguro: el estado se publica via [AtomicReference]; la cache se
 * escribe de forma atomica (tmp + rename) para no corromperla ante cortes.
 */
class AdBlocker(private val context: Context) {

    companion object {
        private const val TAG = "AdBlocker"
        private const val NET_FILE = "adblock_rules.cache"
        private const val COSMETIC_FILE = "adblock_cosmetics.cache"

        private val FILTER_SOURCES = listOf(
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt"
        )
    }

    private val cacheDir: File
        get() = context.cacheDir

    private val rulesRef = AtomicReference(NetworkRules())
    private val cosmeticRef = AtomicReference("")
    private val statsRef = AtomicReference(0 to 0)
    private val readyRef = AtomicReference(false)
    private val enabledRef = AtomicReference(true)

    @Volatile var lastUpdate: Long = 0L
        private set

    var enabled: Boolean
        get() = enabledRef.get()
        set(v) { enabledRef.set(v) }

    val isReady: Boolean get() = readyRef.get()

    /** Carga la cache y, si no existe o esta corrupta, compila las listas en segundo plano. */
    fun init() {
        lastUpdate = prefs().getLong("last_update", 0L)
        enabled = prefs().getBoolean("enabled", true)
        if (tryLoadCache()) {
            readyRef.set(true)
            return
        }
        // Si no hay cache, compilar en background sin bloquear el main thread
        thread(name = "adblock-compile", isDaemon = true) {
            try {
                val assets = arrayOf(
                    "filtros/easylist.txt",
                    "filtros/easyprivacy.txt",
                    "filtros/ublock-filters.txt",
                    "filtros/quick-fixes.txt",
                    "filtros/youtube.txt"
                )
                val lines = ArrayList<String>()
                for (a in assets) {
                    context.assets.open(a).bufferedReader().useLines { lines.addAll(it) }
                }
                compileAndPublish(lines)
                writeCache()
                readyRef.set(true)
                Log.i(TAG, "Compilacion inicial completada")
            } catch (e: Throwable) {
                CrashCatcher.saveCrash(context, "compile", e)
            }
        }
    }

    /** Intenta cargar las reglas compiladas desde cache. Devuelve true si fueron utiles. */
    private fun tryLoadCache(): Boolean {
        return try {
            val net = File(cacheDir, NET_FILE)
            val cos = File(cacheDir, COSMETIC_FILE)
            if (!net.exists() || !cos.exists()) return false
            val r = NetworkRules.load(net)
            if (r.blockedHosts.size + r.ctxHostBlocks.size + r.hostPathBlocks.size < 1000) return false
            rulesRef.set(r)
            cosmeticRef.set(cos.readText())
            updateStats()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Cache no usable: ${e.message}")
            false
        }
    }

    /** Actualiza las listas desde Internet en segundo plano. */
    fun updateLists(onDone: () -> Unit) {
        thread(name = "adblock-update", isDaemon = true) {
            val all = ArrayList<String>()
            var anyOk = false
            for (url in FILTER_SOURCES) {
                try {
                    val body = fetch(url)
                    if (body != null && body.isNotBlank()) {
                        all.addAll(body.lineSequence())
                        anyOk = true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Fallo al descargar $url: ${e.message}")
                }
            }
            val local = arrayOf(
                "filtros/easylist.txt",
                "filtros/easyprivacy.txt",
                "filtros/ublock-filters.txt",
                "filtros/quick-fixes.txt",
                "filtros/youtube.txt"
            )
            try {
                for (a in local) {
                    context.assets.open(a).bufferedReader().useLines { all.addAll(it) }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Fallo al leer assets: ${e.message}")
            }
            if (anyOk || all.isNotEmpty()) {
                try {
                    compileAndPublish(all)
                    writeCache()
                    lastUpdate = System.currentTimeMillis()
                    prefs().edit().putLong("last_update", lastUpdate).apply()
                } catch (e: Throwable) {
                    CrashCatcher.saveCrash(context, "update", e)
                }
            }
            onDone()
        }
    }

    private fun compileAndPublish(lines: List<String>) {
        val started = System.currentTimeMillis()
        val rules = FilterCompiler.compileNetwork(lines)
        val css = FilterCompiler.compileCosmetic(lines)
        rulesRef.set(rules)
        cosmeticRef.set(css)
        updateStats()
        Log.i(TAG, "Compilado en ${System.currentTimeMillis() - started} ms: " +
                "hosts=${rules.blockedHosts.size} ctx=${rules.ctxHostBlocks.size} paths=${rules.hostPathBlocks.size} css=${css.length}")
    }

    private fun updateStats() {
        val r = rulesRef.get()
        val hosts = r.blockedHosts.size + r.ctxHostBlocks.size + r.hostPathBlocks.size +
                r.importantHosts.size + r.importantCtxHosts.size + r.importantHostPaths.size
        statsRef.set(hosts to cosmeticRef.get().length)
    }

    /** Decide si la peticion debe bloquearse (se llama desde el hilo de red del WebView). */
    fun shouldBlock(url: String, pageUrl: String?): Boolean {
        if (!enabledRef.get()) return false
        if (!url.startsWith("http")) return false
        if (url.contains("googlevideo.com/videoplayback")) return false
        return rulesRef.get().isBlocked(url, pageUrl)
    }

    /** Reglas cosmeticas (CSS) a inyectar. */
    fun cosmeticCss(): String = cosmeticRef.get()

    /** Estadisticas para el dialogo de ajustes: (hosts bloqueables, bytes de CSS). */
    fun stats(): Pair<Int, Int> = statsRef.get()

    private fun writeCache() {
        try {
            val net = File(cacheDir, NET_FILE)
            val cos = File(cacheDir, COSMETIC_FILE)
            val tmpNet = File(cacheDir, "$NET_FILE.tmp")
            val tmpCos = File(cacheDir, "$COSMETIC_FILE.tmp")
            rulesRef.get().save(tmpNet)
            FileOutputStream(tmpCos).use { it.write(cosmeticRef.get().toByteArray(Charsets.UTF_8)) }
            if (!tmpNet.renameTo(net)) {
                tmpNet.copyTo(net, overwrite = true)
                tmpNet.delete()
            }
            if (!tmpCos.renameTo(cos)) {
                tmpCos.copyTo(cos, overwrite = true)
                tmpCos.delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo escribir cache: ${e.message}")
        }
    }

    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", Config.CHROME_UA)
            val code = conn.responseCode
            if (code !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs() = context.getSharedPreferences("adblock", Context.MODE_PRIVATE)
}