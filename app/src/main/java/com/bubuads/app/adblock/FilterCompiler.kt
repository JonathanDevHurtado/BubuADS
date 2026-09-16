package com.bubuads.app.adblock

/**
 * Convierte las lineas de las listas (sintaxis Adblock Plus / uBlock Origin)
 * en estructuras compiladas listas para matchear ([NetworkRules] y CSS).
 *
 * Sintaxis soportada:
 *  - ||host^ , ||host/path…            -> bloqueo por host (y dominio padre)
 *  - /regex/                           -> regex con literal requerido
 *  - |ancla| , |…, …, | , *comodin*    -> subcadenas / anclas
 *  - palabra suelta                     -> substring
 *  - @@…                               -> excepcion
 *  - $domain=…, $from=…, $third-party/3p, $important, $document, $denyallow
 * Se descartan: reglas `*` incondicionales y patrones que compilarian a `.*`.
 */
object FilterCompiler {

    /** Resultado de una compilacion completa (red + cosmetico). */
    class Compiled(val network: NetworkRules, val cosmetic: String)

    /**
     * Compila red + cosmetico en **una sola pasada y en streaming**: `feed`
     * entrega cada linea de las listas y aqui se procesa al vuelo, sin guardar
     * nunca la lista completa en memoria. Reduce mucho el pico de memoria (la
     * causa del `OutOfMemoryError` al compilar las ~4 MB de listas).
     */
    fun compile(feed: ((String) -> Unit) -> Unit): Compiled {
        val rules = NetworkRules()
        val blocks = LinkedHashSet<String>()
        val exceptions = LinkedHashSet<String>()
        feed { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith('!') && !t.startsWith('[')) {
                val ex = t.startsWith("#@#")
                val marker = if (ex) "#@#" else "##"
                val i = t.indexOf(marker)
                if (i >= 0) {
                    val domain = t.substring(0, i).trim()
                    val sel = t.substring(i + marker.length).trim()
                    if (sel.isNotEmpty() && cosmeticApplies(domain, sel)) {
                        if (ex) exceptions.add(sel) else blocks.add(sel)
                    }
                } else {
                    parseRule(t, rules)
                }
            }
        }
        rules.rebuildMatchers()
        val sb = StringBuilder(blocks.sumOf { it.length } + exceptions.sumOf { it.length } + 1024)
        for (b in blocks) sb.append(b).append("{display:none!important}")
        for (x in exceptions) sb.append(x).append("{display:block!important;visibility:visible!important}")
        return Compiled(rules, sb.toString())
    }

    /** Compila las reglas de RED de las listas dadas. */
    fun compileNetwork(lines: List<String>): NetworkRules {
        val rules = NetworkRules()
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith('!') || t.startsWith('[')) continue
            parseRule(t, rules)
        }
        rules.rebuildMatchers()
        return rules
    }

    /** Compila las reglas COSMETICAS (##selector, #@#selector) a CSS. Deduplica. */
    fun compileCosmetic(lines: List<String>): String {
        val blocks = LinkedHashSet<String>()
        val exceptions = LinkedHashSet<String>()
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith('!')) continue
            val e = t.startsWith("#@#")
            val marker = if (e) "#@#" else "##"
            val i = t.indexOf(marker)
            if (i < 0) continue
            val domain = t.substring(0, i).trim()
            val sel = t.substring(i + marker.length).trim()
            if (sel.isEmpty()) continue
            if (!cosmeticApplies(domain, sel)) continue
            if (e) exceptions.add(sel) else blocks.add(sel)
        }
        val sb = StringBuilder(blocks.sumOf { it.length } + exceptions.sumOf { it.length } + 1024)
        for (b in blocks) sb.append(b).append("{display:none!important}")
        for (x in exceptions) sb.append(x).append("{display:block!important;visibility:visible!important}")
        return sb.toString()
    }

    /**
     * La app es solo-YouTube: se ignoran las reglas cosmeticas de otros sitios
     * (mas de 1 MB de CSS inutil que ralentiza la carga). Se conservan las
     * acotadas a youtube.com y las genericas que atacan al reproductor.
     * Se descartan los scriptlets procedurales `+js(...)` (NO son CSS).
     */
    private fun cosmeticApplies(domain: String, sel: String): Boolean {
        if (sel.startsWith("+js(")) return false
        if (domain.isNotEmpty()) {
            val d = domain.removeSuffix("#")
            return d == "youtube.com" || d == "m.youtube.com" || d == "www.youtube.com" ||
                d.endsWith(".youtube.com")
        }
        return sel.contains(".ytp-ad") || sel.contains("#player-ads") ||
            sel.contains("#masthead-ad") || sel.contains(".ytp-paid-content") ||
            sel.contains(".ytp-ce-element") || sel.contains("ytd-") ||
            sel.contains("ytm-") || sel.contains("ad-slot-renderer")
    }

    private fun parseRule(t: String, rules: NetworkRules) {
        val excl = t.startsWith("@@")
        val imp = !excl && t.contains("\$important")
        val rest = if (excl) t.removePrefix("@@") else t

        val dollar = rest.lastIndexOf('$')
        val opts: String? = if (dollar > 0 && rest.indexOf('|') < dollar) rest.substring(dollar + 1) else null
        val pat = if (opts != null) rest.substring(0, dollar) else rest

        // Parse options once to avoid repeated split() allocations (OOM fix)
        var pageDoms: Set<String>? = null
        var thirdParty = false
        var isDocument = false
        var isDenyallow = false
        var isDiscardable = false // replace=, csp=, removeparam, badfilter

        if (opts != null) {
            val domains = mutableSetOf<String>()
            var pos = 0
            while (pos < opts.length) {
                val nextComma = opts.indexOf(',', pos)
                val opt = if (nextComma < 0) opts.substring(pos).trim() else opts.substring(pos, nextComma).trim()
                pos = if (nextComma < 0) opts.length else nextComma + 1
                if (opt.isEmpty()) continue
                when {
                    opt.startsWith("domain=") -> {
                        val raw = opt.removePrefix("domain=")
                        var dPos = 0
                        while (dPos < raw.length) {
                            val dNext = raw.indexOf('|', dPos)
                            val d = if (dNext < 0) raw.substring(dPos).trim() else raw.substring(dPos, dNext).trim()
                            dPos = if (dNext < 0) raw.length else dNext + 1
                            if (d.isNotEmpty()) domains.add(d)
                        }
                    }
                    opt.startsWith("from=") -> {
                        val raw = opt.removePrefix("from=")
                        var dPos = 0
                        while (dPos < raw.length) {
                            val dNext = raw.indexOf('|', dPos)
                            val d = if (dNext < 0) raw.substring(dPos).trim() else raw.substring(dPos, dNext).trim()
                            dPos = if (dNext < 0) raw.length else dNext + 1
                            if (d.isNotEmpty()) domains.add(d)
                        }
                    }
                    opt == "third-party" || opt == "3p" -> thirdParty = true
                    opt == "document" -> isDocument = true
                    opt.startsWith("denyallow") -> isDenyallow = true
                    opt.startsWith("replace=") || opt.startsWith("csp=") ||
                        opt == "removeparam" || opt == "badfilter" -> isDiscardable = true
                }
            }
            pageDoms = domains.takeIf { it.isNotEmpty() }
        }

        if (isDenyallow || isDiscardable) return

        if (isDocument) {
            if (excl && pageDoms != null && pat != "*") {
                rules.pageExceptions.addAll(pageDoms)
            }
            return
        }

        if (pat == "*") {
            if (pageDoms != null) {
                for (d in pageDoms) {
                    if (excl) {
                        if (imp) rules.importantCtxHosts.add(d to RuleContext(thirdParty, pageDoms, true))
                        else rules.ctxHostExceptions.add(d to RuleContext(thirdParty, pageDoms, false))
                    } else {
                        if (imp) rules.importantCtxHosts.add(d to RuleContext(thirdParty, pageDoms, true))
                        else rules.ctxHostBlocks.add(d to RuleContext(thirdParty, pageDoms, false))
                    }
                }
            }
            return
        }

        when {
            pat.startsWith("||") -> parseHostRule(pat, excl, imp, pageDoms, thirdParty, rules)
            pat.startsWith("/") && pat.endsWith("/") && pat.length > 2 -> {
                val body = pat.substring(1, pat.length - 1)
                if (body.isEmpty() || body == ".*") return
                val pattern = try {
                    java.util.regex.Pattern.compile(body)
                } catch (e: OutOfMemoryError) {
                    return
                } catch (e: java.util.regex.PatternSyntaxException) {
                    return
                }
                val anchor = literalAnchor(body)
                val ctx = RuleContext(thirdParty, pageDoms, imp)
                if (excl) rules.regexExceptions.add(AnchoredRegexRule(pattern.toString(), anchor, ctx))
                else rules.regexBlocks.add(AnchoredRegexRule(pattern.toString(), anchor, ctx))
            }
            pat.contains("*") -> {
                val regex = "^" + escapeRegex(pat).replace("\\*", ".*") + "$"
                val anchor = literalOf(regex)
                val ctx = RuleContext(thirdParty, pageDoms, imp)
                if (excl) rules.urlRegexExceptions.add(UrlRegexRule(regex, anchor, ctx))
                else rules.urlRegexBlocks.add(UrlRegexRule(regex, anchor, ctx))
            }
            pat.startsWith("|") && pat.endsWith("|") && pat.length > 2 -> {
                val body = pat.substring(1, pat.length - 1)
                if (body.isEmpty()) return
                val regex = "^" + escapeRegex(body) + "$"
                val anchor = literalOf(regex)
                val ctx = RuleContext(thirdParty, pageDoms, imp)
                if (excl) rules.urlRegexExceptions.add(UrlRegexRule(regex, anchor, ctx))
                else rules.urlRegexBlocks.add(UrlRegexRule(regex, anchor, ctx))
            }
            pat.startsWith("|") -> {
                val body = pat.substring(1)
                if (body.isEmpty()) return
                val regex = "^" + escapeRegex(body)
                val anchor = literalOf(regex)
                val ctx = RuleContext(thirdParty, pageDoms, imp)
                if (excl) rules.urlRegexExceptions.add(UrlRegexRule(regex, anchor, ctx))
                else rules.urlRegexBlocks.add(UrlRegexRule(regex, anchor, ctx))
            }
            pat.endsWith("|") -> {
                val body = pat.substring(0, pat.length - 1)
                if (body.isEmpty()) return
                val regex = escapeRegex(body) + "$"
                val anchor = literalOf(regex)
                val ctx = RuleContext(thirdParty, pageDoms, imp)
                if (excl) rules.urlRegexExceptions.add(UrlRegexRule(regex, anchor, ctx))
                else rules.urlRegexBlocks.add(UrlRegexRule(regex, anchor, ctx))
            }
            else -> {
                if (excl) rules.substringExceptions.add(pat)
                else rules.substrings.add(pat)
            }
        }
    }

    private fun parseHostRule(pat: String, excl: Boolean, imp: Boolean, pageDoms: Set<String>?, tp: Boolean, rules: NetworkRules) {
        var p = pat.removePrefix("||")
        val anchorEnd = p.endsWith("^")
        if (anchorEnd) p = p.dropLast(1)
        val slash = p.indexOf('/')
        var host = if (slash >= 0) p.substring(0, slash) else p
        val path = if (slash >= 0) p.substring(slash) else ""
        if (host.endsWith(".")) host = host.dropLast(1)
        if (host.isEmpty()) return
        val ctx = RuleContext(tp, pageDoms, imp)

        if (path.isEmpty()) {
            if (excl) {
                if (imp) rules.importantCtxHosts.add(host to ctx)
                else if (pageDoms != null || tp) rules.ctxHostExceptions.add(host to ctx)
                else rules.exceptionHosts.add(host)
            } else {
                if (imp) rules.importantCtxHosts.add(host to ctx)
                else if (pageDoms != null || tp) rules.ctxHostBlocks.add(host to ctx)
                else rules.blockedHosts.add(host)
            }
            return
        }

        val pathRegex = buildString {
            for (c in path.drop(1)) {
                if (c == '*') append(".*") else append(escapeChar(c))
            }
        }
        val pattern: java.util.regex.Pattern = try {
            java.util.regex.Pattern.compile(
                "^https?://[a-z0-9.-]*" + escapeRegex(host) + "/" + pathRegex +
                    if (anchorEnd) "(?![a-z0-9])" else ""
            )
        } catch (e: OutOfMemoryError) {
            return
        } catch (e: java.util.regex.PatternSyntaxException) {
            return
        }
        val r = PathRule(pattern.toString(), ctx)
        if (excl) {
            if (imp) rules.importantHostPaths.getOrPut(host) { mutableListOf() }.add(r)
            else rules.hostPathExceptions.getOrPut(host) { mutableListOf() }.add(r)
        } else {
            if (imp) rules.importantHostPaths.getOrPut(host) { mutableListOf() }.add(r)
            else rules.hostPathBlocks.getOrPut(host) { mutableListOf() }.add(r)
        }
    }

    /** Extrae un literal de la regex para usarlo como pre-filtro barato. */
    private fun literalAnchor(body: String): String? {
        val m = Regex("\\^?([a-zA-Z0-9./_-]{8,})\\^?").find(body) ?: return null
        return m.groupValues[1]
    }

    /** Primer fragmento alfanumerico literal (>=4) de una regex: debe estar en la URL si la regla aplica. */
    private fun literalOf(regex: String): String? {
        val m = Regex("[a-zA-Z0-9]{4,}").find(regex) ?: return null
        return m.value
    }

    private fun escapeChar(c: Char): String {
        if (c in "\\^$.*|?+()[]{}-") return "\\$c"
        return c.toString()
    }

    private fun escapeRegex(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) sb.append(escapeChar(c))
        return sb.toString()
    }
}