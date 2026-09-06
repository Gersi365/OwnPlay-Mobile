package app.ownplay.player.testing

import java.io.File

internal fun sourceText(relativePath: String): String {
    val candidates = listOf(
        File(relativePath),
        File("app/$relativePath"),
    )
    return candidates.firstOrNull(File::isFile)?.readText()
        ?: error("Could not locate source file: $relativePath")
}

internal fun normalizedSource(source: String): String =
    source.replace(Regex("\\s+"), " ").trim()

/** Extracts a brace-delimited declaration/lambda body following [anchor]. */
internal fun sourceBlockAfter(source: String, anchor: String): String {
    val delimiter = declarationBodyDelimiter(source, anchor)
    require(delimiter.kind == BodyKind.BLOCK) {
        "Source anchor has an expression body, not a brace body: $anchor"
    }
    val closingBrace = findClosingDelimiter(source, delimiter.index, '{', '}')
    return source.substring(delimiter.index + 1, closingBrace)
}

/** Extracts an expression-bodied declaration following [anchor]. */
internal fun sourceExpressionAfter(source: String, anchor: String): String {
    val delimiter = declarationBodyDelimiter(source, anchor)
    require(delimiter.kind == BodyKind.EXPRESSION) {
        "Source anchor has a brace body, not an expression body: $anchor"
    }
    val start = delimiter.index + 1
    val end = findExpressionEnd(source, start)
    return source.substring(start, end).trim()
}

private fun declarationBodyDelimiter(source: String, anchor: String): BodyDelimiter {
    val anchorIndex = source.indexOf(anchor)
    require(anchorIndex >= 0) { "Could not locate source anchor: $anchor" }

    var index = anchorIndex + anchor.length
    var parenDepth = unmatchedDepth(anchor, '(', ')')
    var bracketDepth = unmatchedDepth(anchor, '[', ']')
    var mode = ScanMode.CODE
    var blockCommentDepth = 0

    while (index < source.length) {
        val current = source[index]
        val next = source.getOrNull(index + 1)

        when (mode) {
            ScanMode.CODE -> when {
                current == '/' && next == '/' -> {
                    mode = ScanMode.LINE_COMMENT
                    index += 2
                    continue
                }
                current == '/' && next == '*' -> {
                    mode = ScanMode.BLOCK_COMMENT
                    blockCommentDepth = 1
                    index += 2
                    continue
                }
                current == '"' && source.startsWith("\"\"\"", index) -> {
                    mode = ScanMode.TRIPLE_STRING
                    index += 3
                    continue
                }
                current == '"' -> mode = ScanMode.STRING
                current == '\'' -> mode = ScanMode.CHAR
                current == '(' -> parenDepth += 1
                current == ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                current == '[' -> bracketDepth += 1
                current == ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                parenDepth == 0 && bracketDepth == 0 && current == '{' ->
                    return BodyDelimiter(BodyKind.BLOCK, index)
                parenDepth == 0 && bracketDepth == 0 && current == '=' && isBodyEquals(source, index) ->
                    return BodyDelimiter(BodyKind.EXPRESSION, index)
            }
            ScanMode.STRING -> when {
                current == '\\' -> {
                    index += 2
                    continue
                }
                current == '"' -> mode = ScanMode.CODE
            }
            ScanMode.CHAR -> when {
                current == '\\' -> {
                    index += 2
                    continue
                }
                current == '\'' -> mode = ScanMode.CODE
            }
            ScanMode.TRIPLE_STRING -> if (source.startsWith("\"\"\"", index)) {
                mode = ScanMode.CODE
                index += 3
                continue
            }
            ScanMode.LINE_COMMENT -> if (current == '\n') mode = ScanMode.CODE
            ScanMode.BLOCK_COMMENT -> when {
                current == '/' && next == '*' -> {
                    blockCommentDepth += 1
                    index += 2
                    continue
                }
                current == '*' && next == '/' -> {
                    blockCommentDepth -= 1
                    index += 2
                    if (blockCommentDepth == 0) mode = ScanMode.CODE
                    continue
                }
            }
        }
        index += 1
    }

    error("Could not locate declaration body after source anchor: $anchor")
}

