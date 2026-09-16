package com.bubuads.app.adblock

import java.io.File
import java.net.URL
import java.util.regex.Pattern

/**
 * Autómata Aho-Corasick: detecta si CUALQUIERA de un conjunto de cadenas
 * aparece en un texto con UNA sola pasada. Sustituye el N*contains() lineal.
 */
class AhoCorasick(patterns: List<String>) {

    private class Node {
        val children = HashMap<Char, Node>()
        var fail: Node? = null
        var terminal = false
    }

    private val root = Node()

    init {
        for (p in patterns) {
            if (p.isEmpty()) continue
            var node = root
            for (c in p) node = node.children.getOrPut(c) { Node() }
            node.terminal = true
        }
        val queue = ArrayDeque<Node>()
        for (ch in root.children.values) {
            ch.fail = root
            queue.addLast(ch)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((c, child) in cur.children) {
                queue.addLast(child)
                var f = cur.fail
                while (f != null && !f.children.containsKey(c)) f = f.fail
                child.fail = f?.children?.get(c) ?: root
                if (child.fail!!.terminal) child.terminal = true
            }
        }
    }

    fun anyMatch(s: String): Boolean {
        var node = root
        for (i in s.indices) {
            val c = s[i]
            while (node !== root && !node.children.containsKey(c)) node = node.fail ?: root
            node = node.children[c] ?: root
            if (node.terminal) return true
        }
        return false
    }
}

/**
 * Variante de Aho-Corasick que REPORTa cuales patrones aparecen en el texto.
 * Se usa para indexar reglas regex por su "ancla" literal.
 */
class AhoCorasickReporter(patterns: List<String>) {

    private class Node {
        val children = HashMap<Char, Node>()
        var fail: Node? = null
        val outputs = ArrayList<String>()
    }

    private val root = Node()

    init {
        for (p in patterns) {
            if (p.isEmpty()) continue
            var node = root
            for (c in p) node = node.children.getOrPut(c) { Node() }
            node.outputs.add(p)
        }
        val queue = ArrayDeque<Node>()
        for (ch in root.children.values) {
            ch.fail = root
            queue.addLast(ch)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((c, child) in cur.children) {
                queue.addLast(child)
                var f = cur.fail
                while (f != null && !f.children.containsKey(c)) f = f.fail
                val fc = f?.children?.get(c) ?: root
                child.fail = fc
                child.outputs.addAll(fc.outputs)
            }
        }
    }

    fun matches(s: String): List<String> {
        val res = ArrayList<String>(2)
        var node = root
        for (i in s.indices) {
            val c = s[i]
            while (node !== root && !node.children.containsKey(c)) node = node.fail ?: root
            node = node.children[c] ?: root
            if (node.outputs.isNotEmpty()) res.addAll(node.outputs)
        }
        return res
    }
}

/**
 * Contexto de aplicacion de una regla: opciones de la sintaxis
 * Adblock Plus / uBlock Origin ($third-party, $domain=, $important).
 */
data class RuleContext(
    val thirdParty: Boolean = false,
    val pageDomains: Set<String>? = null,
    val important: Boolean = false
)

