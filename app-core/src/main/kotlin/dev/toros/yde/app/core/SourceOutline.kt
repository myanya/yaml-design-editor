package dev.toros.yde.app.core

/**
 * ソースの宣言位置の軽量な抽出。型解決・継承・呼出関係は推測しない。
 * コメントと文字列を伏せてから宣言を読むため、文字列に書かれたサンプルを候補にしない。
 */
object SourceOutline {
    data class Symbol(val kind: String, val name: String, val line: Int, val owner: String?)
    data class Outline(val language: String, val symbols: List<Symbol>)
    fun extract(name: String, text: String): Outline {
        val extension = name.substringAfterLast('.', "").lowercase()
        val language = when (extension) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js", "jsx", "ts", "tsx" -> "JavaScript / TypeScript"
            else -> return Outline("未対応", emptyList())
        }
        val clean = mask(text, extension == "py")
        val symbols = mutableListOf<Symbol>()
        val owners = ArrayDeque<Triple<String, Int, Int>>()
        var depth = 0
        val classes = Regex("\\b(class|interface|object|enum|record)\\s+(?:class\\s+)?([A-Za-z_$][\\w$]*)")
        val kotlinFunction = Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w<>?.]+\\.)?([A-Za-z_$][\\w$]*)\\s*\\(")
        val pythonFunction = Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(")
        val jsFunction = Regex("\\bfunction\\s+([A-Za-z_$][\\w$]*)\\s*\\(|\\b(?:const|let)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[\\w$]+)\\s*=>")
        val method = Regex("^\\s*(?:(?:public|private|protected|static|final|abstract|async|override|synchronized|native|default)\\s+)*(?:[\\w<>,?\\[\\].]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)\\s*(?::[^={]+)?(?:throws\\s+[^{}]+)?[;{]")
        clean.lines().forEachIndexed { index, line ->
            val indent = line.takeWhile(Char::isWhitespace).length
            if (line.isNotBlank()) while (owners.isNotEmpty() &&
                (if (extension == "py") indent <= owners.last().third else depth < owners.last().second)) owners.removeLast()
            val declared = classes.find(line)
            if (declared != null) {
                val value = declared.groupValues[2]
                symbols += Symbol(declared.groupValues[1], value, index + 1, owners.lastOrNull()?.first)
                if (extension == "py" || '{' in line) owners.add(Triple(value, depth + 1, indent))
            } else {
                val found = when (extension) {
                    "kt", "kts" -> kotlinFunction.find(line)?.groupValues?.get(1)
                    "py" -> pythonFunction.find(line)?.groupValues?.get(1)
                    "java" -> if (owners.isNotEmpty() && depth == owners.last().second) method.find(line)?.groupValues?.get(1) else null
                    else -> jsFunction.find(line)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
                        ?: if (owners.isNotEmpty() && depth == owners.last().second) method.find(line)?.groupValues?.get(1) else null
                }
                if (found != null && found !in setOf("if", "for", "while", "switch", "catch", "return", "new", "super", "this"))
                    symbols += Symbol(if (owners.isEmpty()) "function" else "method", found, index + 1, owners.lastOrNull()?.first)
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
        }
        return Outline(language, symbols)
    }

    private fun mask(text: String, python: Boolean): String {
        val out = text.toCharArray()
        var i = 0; var block = false; var line = false; var quote: Char? = null; var triple = false
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') { line = false; i++; continue }
            when {
                line -> out[i] = ' '
                block -> {
                    out[i] = ' '
                    if (text.startsWith("*/", i)) { out[i + 1] = ' '; i++; block = false }
                }
                quote != null -> {
                    out[i] = ' '
                    if (c == '\\' && i + 1 < text.length) { if (text[i + 1] != '\n') out[i + 1] = ' '; i++ }
                    else if (c == quote) {
                        if (!triple) quote = null
                        else if (text.startsWith("$c$c$c", i)) { out[i + 1] = ' '; out[i + 2] = ' '; i += 2; quote = null; triple = false }
                    }
                }
                (python && c == '#') || (!python && text.startsWith("//", i)) -> { line = true; out[i] = ' ' }
                !python && text.startsWith("/*", i) -> { block = true; out[i] = ' '; out[i + 1] = ' '; i++ }
                c == '"' || c == '\'' || c == '`' -> {
                    quote = c; out[i] = ' '
                    if (text.startsWith("$c$c$c", i)) { triple = true; out[i + 1] = ' '; out[i + 2] = ' '; i += 2 }
                }
            }
            i++
        }
        return out.concatToString()
    }
}
