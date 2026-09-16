package com.bubuads.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterCompilerTest {

    private fun assetLines(): List<String> {
        val dir = File("src/main/assets/filtros")
        return dir.listFiles().orEmpty().flatMap { it.readLines() }
    }

    @Test
    fun compilaListasRealesSinFallar() {
        val start = System.currentTimeMillis()
        val lines = assetLines()
        val rules = FilterCompiler.compileNetwork(lines)
        val css = FilterCompiler.compileCosmetic(lines)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("hosts bloqueados", rules.blockedHosts.size > 500)
        assertTrue("css cosmetico", css.length > 1000)
        assertTrue("compilacion rapida (<5s): ${elapsed}ms", elapsed < 5000)
        println("compilado en ${elapsed}ms: ${rules.blockedHosts.size} hosts, " +
            "css ${css.length} chars")
    }

    @Test
    fun noReglasDegeneradas() {
        // Regression del bug critico: reglas que compilan a .* / hosts vacios
        val rules = FilterCompiler.compileNetwork(assetLines())

        assertFalse("no debe haber hosts vacios", rules.blockedHosts.contains(""))
        assertFalse("no debe haber hosts vacios", rules.exceptionHosts.contains(""))
        assertTrue("bloqueados no vacios", rules.blockedHosts.none { it.isBlank() })
        assertTrue("regex no debe ser .*", rules.urlRegexBlocks.none { it.patternStr == "^.*$" || it.patternStr == ".*" })
        assertTrue("regex anclado no debe ser .*", rules.regexBlocks.none { it.patternStr == ".*" })
        assertTrue("path no debe ser raro", rules.hostPathBlocks.values.flatten().none { it.patternStr.contains("^https?://.*/.*$") })
    }

    @Test
    fun bloqueaDominiosDeAnunciosDeGoogle() {
        val rules = FilterCompiler.compileNetwork(assetLines())

        assertTrue(
            "googleads.g.doubleclick.net debe estar en la lista",
            "googleads.g.doubleclick.net" in rules.blockedHosts ||
                rules.isBlocked("https://googleads.g.doubleclick.net/pagead/ads?x=1", "https://m.youtube.com/")
        )
        assertTrue(
            "pagead2.googlesyndication.com debe bloquearse",
            rules.isBlocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://m.youtube.com/watch?v=abc")
        )
    }

    @Test
    fun noBloqueaEndpointsEsencialesDeLaApi() {
        // Regression del bug critico: las reglas $replace= de uBlock (modifican la
        // respuesta, no bloquean) se compilaban como bloqueos y dejaban la pagina
        // de reproduccion en carga infinita (get_watch/player bloqueados).
        val rules = FilterCompiler.compileNetwork(assetLines())
        val page = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
        val apiKey = "key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"

        val esenciales = listOf(
            "https://m.youtube.com/youtubei/v1/player?$apiKey",
            "https://m.youtube.com/youtubei/v1/next?$apiKey",
            "https://m.youtube.com/youtubei/v1/browse?$apiKey",
            "https://m.youtube.com/youtubei/v1/get_watch?$apiKey",
            "https://m.youtube.com/youtubei/v1/search?$apiKey",
            "https://m.youtube.com/youtubei/v1/reel/reel_watch_sequence?$apiKey",
            "https://www.youtube.com/youtubei/v1/player?$apiKey",
            "https://m.youtube.com/youtubei/v1/log_event?$apiKey"
        )
        for (u in esenciales) {
            assertFalse("endpoint esencial no debe bloquearse: $u", rules.isBlocked(u, page))
        }

        // Los endpoints de ANUNCIOS que el reproductor espera de forma SINCRONA
        // estan EXCEPCIONADOS: bloquearlos devuelve un cuerpo vacio y YouTube
        // niega la reproduccion (anti-adblock), asi que los videos no cargan
        // con el bloqueador activado. La publicidad se oculta/salta por CSS+JS.
        val anunciosSincronos = listOf(
            "https://m.youtube.com/youtubei/v1/player/ad_break?$apiKey",
            "https://www.youtube.com/youtubei/v1/player/ad_break?$apiKey",
            "https://m.youtube.com/get_midroll_20771234?host=0.0.0.0&el=detailpage",
            "https://static.doubleclick.net/instream/ad_status.js?sessionID=xyz"
        )
        for (u in anunciosSincronos) {
            assertFalse("endpoint de anuncio sincrono no debe bloquearse: $u", rules.isBlocked(u, page))
        }
    }

    @Test
    fun bloqueaInitPlaybackDeAnunciosPeroNoLaReproduccion() {
        val rules = FilterCompiler.compileNetwork(assetLines())
        val page = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"

        // Anuncio: initplayback con c=TVHTML5 y oad (regla de uBlock). Debe bloquearse.
        assertTrue(
            "initplayback de anuncios (TVHTML5+oad) debe bloquearse",
            rules.isBlocked(
                "https://r2---sn-a5okrn7e.googlevideo.com/initplayback?source=youtube&c=TVHTML5&oad=1&fvip=3&as=abc",
                page
            )
        )

        // Reproduccion principal: videoplayback, no debe bloquearse nunca.
        assertFalse(
            "videoplayback (video real) no debe bloquearse",
            rules.isBlocked(
                "https://rr2---sn-a5okrn7e.googlevideo.com/videoplayback?expire=1720000000&id=abc123&ipbits=0&itag=18&signature=xyz",
                page
            )
        )

        // initplayback de contenido normal (sin oad) no debe bloquearse.
        assertFalse(
            "initplayback normal (sin oad) no debe bloquearse",
            rules.isBlocked(
                "https://r2.googlevideo.com/initplayback?source=youtube&c=WEB&nl=1",
                page
            )
        )
    }

    @Test
    fun noBloqueaContenidoLegitimoDeYouTube() {
        val rules = FilterCompiler.compileNetwork(assetLines())

        assertFalse(
            "youtube.com no debe bloquearse",
            rules.isBlocked("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://m.youtube.com/")
        )
        assertFalse(
            "googlevideo (video real) no debe bloquearse",
            rules.isBlocked("https://rr2.googlevideo.com/videoplayback?id=abc123&itag=18", "https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertFalse(
            "peticiones https normales no deben bloquearse",
            rules.isBlocked("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", "https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertFalse(
            "analytics.youtube.com en pagina de youtube es mismo partido (no 3p)",
            rules.isBlocked("https://analytics.youtube.com/pageview/abc", "https://m.youtube.com/")
        )
    }

    @Test
    fun generaCssCosmeticoParaYouTube() {
        val css = FilterCompiler.compileCosmetic(assetLines())
        assertTrue(
            "debe incluir ytd-ad-slot-renderer",
            css.contains("ytd-ad-slot-renderer{display:none!important}")
        )
        assertTrue(
            "debe incluir .ytp-ad-module",
            css.contains(".ytp-ad-module{display:none!important}")
        )
    }

    @Test
    fun cssCosmeticoDeduplicaSelectores() {
        val lines = listOf(
            "! com", "youtube.com##ytd-ad-slot-renderer", "youtube.com##ytd-ad-slot-renderer",
            "! com", "##.ytp-ad-module", "##.ytp-ad-module"
        )
        val css = FilterCompiler.compileCosmetic(lines)
        val countAdSlot = css.split("ytd-ad-slot-renderer{").size - 1
        val countAdModule = css.split(".ytp-ad-module{").size - 1
        assertEquals("ytd-ad-slot-renderer debe aparecer 1 vez", 1, countAdSlot)
        assertEquals(".ytp-ad-module debe aparecer 1 vez", 1, countAdModule)
    }

    @Test
    fun cssCosmeticoSoloParaYouTube() {
        // Las reglas de otros sitios no deben ensuciar el CSS (app solo-YouTube)
        val lines = listOf(
            "example.com##.ad-banner",
            "random-site.net##div[class*=\"advert\"]",
            "##.ad-banner",
            "youtube.com##ytd-ad-slot-renderer",
            "m.youtube.com##.ytp-ad-module"
        )
        val css = FilterCompiler.compileCosmetic(lines)
        assertTrue("debe incluir ytd-ad-slot-renderer", css.contains("ytd-ad-slot-renderer{display:none!important}"))
        assertTrue("debe incluir .ytp-ad-module", css.contains(".ytp-ad-module{display:none!important}"))
        assertFalse("no debe incluir .ad-banner", css.contains(".ad-banner"))
        assertFalse("no debe incluir selectores de otras webs", css.contains("advert"))
    }

    @Test
    fun matcherRapidoEnCaliente() {
        val rules = FilterCompiler.compileNetwork(assetLines())
        val urls = ArrayList<String>(5000)
        for (i in 0 until 5000) {
            urls.add(
                "https://rr${i % 20}---sn-abcd${i}.googlevideo.com/videoplayback?expire=1720000000&id=vid$i&itag=18&signature=xyz&source=youtube"
            )
        }
        // Calentamiento
        rules.isBlocked(urls[0], "https://m.youtube.com/watch?v=abc")
        val start = System.currentTimeMillis()
        var blocked = 0
        for (u in urls) if (rules.isBlocked(u, "https://m.youtube.com/watch?v=abc")) blocked++
        val elapsed = System.currentTimeMillis() - start
        assertEquals("ninguna reproduccion legitima bloqueada", 0, blocked)
        println("5000 peticiones matcheadas en ${elapsed}ms")
        assertTrue("5000 peticiones < 3500ms: ${elapsed}ms", elapsed < 3500)
    }

    @Test
    fun cssCosmeticoSinScriptletsNiFalsosPositivos() {
        val css = FilterCompiler.compileCosmetic(assetLines())
        assertFalse("no debe colar scriptlets +js de uBlock", css.contains("+js("))
        assertFalse("no debe incluir reglas de dominios falsos como nsfwyoutube", css.contains("nsfwyoutube"))
        assertFalse("no debe incluir reglas de otras webs", css.contains(".ad-banner"))
        assertTrue("debe seguir ocultando ytd-ad-slot-renderer", css.contains("ytd-ad-slot-renderer{display:none!important}"))
    }

    @Test
    fun cacheRoundTrip() {
        val rules = FilterCompiler.compileNetwork(assetLines())
        val tmp = File.createTempFile("network", ".dat")
        rules.save(tmp)
        val loaded = NetworkRules.load(tmp)
        tmp.delete()

        assertEquals(rules.blockedHosts.size, loaded.blockedHosts.size)
        assertEquals(rules.hostPathBlocks.size, loaded.hostPathBlocks.size)
        assertTrue(
            "cache debe conservar el bloqueo",
            loaded.isBlocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://m.youtube.com/")
        )
        assertTrue(
            "cache debe conservar la regla de anuncios TVHTML5",
            loaded.isBlocked(
                "https://r2---sn-a5okrn7e.googlevideo.com/initplayback?source=youtube&c=TVHTML5&oad=1",
                "https://m.youtube.com/watch?v=abc"
            )
        )
    }
}