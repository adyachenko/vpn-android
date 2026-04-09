package com.sbcfg.manager.util

object DomainValidator {
    fun isValid(domain: String): Boolean {
        if (domain.isBlank()) return false
        if (domain.contains(" ")) return false
        if (domain.contains("://")) return false

        val cleaned = domain.removePrefix("*.")

        // IP address check
        val ipRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        if (ipRegex.matches(cleaned)) return true

        // Domain check: at least one dot, valid characters
        if (!cleaned.contains(".")) return false

        return cleaned.split(".").all { part ->
            part.isNotEmpty() && part.length <= 63
        }
    }
}
