package id.my.doncorleonez.multiview.core

import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

class XtreamException(message: String, val httpCode: Int = -1) : IOException(message)

/**
 * Xtream Codes `player_api.php` client. Only three calls: auth, live categories, live streams.
 * Plain HttpURLConnection; responses are streamed through [JsonReaderLite].
 * Error messages never contain the URL or credentials.
 */
class XtreamClient(
    private val userAgent: String = "Multiview/1",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {

    fun auth(acc: Account): AuthInfo = get(StreamUrl.api(acc)) { parseAuth(it) }

    fun categories(acc: Account): List<Category> = get(StreamUrl.api(acc, "get_live_categories")) { parseCategories(it) }

    fun streams(acc: Account): List<StreamRow> = get(StreamUrl.api(acc, "get_live_streams")) { parseStreams(it) }

    /** Categories + streams → ChannelList. */
    fun channelList(acc: Account, nowMs: Long = System.currentTimeMillis()): ChannelList =
        ChannelList.build(categories(acc), streams(acc), nowMs)

    private fun <T> get(url: String, parse: (Reader) -> T): T {
        val host = Redact.hostOf(url)
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw XtreamException("Alamat server tidak valid")
        }
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                Log.w(TAG, "request to $host failed", e)
                throw XtreamException("Tidak bisa terhubung ke $host")
            }
            if (code == 401 || code == 403 || code == 884) throw XtreamException("Login ditolak provider (HTTP $code)", code)
            if (code !in 200..299) throw XtreamException("Server membalas HTTP $code", code)
            return try {
                InputStreamReader(conn.inputStream, Charsets.UTF_8).use { parse(it) }
            } catch (e: XtreamException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bad response from $host", e)
                throw XtreamException("Balasan server tidak bisa dibaca")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "Xtream"

        fun parseAuth(r: Reader): AuthInfo {
            val j = JsonReaderLite(r)
            var auth: String? = null
            var status: String? = null
            var exp: Long? = null
            var maxC: Int? = null
            var active: Int? = null
            val formats = ArrayList<String>()
            var tz: String? = null
            if (j.peek() != JsonReaderLite.Token.BEGIN_OBJECT) throw XtreamException("Balasan login tidak dikenal")
            j.beginObject()
            while (j.hasNext()) {
                when (j.nextName()) {
                    "user_info" -> if (j.peek() == JsonReaderLite.Token.BEGIN_OBJECT) {
                        j.beginObject()
                        while (j.hasNext()) {
                            when (j.nextName()) {
                                "auth" -> auth = j.nextStringOrNull()
                                "status" -> status = j.nextStringOrNull()
                                "exp_date" -> exp = j.nextStringOrNull()?.trim()?.toLongOrNull()
                                "max_connections" -> maxC = j.nextStringOrNull()?.trim()?.toIntOrNull()
                                "active_cons" -> active = j.nextStringOrNull()?.trim()?.toIntOrNull()
                                "allowed_output_formats" -> if (j.peek() == JsonReaderLite.Token.BEGIN_ARRAY) {
                                    j.beginArray()
                                    while (j.hasNext()) {
                                        if (j.peek() == JsonReaderLite.Token.BEGIN_OBJECT || j.peek() == JsonReaderLite.Token.BEGIN_ARRAY) j.skipValue()
                                        else j.nextStringOrNull()?.let { formats.add(it) }
                                    }
                                    j.endArray()
                                } else j.skipValue()
                                else -> j.skipValue()
                            }
                        }
                        j.endObject()
                    } else j.skipValue()
                    "server_info" -> if (j.peek() == JsonReaderLite.Token.BEGIN_OBJECT) {
                        j.beginObject()
                        while (j.hasNext()) {
                            if (j.nextName() == "timezone") tz = scalarOrSkip(j) else j.skipValue()
                        }
                        j.endObject()
                    } else j.skipValue()
                    else -> j.skipValue()
                }
            }
            j.endObject()
            val ok = auth == "1" || auth.equals("true", true)
            return AuthInfo(ok, status, exp, maxC, active, formats, tz)
        }

        fun parseCategories(r: Reader): List<Category> {
            val j = JsonReaderLite(r)
            val out = ArrayList<Category>()
            if (j.peek() != JsonReaderLite.Token.BEGIN_ARRAY) { j.skipValue(); return out } // some panels return {} when empty
            j.beginArray()
            while (j.hasNext()) {
                if (j.peek() != JsonReaderLite.Token.BEGIN_OBJECT) { j.skipValue(); continue }
                var id: String? = null
                var name: String? = null
                j.beginObject()
                while (j.hasNext()) {
                    when (j.nextName()) {
                        "category_id" -> id = scalarOrSkip(j)
                        "category_name" -> name = scalarOrSkip(j)
                        else -> j.skipValue()
                    }
                }
                j.endObject()
                if (id != null) out.add(Category(id, name?.trim().orEmpty().ifEmpty { "Kategori $id" }))
            }
            j.endArray()
            return out
        }

        fun parseStreams(r: Reader): List<StreamRow> {
            val j = JsonReaderLite(r)
            val out = ArrayList<StreamRow>()
            if (j.peek() != JsonReaderLite.Token.BEGIN_ARRAY) { j.skipValue(); return out }
            j.beginArray()
            while (j.hasNext()) {
                if (j.peek() != JsonReaderLite.Token.BEGIN_OBJECT) { j.skipValue(); continue }
                var id = -1
                var name = ""
                var cat: String? = null
                var type: String? = null
                j.beginObject()
                while (j.hasNext()) {
                    when (j.nextName()) {
                        "stream_id" -> id = scalarOrSkip(j)?.trim()?.toIntOrNull() ?: -1
                        "name" -> name = scalarOrSkip(j)?.trim().orEmpty()
                        "category_id" -> cat = scalarOrSkip(j)
                        "stream_type" -> type = scalarOrSkip(j)
                        else -> j.skipValue()
                    }
                }
                j.endObject()
                // Some panels mix radio/created_live into live; keep everything that is not explicitly VOD.
                if (type == null || !type.equals("movie", true)) out.add(StreamRow(id, name, cat))
            }
            j.endArray()
            return out
        }

        private fun scalarOrSkip(j: JsonReaderLite): String? {
            val t = j.peek()
            return if (t == JsonReaderLite.Token.BEGIN_OBJECT || t == JsonReaderLite.Token.BEGIN_ARRAY) { j.skipValue(); null }
            else j.nextStringOrNull()
        }
    }
}
