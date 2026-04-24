package com.script

class ScriptException : Exception {
    val columnNumber: Int
    val fileName: String?
    val lineNumber: Int

    constructor(message: String?) : super(message) {
        fileName = null
        lineNumber = -1
        columnNumber = -1
    }

    constructor(cause: Exception?) : super(cause) {
        fileName = null
        lineNumber = -1
        columnNumber = -1
    }

    constructor(message: String?, fileName: String?, lineNumber: Int) : super(message) {
        this.fileName = fileName
        this.lineNumber = lineNumber
        columnNumber = -1
    }

    constructor(
        message: String?,
        fileName: String?,
        lineNumber: Int,
        columnNumber: Int
    ) : super(message) {
        this.fileName = fileName
        this.lineNumber = lineNumber
        this.columnNumber = columnNumber
    }

    override val message: String?
        get() {
            val base = super.message
            val location = formatLocation()
            return when {
                base == null -> location
                location == null -> base
                else -> "$base $location"
            }
        }

    private fun formatLocation(): String? {
        val parts = ArrayList<String>(3)
        fileName?.let { parts += "in $it" }
        if (lineNumber >= 0) parts += "at line number $lineNumber"
        if (columnNumber >= 0) parts += "at column number $columnNumber"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
