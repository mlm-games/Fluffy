package app.fluffy.ui.viewers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

private const val HIGHLIGHT_LIMIT = 200_000

enum class EditorLanguage(val label: String, val keywords: Set<String>) {
    PLAIN("", emptySet()),
    CLike(
        "C-like",
        setOf(
            "abstract", "as", "async", "await", "base", "bool", "break", "case", "catch", "char",
            "class", "const", "constexpr", "continue", "decltype", "default", "defer", "delete",
            "do", "double", "else", "enum", "explicit", "export", "extends", "extern", "final",
            "finally", "float", "for", "from", "func", "fun", "function", "get", "goto", "if",
            "implements", "import", "in", "init", "inline", "instanceof", "int", "interface",
            "internal", "is", "lambda", "let", "long", "mut", "namespace", "native", "new",
            "null", "nullptr", "object", "operator", "override", "package", "private", "protected",
            "public", "readonly", "return", "sealed", "set", "short", "signed", "static", "struct",
            "super", "switch", "synchronized", "template", "this", "throw", "throws", "true",
            "false", "try", "typealias", "typeof", "union", "unsigned", "using", "val", "var",
            "virtual", "void", "volatile", "when", "where", "while", "with", "yield"
        )
    ),
    SHELL(
        "Shell",
        setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
            "esac", "function", "return", "break", "continue", "local", "export", "readonly",
            "declare", "source", "alias", "unset", "shift", "trap", "exit", "set"
        )
    ),
    PYTHON(
        "Python",
        setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
            "True", "try", "while", "with", "yield", "match", "case"
        )
    ),
    JSON("JSON", setOf("true", "false", "null")),
    MARKUP("HTML/XML", emptySet()),
    YAML("YAML", setOf("true", "false", "null", "yes", "no", "on", "off")),
    SQL("SQL", setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
        "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AS", "AND", "OR", "NOT",
        "NULL", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DISTINCT", "COUNT", "SUM", "AVG"
    )),
    INI("INI", emptySet()),
    MARKDOWN("Markdown", emptySet());

    companion object {
        fun forName(name: String): EditorLanguage {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "c", "h", "cpp", "hpp", "cc", "hh", "java", "kt", "kts", "js", "mjs", "cjs", "ts",
                "tsx", "jsx", "cs", "go", "rs", "swift", "php", "scala", "dart", "groovy",
                "gradle" -> CLike
                "sh", "bash", "zsh", "fish", "ksh" -> SHELL
                "py", "pyw" -> PYTHON
                "json" -> JSON
                "html", "htm", "xml", "svg", "xhtml", "vue" -> MARKUP
                "yml", "yaml" -> YAML
                "sql" -> SQL
                "ini", "cfg", "conf", "properties", "env", "editorconfig" -> INI
                "md", "markdown" -> MARKDOWN
                else -> PLAIN
            }
        }
    }
}

private fun commentPattern(language: EditorLanguage): String = when (language) {
    EditorLanguage.CLike -> "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
    EditorLanguage.SHELL, EditorLanguage.PYTHON, EditorLanguage.YAML -> "#[^\\n]*"
    EditorLanguage.MARKUP -> "<!--[\\s\\S]*?-->"
    EditorLanguage.SQL -> "--[^\\n]*"
    EditorLanguage.INI -> "[#;][^\\n]*"
    EditorLanguage.MARKDOWN -> "#[^\\n]*"
    EditorLanguage.JSON -> "$^"
    EditorLanguage.PLAIN -> "$^"
}

private fun stringPattern(language: EditorLanguage): String = when (language) {
    EditorLanguage.MARKUP -> "\"[^\"]*\"|'[^']*'"
    else -> "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
}

private fun blockStart(language: EditorLanguage): String = when (language) {
    EditorLanguage.MARKDOWN -> "(^#{1,6}[^\\n]*)"
    EditorLanguage.INI -> "(\\[[^\\]\\n]+\\])"
    else -> "$^"
}

private val patterns: Map<EditorLanguage, Regex> by lazy {
    EditorLanguage.entries.associateWith { language ->
        Regex(
            buildString {
                append('(').append(commentPattern(language)).append(')')
                append("|(").append(stringPattern(language)).append(')')
                append("|(\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b)")
                append('|').append(blockStart(language))
                append("|([A-Za-z_$][A-Za-z0-9_$]*)")
            }
        )
    }
}

class EditorHighlighting(
    private val language: EditorLanguage,
    private val keyword: Color,
    private val string: Color,
    private val comment: Color,
    private val number: Color,
    private val heading: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        if (language == EditorLanguage.PLAIN || source.isEmpty() || source.length > HIGHLIGHT_LIMIT) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(text)
        patterns.getValue(language).findAll(source).forEach { match ->
            when {
                match.groups[1] != null ->
                    builder.addStyle(SpanStyle(comment, fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
                match.groups[2] != null ->
                    builder.addStyle(SpanStyle(string), match.range.first, match.range.last + 1)
                match.groups[3] != null ->
                    builder.addStyle(SpanStyle(number), match.range.first, match.range.last + 1)
                match.groups[4] != null ->
                    builder.addStyle(SpanStyle(heading, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
                match.groups[5] != null -> {
                    if (source.substring(match.range.first, match.range.last + 1) in language.keywords) {
                        builder.addStyle(SpanStyle(keyword, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
                    }
                }
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}