private fun findExpressionEnd(source: String, start: Int): Int {
    var index = start
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var mode = ScanMode.CODE
    var blockCommentDepth = 0

    while (index < source.length) {
        val current = source[index]
        val next = source.getOrNull(index + 1)

        when (mode) {
            ScanMode.CODE -> when {
                current == '/' && next == '/' -> {
                    mode = ScanMode.LINE_COMMENT
                    index += 2
                    continue
                }
                current == '/' && next == '*' -> {
                    mode = ScanMode.BLOCK_COMMENT
                    blockCommentDepth = 1
                    index += 2
                    continue
                }
                current == '"' && source.startsWith("\"\"\"", index) -> {
                    mode = ScanMode.TRIPLE_STRING
                    index += 3
                    continue
                }
                current == '"' -> mode = ScanMode.STRING
                current == '\'' -> mode = ScanMode.CHAR
                current == '(' -> parenDepth += 1
                current == ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                current == '[' -> bracketDepth += 1
                current == ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                current == '{' -> braceDepth += 1
                current == '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                current == '\n' && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 -> {
                    val nextNonWhitespace = source.indexOfFirstFrom(index + 1) { !it.isWhitespace() }
                    if (nextNonWhitespace < 0) return index
                    val gap = source.substring(index + 1, nextNonWhitespace)
                    if ('\n' in gap) return index
                }
            }
            ScanMode.STRING -> when {
                current == '\\' -> {
                    index += 2
                    continue
                }
                current == '"' -> mode = ScanMode.CODE
            }
            ScanMode.CHAR -> when {
                current == '\\' -> {
                    index += 2
                    continue
                }
                current == '\'' -> mode = ScanMode.CODE
            }
            ScanMode.TRIPLE_STRING -> if (source.startsWith("\"\"\"", index)) {
                mode = ScanMode.CODE
                index += 3
                continue
            }
            ScanMode.LINE_COMMENT -> if (current == '\n') mode = ScanMode.CODE
            ScanMode.BLOCK_COMMENT -> when {
                current == '/' && next == '*' -> {
                    blockCommentDepth += 1
                    index += 2
                    continue
                }
                current == '*' && next == '/' -> {
                    blockCommentDepth -= 1
                    index += 2
                    if (blockCommentDepth == 0) mode = ScanMode.CODE
                    continue
                }
            }
        }
        index += 1
    }
    return source.length
}

private fun findClosingDelimiter(
    source: String,
    openingIndex: Int,
    opening: Char,
    closing: Char,
): Int {
    var depth = 0
    var index = openingIndex
    var mode = ScanMode.CODE
    var blockCommentDepth = 0

    while (index < source.length) {
        val current = source[index]
        val next = source.getOrNull(index + 1)

        when (mode) {
            ScanMode.CODE -> when {
                current == '/' && next == '/' -> {
                    mode = ScanMode.LINE_COMMENT
                    index += 2
                    continue
                }
                current == '/' && next == '*' -> {
                    mode = ScanMode.BLOCK_COMMENT
                    blockCommentDepth = 1
                    index += 2
                    continue
                }
                current == '"' && source.startsWith("\"\"\"", index) -> {
                    mode = ScanMode.TRIPLE_STRING
                    index += 3
                    continue
                }
                current == '"' -> mode = ScanMode.STRING
                current == '\'' -> mode = ScanMode.CHAR
                current == opening -> depth += 1
                current == closing -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            ScanMode.STRING -> when {
                current == '\\' -> {
                    index += 2
                    continue
                }
                current == '"' -> mode = ScanMode.CODE
            }
            ScanMode.CHAR -> when {
                current == '\\' -> {
                    index += 2
                    continue
                }
                current == '\'' -> mode = ScanMode.CODE
            }
            ScanMode.TRIPLE_STRING -> if (source.startsWith("\"\"\"", index)) {
                mode = ScanMode.CODE
                index += 3
                continue
            }
            ScanMode.LINE_COMMENT -> if (current == '\n') mode = ScanMode.CODE
            ScanMode.BLOCK_COMMENT -> when {
                current == '/' && next == '*' -> {
                    blockCommentDepth += 1
                    index += 2
                    continue
                }
                current == '*' && next == '/' -> {
                    blockCommentDepth -= 1
                    index += 2
                    if (blockCommentDepth == 0) mode = ScanMode.CODE
                    continue
                }
            }
        }
        index += 1
    }

    error("Unbalanced source block starting at index $openingIndex")
}

private fun unmatchedDepth(text: String, opening: Char, closing: Char): Int =
    (text.count { it == opening } - text.count { it == closing }).coerceAtLeast(0)

private fun isBodyEquals(source: String, index: Int): Boolean {
    val previous = source.getOrNull(index - 1)
    val next = source.getOrNull(index + 1)
    val previousIsOperator = previous != null && previous in setOf('=', '!', '<', '>')
    return !previousIsOperator && next != '=' && next != '>'
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (index in start until length) {
        if (predicate(this[index])) return index
    }
    return -1
}

private data class BodyDelimiter(
    val kind: BodyKind,
    val index: Int,
)

private enum class BodyKind { BLOCK, EXPRESSION }

private enum class ScanMode {
    CODE,
    STRING,
    CHAR,
    TRIPLE_STRING,
    LINE_COMMENT,
    BLOCK_COMMENT,
}
