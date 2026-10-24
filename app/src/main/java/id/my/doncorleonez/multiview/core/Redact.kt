package id.my.doncorleonez.multiview.core

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Removes Xtream credentials from any text before it reaches a log line or the screen.
 * Xtream puts username and password in the URL path (`/live/U/P/123.ts`) and in API query strings,
 * and Media3 exceptions include the URI — so every message goes through [redact].
 */
object Redact {
    private val secrets = CopyOnWriteArraySet<String>()

    /** Known secrets are also removed verbatim, wherever they appear (defence in depth). */
    fun register(secret: String?) {
        if (secret != null && secret.length >= 3) secrets.add(secret)
    }

    fun clearRegistered() = secrets.clear()

    private val pathCreds = Regex("""/(live|movie|series|timeshift)/[^/\s?#]+/[^/\s?#]+/""", RegexOption.IGNORE_CASE)
    private val queryCreds = Regex("""((?:username|password)=)[^&\s#"']*""", RegexOption.IGNORE_CASE)
    private val userInfo = Regex("""(https?://)[^/@\s]+@""", RegexOption.IGNORE_CASE)

    fun redact(text: String?): String {
        if (text == null) return ""
        var s = pathCreds.replace(text) { "/${it.groupValues[1]}/***/***/" }
        s = queryCreds.replace(s) { "${it.groupValues[1]}***" }
        s = userInfo.replace(s) { "${it.groupValues[1]}***@" }
        for (sec in secrets) if (s.contains(sec)) s = s.replace(sec, "***")
        return s
    }

    /** Host only, for display/logging of a server. */
    fun hostOf(baseUrl: String): String =
        baseUrl.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')

    /** A throwable's message chain, redacted. Never prints stack frames with URIs unredacted. */
    fun describe(t: Throwable?): String {
        if (t == null) return ""
        val parts = ArrayList<String>()
        var c: Throwable? = t
        var n = 0
        while (c != null && n < 4) {
            parts.add(c.javaClass.simpleName + (c.message?.let { ": $it" } ?: ""))
            c = c.cause; n++
        }
        return redact(parts.joinToString(" <- "))
    }
}

/** Logging seam: Android impl writes to logcat, tests capture. Every message is redacted. */
interface Logger {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
}

object Log {
    @Volatile var sink: Logger = object : Logger {
        override fun i(tag: String, msg: String) {}
        override fun w(tag: String, msg: String) {}
    }
    fun i(tag: String, msg: String) = sink.i(tag, Redact.redact(msg))
    fun w(tag: String, msg: String, t: Throwable? = null) =
        sink.w(tag, Redact.redact(msg) + (t?.let { " | " + Redact.describe(it) } ?: ""))
}
