package com.bubuads.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bubuads.app.Config
import com.bubuads.app.CrashCatcher
import com.bubuads.app.adblock.AdBlocker
import java.io.ByteArrayInputStream

/**
 * Cliente WebView que:
 *  - bloquea peticiones de red a dominios de anuncios (nivel red, como uBlock),
 *  - inyecta las reglas cosmeticas y el watchdog anti-anuncios en cada pagina,
 *  - maneja la muerte del renderizador de WebView para que Android NO mate la app.
 */
class YtWebViewClient(
    private val adBlocker: AdBlocker,
    private val onMainFrameError: (String?) -> Unit,
    private val onRenderProcessGone: (WebView) -> Unit,
    private val onRendererGiveUp: () -> Unit,
    private val onMainFrameUrlChanged: (String?) -> Unit = {}
) : WebViewClient() {

    /** Crashes consecutivos del renderer sin llegar a cargar ninguna pagina. */
    private var rendererCrashStreak = 0

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (request.isForMainFrame) return super.shouldInterceptRequest(view, request)
        val url = request.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        if (!url.startsWith("http")) return super.shouldInterceptRequest(view, request)
        return try {
            // OJO: aqui estamos en un hilo de red (NO el principal): NO se puede
            // llamar a metodos de WebView (getUrl, evaluateJavascript, etc.).
            // Se usa la URL de la pagina cacheada en [currentPageUrl].
            if (adBlocker.shouldBlock(url, currentPageUrl)) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                // La reescritura de la API de reproduccion (isInlinePlaybackNoAd,
                // poda de la respuesta) se hace en JS dentro de [anti-anuncios.js],
                // porque WebResourceRequest NO expone el cuerpo de la peticion
                // y no se podria re-enviar un POST con el cuerpo modificado.
                super.shouldInterceptRequest(view, request)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "shouldInterceptRequest fallo; se deja pasar la peticion", e)
            super.shouldInterceptRequest(view, request)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        false

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        currentPageUrl = url
        // El json-prune del watchdog debe estar activo ANTES de que el JS de la
        // pagina llame a youtubei/v1/player: se inyecta lo antes posible. Si la
        // evaluacion cae en el documento anterior es inofensivo (el guard
        // __uoWatchdog + onPageCommitVisible lo reinyectan en el nuevo).
        injectWatchdogEarly(view)
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        super.onPageCommitVisible(view, url)
        currentPageUrl = url
        rendererCrashStreak = 0
        injectScripts(view)
        // La URL de la pagina cambio: la UI (insets de Shorts, etc.) puede
        // necesitar re-aplicar su padding segun la pagina actual.
        onMainFrameUrlChanged(url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame && view.isAttachedToWindow) {
            onMainFrameError(error.description?.toString())
        }
    }

    /** URL de la pagina actual, cacheada en el hilo principal para leerla desde hilos de red. */
    @Volatile
    private var currentPageUrl: String? = null

    /** Si muere el renderizador de WebView, recreamos la vista en vez de que Android mate la app. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        rendererCrashStreak++
        // Log minimo para no causar OOM adicional durante el crash
        val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail.didCrash() else true
        android.util.Log.w(TAG, "renderer gone racha=$rendererCrashStreak crashed=$crashed")
        try {
            if (rendererCrashStreak >= 3) {
                onRendererGiveUp()
            } else {
                onRenderProcessGone(view)
            }
        } catch (e: Exception) {
            // Log basico sin stack trace para evitar OOM
            android.util.Log.w(TAG, "fallo recreate: ${e.message}")
        }
        return true
    }

    private fun injectScripts(view: WebView) {
        if (!view.isAttachedToWindow) return
        try {
            // CSS de bloqueo de anuncios
            val css = adBlocker.cosmeticCss()
            if (css.isNotEmpty()) {
                val esc = cachedEscapedCss ?: escapeJs(css).also { cachedEscapedCss = it }
                view.evaluateJavascript(
                    "(function(){var old=document.getElementById('uo-css');if(old)old.remove();" +
                        "var s=document.createElement('style');s.id='uo-css';" +
                        "s.textContent='$esc';document.head.appendChild(s);})();",
                    null
                )
            }
            // CSS del tema (ajustes visuales minimos sobre la interfaz nativa)
            val enhCss = cachedEnhancementCss
            if (enhCss != null) {
                val escEnh = cachedEscapedEnhCss ?: escapeJs(enhCss).also { cachedEscapedEnhCss = it }
                view.evaluateJavascript(
                    "(function(){var old=document.getElementById('bubu-css');if(old)old.remove();" +
                        "var s=document.createElement('style');s.id='bubu-css';" +
                        "s.textContent='$escEnh';document.head.appendChild(s);})();",
                    null
                )
            }
            // Modulos JS (en orden): 1) bloqueo de anuncios, 2) segundo plano
            cachedAntiAnunciosJs?.let { view.evaluateJavascript(it, null) }
            cachedReproduccionJs?.let { view.evaluateJavascript(it, null) }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo inyectar scripts", e)
        }
    }

    /** Inyecta el modulo anti-anuncios lo antes posible: su parche de JSON.parse
     *  y de fetch/XHR debe estar activo ANTES de que la pagina pida el player. */
    private fun injectWatchdogEarly(view: WebView) {
        if (!view.isAttachedToWindow) return
        try {
            cachedAntiAnunciosJs?.let { view.evaluateJavascript(it, null) }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo inyectar el watchdog temprano", e)
        }
    }

    private fun escapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '<' -> sb.append("\\u003C")
                '>' -> sb.append("\\u003E")
                '&' -> sb.append("\\u0026")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "YtWebViewClient"
        @Volatile private var cachedAntiAnunciosJs: String? = null
        @Volatile private var cachedReproduccionJs: String? = null
        @Volatile private var cachedEscapedCss: String? = null
        @Volatile private var cachedEnhancementCss: String? = null
        @Volatile private var cachedEscapedEnhCss: String? = null

        fun invalidateCssCache() { cachedEscapedCss = null; cachedEscapedEnhCss = null }

        fun preloadAssets(context: Context) {
            try {
                if (cachedAntiAnunciosJs == null) {
                    cachedAntiAnunciosJs = context.assets.open("js/anti-anuncios.js").bufferedReader().use { it.readText() }
                }
                if (cachedReproduccionJs == null) {
                    cachedReproduccionJs = context.assets.open("js/reproduccion.js").bufferedReader().use { it.readText() }
                }
                if (cachedEnhancementCss == null) {
                    cachedEnhancementCss = context.assets.open("css/tema.css").bufferedReader().use { it.readText() }
                }
            } catch (_: Exception) {}
        }
    }
}