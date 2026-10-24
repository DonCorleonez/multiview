package id.my.doncorleonez.multiview.core

import java.net.URLEncoder

/** The only place (with XtreamClient's query builder) where credentials enter a URL. */
object StreamUrl {

    /** Normalises what the owner typed: adds http:// if missing, strips trailing slashes and paths. */
    fun normalizeBase(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (rest.isEmpty() || rest.contains(' ') || rest.contains('@')) return null
        val host = rest.substringBefore(':')
        if (host.isEmpty()) return null
        if (rest.contains(':')) {
            val port = rest.substringAfter(':').toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        }
        return "$scheme://$rest"
    }

    private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun live(acc: Account, streamId: Int, format: StreamFormat): String =
        "${acc.baseUrl}/live/${seg(acc.username)}/${seg(acc.password)}/$streamId.${format.ext}"

    fun api(acc: Account, action: String? = null): String {
        val q = "username=${URLEncoder.encode(acc.username, "UTF-8")}&password=${URLEncoder.encode(acc.password, "UTF-8")}"
        return "${acc.baseUrl}/player_api.php?$q" + (action?.let { "&action=$it" } ?: "")
    }

    /**
     * First format to try for an account:
     * explicit choice wins; for AUTO use the format learned earlier, else TS if the provider allows it.
     */
    fun initialFormat(choice: OutputFormat, learned: StreamFormat?, allowed: List<String>): StreamFormat = when (choice) {
        OutputFormat.TS -> StreamFormat.TS
        OutputFormat.HLS -> StreamFormat.HLS
        OutputFormat.AUTO -> learned ?: when {
            allowed.isEmpty() -> StreamFormat.TS
            allowed.any { it.equals("ts", true) } -> StreamFormat.TS
            allowed.any { it.equals("m3u8", true) } -> StreamFormat.HLS
            else -> StreamFormat.TS
        }
    }
}