/** Extrae el host en minusculas de una URL, o null si no es parseable. */
internal fun hostOf(url: String): String? = try {
    URL(url).host?.lowercase()?.takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

/** Mismo "registrable domain" (heuristica): youtube.com, m.youtube.com e i.ytimg.com son same-party. */
internal fun sameParty(pageHost: String, requestHost: String): Boolean =
    registrableHost(pageHost) == registrableHost(requestHost)

private fun registrableHost(host: String): String {
    val labels = host.split('.')
    val n = labels.size
    if (n >= 3 && labels.last().length <= 2) return labels.takeLast(3).joinToString(".")
    return if (n >= 2) labels.takeLast(2).joinToString(".") else host
}

/** Comprueba las restricciones de contexto de una regla. */
internal fun ctxApplies(ctx: RuleContext, pageHost: String?, url: String): Boolean {
    if (ctx.thirdParty) {
        val rh = hostOf(url) ?: return false
        if (pageHost == null || sameParty(pageHost, rh)) return false
    }
    if (ctx.pageDomains != null) {
        if (pageHost == null) return false
        if (ctx.pageDomains.none { pageHost == it || pageHost.endsWith(".$it") }) return false
    }
    return true
}

/** Regla de bloqueo "host + ruta": ||host/path… */
class PathRule(val patternStr: String, val ctx: RuleContext) {
    private val pattern: Pattern = try {
        Pattern.compile(patternStr)
    } catch (e: OutOfMemoryError) {
        Pattern.compile("a^")  // pattern que nunca matchea
    }

    fun matches(url: String, pageHost: String?): Boolean {
        if (!ctxApplies(ctx, pageHost, url)) return false
        return pattern.matcher(url).find()
    }
}

/** Regla anclada al inicio/fin de URL o con comodines: |…|, *…* */
class UrlRegexRule(val patternStr: String, val anchor: String?, val ctx: RuleContext) {
    private val pattern: Pattern = try {
        Pattern.compile(patternStr)
    } catch (e: OutOfMemoryError) {
        Pattern.compile("a^")
    }

    fun matches(url: String, pageHost: String?): Boolean {
        if (!ctxApplies(ctx, pageHost, url)) return false
        val a = anchor
        if (a != null && !url.contains(a)) return false
        return pattern.matcher(url).find()
    }
}

/**
 * Regla regex pura (/…/) con optimizacion de "literal requerido":
 * si la URL no contiene el literal, se descarta sin ejecutar el regex.
 */
class AnchoredRegexRule(val patternStr: String, val anchor: String?, val ctx: RuleContext) {
    private val pattern: Pattern = try {
        Pattern.compile(patternStr)
    } catch (e: OutOfMemoryError) {
        Pattern.compile("a^")
    }

    fun matches(url: String, pageHost: String?): Boolean {
        if (!ctxApplies(ctx, pageHost, url)) return false
        val a = anchor
        if (a != null && !url.contains(a)) return false
        return pattern.matcher(url).find()
    }
}

/**
 * Conjunto de reglas de red compiladas, listas para matchear en caliente.
 * Las excepciones se comprueban SIEMPRE antes que los bloqueos.
 */
class NetworkRules {

    val blockedHosts = HashSet<String>()
    val ctxHostBlocks = mutableListOf<Pair<String, RuleContext>>()
    val exceptionHosts = HashSet<String>()
    val ctxHostExceptions = mutableListOf<Pair<String, RuleContext>>()
    val importantHosts = HashSet<String>()
    val importantCtxHosts = mutableListOf<Pair<String, RuleContext>>()
    val pageExceptions = HashSet<String>()

    val hostPathBlocks = HashMap<String, MutableList<PathRule>>()
    val hostPathExceptions = HashMap<String, MutableList<PathRule>>()
    val importantHostPaths = HashMap<String, MutableList<PathRule>>()

    val urlRegexBlocks = mutableListOf<UrlRegexRule>()
    val urlRegexExceptions = mutableListOf<UrlRegexRule>()
    val regexBlocks = mutableListOf<AnchoredRegexRule>()
    val regexExceptions = mutableListOf<AnchoredRegexRule>()
    val substrings = mutableListOf<String>()
    val substringExceptions = mutableListOf<String>()

    private var subMatcher: AhoCorasick = AhoCorasick(emptyList())
    private var subExcMatcher: AhoCorasick = AhoCorasick(emptyList())
    private var urlBlockMap = HashMap<String, MutableList<UrlRegexRule>>()
    private var urlBlockNoAnchor = mutableListOf<UrlRegexRule>()
    private var urlBlockReporter: AhoCorasickReporter = AhoCorasickReporter(emptyList())
    private var urlExcMap = HashMap<String, MutableList<UrlRegexRule>>()
    private var urlExcNoAnchor = mutableListOf<UrlRegexRule>()
    private var urlExcReporter: AhoCorasickReporter = AhoCorasickReporter(emptyList())

    /** Construye los automatas de subcadenas y el indice por ancla. Llamar tras poblar las listas. */
    fun rebuildMatchers() {
        subMatcher = AhoCorasick(substrings)
        subExcMatcher = AhoCorasick(substringExceptions)
        urlBlockMap = indexByAnchor(urlRegexBlocks)
        urlBlockNoAnchor = urlRegexBlocks.filter { it.anchor == null }.toMutableList()
        urlBlockReporter = AhoCorasickReporter(urlBlockMap.keys.toList())
        urlExcMap = indexByAnchor(urlRegexExceptions)
        urlExcNoAnchor = urlRegexExceptions.filter { it.anchor == null }.toMutableList()
        urlExcReporter = AhoCorasickReporter(urlExcMap.keys.toList())
    }

    private fun indexByAnchor(list: List<UrlRegexRule>): HashMap<String, MutableList<UrlRegexRule>> {
        val map = HashMap<String, MutableList<UrlRegexRule>>()
        for (r in list) {
            val a = r.anchor ?: continue
            map.getOrPut(a) { mutableListOf() }.add(r)
        }
        return map
    }

    private fun matchesAnchored(map: HashMap<String, MutableList<UrlRegexRule>>, reporter: AhoCorasickReporter, url: String, pageHost: String?): Boolean {
        for (a in reporter.matches(url)) {
            map[a]?.let { if (it.any { r -> r.matches(url, pageHost) }) return true }
        }
        return false
    }

    /**
     * Decide si la peticion debe bloquearse.
     * @param requestUrl URL de la peticion (imagen, script, xhr, media...)
     * @param pageUrl    URL de la pagina que origina la peticion (puede ser null)
     */
    fun isBlocked(requestUrl: String, pageUrl: String?): Boolean {
        val pageHost = pageUrl?.let { hostOf(it) }
        if (pageHost != null && pageHost in pageExceptions) return false
        val rHost = hostOf(requestUrl) ?: return false
        val candidates = hostCandidates(rHost)

        // Reglas $important: bloquean incluso con excepciones presentes
        for (c in candidates) if (c in importantHosts) return true
        for ((h, ctx) in importantCtxHosts) {
            if (h in candidates && ctxApplies(ctx, pageHost, requestUrl)) return true
        }
        for (c in candidates) {
            importantHostPaths[c]?.let { list ->
                if (list.any { it.matches(requestUrl, pageHost) }) return true
            }
        }

        // Excepciones primero (@@…)
        for (c in candidates) if (c in exceptionHosts) return false
        for ((h, ctx) in ctxHostExceptions) {
            if (h in candidates && ctxApplies(ctx, pageHost, requestUrl)) return false
        }
        for (c in candidates) {
            hostPathExceptions[c]?.let { list ->
                if (list.any { it.matches(requestUrl, pageHost) }) return false
            }
        }
        if (matchesAnchored(urlExcMap, urlExcReporter, requestUrl, pageHost)) return false
        if (urlExcNoAnchor.any { it.matches(requestUrl, pageHost) }) return false
        if (regexExceptions.any { it.matches(requestUrl, pageHost) }) return false
        if (subExcMatcher.anyMatch(requestUrl)) return false

        // Reglas de bloqueo
        for (c in candidates) if (c in blockedHosts) return true
        for ((h, ctx) in ctxHostBlocks) {
            if (h in candidates && ctxApplies(ctx, pageHost, requestUrl)) return true
        }
        for (c in candidates) {
            hostPathBlocks[c]?.let { list ->
                if (list.any { it.matches(requestUrl, pageHost) }) return true
            }
        }
        if (matchesAnchored(urlBlockMap, urlBlockReporter, requestUrl, pageHost)) return true
        if (urlBlockNoAnchor.any { it.matches(requestUrl, pageHost) }) return true
        if (regexBlocks.any { it.matches(requestUrl, pageHost) }) return true
        if (subMatcher.anyMatch(requestUrl)) return true
        return false
    }

    /** Host y sus dominios padre, p. ej. a.b.example.com -> [a.b.example.com, b.example.com, example.com]. */
    private fun hostCandidates(host: String): List<String> {
        val parts = host.split('.')
        val out = ArrayList<String>(4)
        out.add(host)
        if (parts.size >= 3) out.add(parts.drop(parts.size - 2).joinToString("."))
        if (parts.size >= 4) out.add(parts.drop(parts.size - 3).joinToString("."))
        return out
    }

    /** Serializa las reglas a un archivo de texto comprensible para [load]. */
    fun save(file: File) {
        val sb = StringBuilder()
        sb.append("#v2\n")
        fun sec(name: String) {
            sb.append('[').append(name).append("]\n")
        }
        fun hostLines(map: HashMap<String, MutableList<PathRule>>) {
            for ((h, list) in map) {
                for (p in list) {
                    sb.append(h).append('\t').append(p.patternStr).append('\t').append(encodeCtx(p.ctx)).append('\n')
                }
            }
        }
        sec("BLOCKED_HOSTS"); blockedHosts.forEach { sb.append(it).append('\n') }
        sec("EXCEPTION_HOSTS"); exceptionHosts.forEach { sb.append(it).append('\n') }
        sec("IMPORTANT_HOSTS"); importantHosts.forEach { sb.append(it).append('\n') }
        sec("PAGE_EXCEPTIONS"); pageExceptions.forEach { sb.append(it).append('\n') }
        sec("CTX_HOST_BLOCKS"); ctxHostBlocks.forEach { sb.append(it.first).append('\t').append(encodeCtx(it.second)).append('\n') }
        sec("CTX_HOST_EXCEPTIONS"); ctxHostExceptions.forEach { sb.append(it.first).append('\t').append(encodeCtx(it.second)).append('\n') }
        sec("IMPORTANT_CTX_HOSTS"); importantCtxHosts.forEach { sb.append(it.first).append('\t').append(encodeCtx(it.second)).append('\n') }
        sec("HOST_PATH_BLOCKS"); hostLines(hostPathBlocks)
        sec("HOST_PATH_EXCEPTIONS"); hostLines(hostPathExceptions)
        sec("IMPORTANT_HOST_PATHS"); hostLines(importantHostPaths)
        sec("URL_REGEX_BLOCKS"); urlRegexBlocks.forEach { sb.append(it.patternStr).append('\t').append(it.anchor ?: "").append('\t').append(encodeCtx(it.ctx)).append('\n') }
        sec("URL_REGEX_EXCEPTIONS"); urlRegexExceptions.forEach { sb.append(it.patternStr).append('\t').append(it.anchor ?: "").append('\t').append(encodeCtx(it.ctx)).append('\n') }
        sec("REGEX_BLOCKS"); regexBlocks.forEach { sb.append(it.patternStr).append('\t').append(it.anchor ?: "").append('\t').append(encodeCtx(it.ctx)).append('\n') }
        sec("REGEX_EXCEPTIONS"); regexExceptions.forEach { sb.append(it.patternStr).append('\t').append(it.anchor ?: "").append('\t').append(encodeCtx(it.ctx)).append('\n') }
        sec("SUBSTRING_BLOCKS"); substrings.forEach { sb.append(it).append('\n') }
        sec("SUBSTRING_EXCEPTIONS"); substringExceptions.forEach { sb.append(it).append('\n') }
        file.writeText(sb.toString())
    }

    companion object {
        private const val CACHE_VERSION = "#v2"

        /** Carga reglas desde un archivo guardado con [NetworkRules.save]. Devuelve reglas vacias si la version no coincide. */
        fun load(file: File): NetworkRules {
            val r = NetworkRules()
            var sec = ""
            val lines = file.readLines()
            if (lines.firstOrNull() != CACHE_VERSION) return r
            for (line in lines) {
                val t = line.trim()
                when {
                    t.startsWith("[") -> sec = t.removeSurrounding("[", "]")
                    t.isEmpty() -> {}
                    else -> when (sec) {
                        "BLOCKED_HOSTS" -> r.blockedHosts.add(t)
                        "EXCEPTION_HOSTS" -> r.exceptionHosts.add(t)
                        "IMPORTANT_HOSTS" -> r.importantHosts.add(t)
                        "PAGE_EXCEPTIONS" -> r.pageExceptions.add(t)
                        "CTX_HOST_BLOCKS" -> r.ctxHostBlocks.add(parseCtxHost(t))
                        "CTX_HOST_EXCEPTIONS" -> r.ctxHostExceptions.add(parseCtxHost(t))
                        "IMPORTANT_CTX_HOSTS" -> r.importantCtxHosts.add(parseCtxHost(t))
                        "HOST_PATH_BLOCKS" -> addPath(r.hostPathBlocks, t)
                        "HOST_PATH_EXCEPTIONS" -> addPath(r.hostPathExceptions, t)
                        "IMPORTANT_HOST_PATHS" -> addPath(r.importantHostPaths, t)
                        "URL_REGEX_BLOCKS" -> r.urlRegexBlocks.add(parseUrlRegex(t))
                        "URL_REGEX_EXCEPTIONS" -> r.urlRegexExceptions.add(parseUrlRegex(t))
                        "REGEX_BLOCKS" -> r.regexBlocks.add(parseAnchoredRegex(t))
                        "REGEX_EXCEPTIONS" -> r.regexExceptions.add(parseAnchoredRegex(t))
                        "SUBSTRING_BLOCKS" -> r.substrings.add(t)
                        "SUBSTRING_EXCEPTIONS" -> r.substringExceptions.add(t)
                    }
                }
            }
            r.rebuildMatchers()
            return r
        }

        private fun parseCtxHost(t: String): Pair<String, RuleContext> {
            val p = t.split('\t')
            return p[0] to decodeCtx(p.getOrElse(1) { "" })
        }

        private fun addPath(map: HashMap<String, MutableList<PathRule>>, t: String) {
            val p = t.split('\t')
            map.getOrPut(p[0]) { mutableListOf() }.add(PathRule(p[1], decodeCtx(p.getOrElse(2) { "" })))
        }

        private fun parseUrlRegex(t: String): UrlRegexRule {
            val p = t.split('\t')
            return UrlRegexRule(p[0], p.getOrNull(1)?.ifEmpty { null }, decodeCtx(p.getOrElse(2) { "" }))
        }

        private fun parseAnchoredRegex(t: String): AnchoredRegexRule {
            val p = t.split('\t')
            return AnchoredRegexRule(p[0], p.getOrNull(1)?.ifEmpty { null }, decodeCtx(p.getOrElse(2) { "" }))
        }

        private fun encodeCtx(ctx: RuleContext): String {
            val dom = ctx.pageDomains?.joinToString(",") ?: ""
            return "${if (ctx.thirdParty) 1 else 0};${if (ctx.important) 1 else 0};$dom"
        }

        private fun decodeCtx(s: String): RuleContext {
            val p = s.split(';')
            val doms = p.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(",")?.toSet()
            return RuleContext(p.getOrNull(0) == "1", doms, p.getOrNull(1) == "1")
        }
    }
}