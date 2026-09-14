package ro.andi.phonebarriers

object CsvUtils {

    // Safely wraps fields containing commas, quotes, or newlines in double quotes
    fun escapeCsvField(field: String): String {
        val containsComma = field.contains(",")
        val containsQuote = field.contains("\"")
        val containsNewline = field.contains("\n")

        if (containsComma || containsQuote || containsNewline) {
            // If the string contains a quote, we must escape it with a double-quote ""
            val escapedQuotes = field.replace("\"", "\"\"")
            return "\"$escapedQuotes\""
        }
        return field
    }

    // Safely splits a CSV line, ignoring commas inside double quotes
    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false

        var i = 0
        while (i < line.length) {
            val char = line[i]

            if (char == '\"') {
                // Check if it's an escaped quote ("") inside a quoted string
                if (inQuotes && i + 1 < line.length && line[i + 1] == '\"') {
                    currentField.append('\"')
                    i++ // Skip the second quote
                } else {
                    inQuotes = !inQuotes // Toggle quote state
                }
            } else if (char == ',' && !inQuotes) {
                // Comma outside of quotes means end of field
                result.add(currentField.toString())
                currentField.clear()
            } else {
                currentField.append(char)
            }
            i++
        }
        // Add the final field
        result.add(currentField.toString())
        return result
    }
}