package com.swift.browser.extensionengine

import java.io.File

/**
 * Conservative MV3 module bundler for static script bundling.
 *
 * Resolves package-local imports into a deterministic CommonJS-like bundle.
 */
object ExtensionModuleBundler {
    data class Result(val source: String, val entryPath: String, val modules: List<String>)

    private val importFrom = Regex(
        """\bimport\s+(.+?)\s+from\s+[\"']([^\"']+)[\"']\s*;?"""
    )
    private val sideEffectImport = Regex(
        """\bimport\s+[\"']([^\"']+)[\"']\s*;?"""
    )
    private val exportFrom = Regex(
        """\bexport\s+(?:\{([^}]*)\}|\*|\*\s+as\s+([A-Za-z_$][\w$]*))\s+from\s+[\"']([^\"']+)[\"']\s*;?"""
    )
    private val exportList = Regex(
        """\bexport\s*\{([^}]*)\}\s*;?"""
    )
    private val exportDecl = Regex(
        """\bexport\s+(?=(?:const|let|var|function|class)\b)"""
    )
    private val namedDecl = Regex(
        """\b(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)"""
    )

    fun build(root: File, entryPath: String, sourceOverride: String? = null): Result {
        val modules = LinkedHashMap<String, String>()
        val visiting = HashSet<String>()

        fun normalize(path: String): String {
            val parts = mutableListOf<String>()
            for (part in path.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else return ""
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }

        fun resolve(referrer: String, specifier: String): String? {
            val spec = specifier.trim()
            if (spec.startsWith("chrome-extension://") || spec.startsWith("swift-extension://")) {
                val slash = spec.indexOf('/', spec.indexOf("://") + 3)
                return if (slash >= 0) normalize(spec.substring(slash + 1)) else null
            }
            if (spec.startsWith("/")) return normalize(spec)
            if (!spec.startsWith(".")) return null
            val base = referrer.substringBeforeLast('/', "")
            val raw = if (base.isBlank()) spec else "$base/$spec"
            val normalized = normalize(raw)
            if (normalized.isBlank()) return null
            val candidates = buildList {
                add(normalized)
                if (!normalized.endsWith(".js") && !normalized.endsWith(".mjs")) {
                    add("$normalized.js")
                    add("$normalized.mjs")
                }
                add("$normalized/index.js")
                add("$normalized/index.mjs")
            }
            return candidates.firstOrNull { File(root, it).isFile }
        }

        fun readModule(path: String): String? {
            val file = File(root, path)
            if (!file.isFile) return null
            return try { file.readText() } catch (_: Throwable) { null }
        }

        fun collect(path: String, override: String? = null) {
            val key = normalize(path)
            if (key.isBlank() || modules.containsKey(key) || !visiting.add(key)) return
            val source = override ?: readModule(key) ?: return
            modules[key] = source

            importFrom.findAll(source).forEach { m -> resolve(key, m.groupValues[2])?.let { collect(it) } }
            sideEffectImport.findAll(source).forEach { m -> resolve(key, m.groupValues[1])?.let { collect(it) } }
            exportFrom.findAll(source).forEach { m -> resolve(key, m.groupValues[3])?.let { collect(it) } }
            visiting.remove(key)
        }

        val normalizedEntry = normalize(entryPath)
        collect(normalizedEntry, sourceOverride)
        if (!modules.containsKey(normalizedEntry)) {
            throw IllegalArgumentException("Extension module not found: $entryPath")
        }

        fun quote(value: String): String = org.json.JSONObject.quote(value)

        fun transform(key: String, source: String): String {
            var body = source
            val exportAssignments = mutableListOf<String>()

            body = importFrom.replace(body) { m ->
                val clause = m.groupValues[1].trim()
                val spec = m.groupValues[2]
                val dep = resolve(key, spec) ?: return@replace ""
                val req = "__require(${quote(dep)})"
                when {
                    clause.startsWith("*") -> {
                        val name = clause.substringAfter("as", "").trim()
                        "var $name = $req;"
                    }
                    clause.startsWith("{") -> {
                        val inside = clause.removePrefix("{").removeSuffix("}").trim()
                        val statements = inside.split(',').mapNotNull { item ->
                            val p = item.trim()
                            if (p.isBlank()) return@mapNotNull null
                            val parts = p.split(Regex("\\s+as\\s+"), limit = 2)
                            val from = parts[0].trim()
                            val local = parts.getOrNull(1)?.trim() ?: from
                            "var $local = $req.${from};"
                        }
                        statements.joinToString("\n")
                    }
                    else -> {
                        val defaultName = clause.substringBefore(',').trim()
                        val named = clause.substringAfter(',', "").trim()
                        buildString {
                            append("var ").append(defaultName).append(" = ").append(req).append(".default;")
                            if (named.startsWith("{")) {
                                val inside = named.removePrefix("{").removeSuffix("}").trim()
                                inside.split(',').filter { it.isNotBlank() }.forEach { item ->
                                    val parts = item.trim().split(Regex("\\s+as\\s+"), limit = 2)
                                    val from = parts[0].trim()
                                    val local = parts.getOrNull(1)?.trim() ?: from
                                    append("var ").append(local).append(" = ").append(req).append('.').append(from).append(';')
                                }
                            }
                        }
                    }
                }
            }

            body = sideEffectImport.replace(body) { m ->
                val dep = resolve(key, m.groupValues[1]) ?: return@replace ""
                "__require(${quote(dep)});"
            }

            body = exportFrom.replace(body) { m ->
                val named = m.groupValues[1]
                val namespace = m.groupValues[2]
                val dep = resolve(key, m.groupValues[3]) ?: return@replace ""
                if (namespace.isNotBlank()) {
                    "exports.${namespace} = __require(${quote(dep)});"
                } else {
                    named.split(',').filter { it.isNotBlank() }.joinToString("\n") { item ->
                        val parts = item.trim().split(Regex("\\s+as\\s+"), limit = 2)
                        val from = parts[0].trim()
                        val to = parts.getOrNull(1)?.trim() ?: from
                        "exports.${to} = __require(${quote(dep)}).${from};"
                    }
                }
            }

            body = body.replace(Regex("(?m)^\\s*export\\s+default\\s+function\\s+([A-Za-z_$][\\w$]*)")) { m ->
                exportAssignments += "exports.default = ${m.groupValues[1]};"
                "function ${m.groupValues[1]}"
            }
            body = body.replace(Regex("(?m)^\\s*export\\s+default\\s+class\\s+([A-Za-z_$][\\w$]*)")) { m ->
                exportAssignments += "exports.default = ${m.groupValues[1]};"
                "class ${m.groupValues[1]}"
            }
            body = body.replace(Regex("(?m)^\\s*export\\s+default\\s+([^;]+);?")) { m ->
                "exports.default = (${m.groupValues[1]});"
            }

            body = exportDecl.replace(body, "")
            // Capture simple exported declarations before the export list is removed.
            val names = namedDecl.findAll(source.replace(exportDecl, ""))
                .map { it.groupValues[1] }
                .filter { name -> Regex("\\bexport\\s+(?:const|let|var|function|class)\\s+$name\\b").containsMatchIn(source) }
                .toList()
            names.forEach { exportAssignments += "exports.$it = $it;" }

            body = exportList.replace(body) { m ->
                m.groupValues[1].split(',').filter { it.isNotBlank() }.joinToString("\n") { item ->
                    val parts = item.trim().split(Regex("\\s+as\\s+"), limit = 2)
                    val from = parts[0].trim()
                    val to = parts.getOrNull(1)?.trim() ?: from
                    "exports.$to = $from;"
                }
            }

            if (exportAssignments.isNotEmpty()) body += "\n" + exportAssignments.joinToString("\n")
            return body
        }

        val out = StringBuilder()
        out.append("(function(){\n")
        out.append("var __mods = Object.create(null), __cache = Object.create(null);\n")
        out.append("function __define(id,f){__mods[id]=f;}\n")
        out.append("function __require(id){if(__cache[id])return __cache[id].exports;var f=__mods[id];if(!f)throw new Error('Module not found: '+id);var m={exports:{}};__cache[id]=m;f(m,m.exports,__require);return m.exports;}\n")
        for ((key, source) in modules) {
            out.append("__define(").append(quote(key)).append(", function(module,exports,__require){\n")
            out.append(transform(key, source)).append("\n});\n")
        }
        out.append("return __require(").append(quote(normalizedEntry)).append(");\n")
        out.append("})();")
        return Result(out.toString(), normalizedEntry, modules.keys.toList())
    }
}
