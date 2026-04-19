package com.sbcfg.manager.util

/**
 * Strips likely credentials from arbitrary text (sing-box JSON configs,
 * logcat output) before we hand it to the user's share sheet.
 *
 * We can't afford to leak Hysteria2 passwords or config tokens — a diag
 * file could end up in a public channel. Masks JSON string values for
 * known-sensitive keys and opportunistically masks obvious secret-shaped
 * substrings (UUIDs, bearer tokens) found in free-form log lines.
 *
 * Philosophy: false positives (over-masking) are fine, false negatives
 * (leaking a real credential) are not. If an unknown key looks secret-ish,
 * err on masking.
 */
object CredentialMasker {

    private const val MASK = "\"***\""

    // JSON string values for these keys get replaced with "***".
    // Covers sing-box user/server credential shapes across outbound types
    // (hysteria2, naive, vmess, shadowsocks, tuic, etc.) plus our own
    // config token used by ConfigApiClient.
    private val SENSITIVE_KEYS = listOf(
        "password",
        "passwd",
        "auth",
        "secret",
        "token",
        "access_token",
        "uuid",
    )

    // Matches `"<key>": "<value>"` in JSON — captures key and value, replaces
    // value only. Permissive whitespace, doesn't require the match to be on
    // one line (DOTALL not needed because strings can't contain raw newlines
    // in JSON).
    private val jsonKeyValueRegex = run {
        val keys = SENSITIVE_KEYS.joinToString("|") { Regex.escape(it) }
        Regex(""""($keys)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""", RegexOption.IGNORE_CASE)
    }

    // Matches a URL user:pass@host form — scrubs the password portion.
    private val urlCredRegex =
        Regex("""([a-zA-Z][a-zA-Z0-9+.\-]*://[^:/@\s]+):([^@\s]+)@""")

    // Matches UUID v4 — typical VMess/TUIC ID shape. Aggressive: masks any
    // UUID we find in logs even if the key isn't known-sensitive.
    private val uuidRegex =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    // Bearer/basic auth header values in raw text (logcat, debug dumps).
    private val authHeaderRegex =
        Regex("""(?i)(authorization|x-auth[-\w]*)\s*[:=]\s*\S+""")

    fun mask(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        out = jsonKeyValueRegex.replace(out) { m ->
            """"${m.groupValues[1]}": $MASK"""
        }
        out = urlCredRegex.replace(out) { m -> "${m.groupValues[1]}:***@" }
        out = uuidRegex.replace(out, "***-uuid-***")
        out = authHeaderRegex.replace(out) { m -> "${m.groupValues[1]}: ***" }
        return out
    }
}
