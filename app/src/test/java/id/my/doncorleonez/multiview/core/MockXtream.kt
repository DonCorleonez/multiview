package id.my.doncorleonez.multiview.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Local Xtream panel for tests (JDK HttpServer). One user/password; fixtures generated in code. */
class MockXtream(
    private val user: String,
    private val pass: String,
    private val channels: Int = 50,
    private val categories: Int = 5,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = ArrayList<String>()
    @Volatile var rejectWith: Int = 0

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/player_api.php") { ex ->
            val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            synchronized(requests) { requests.add(q["action"] ?: "auth") }
            val (code, body) = when {
                rejectWith != 0 -> rejectWith to "{}"
                q["username"] != user || q["password"] != pass ->
                    200 to """{"user_info":{"auth":0}}"""
                q["action"] == null -> 200 to authJson()
                q["action"] == "get_live_categories" -> 200 to categoriesJson()
                q["action"] == "get_live_streams" -> 200 to streamsJson()
                else -> 200 to "[]"
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun authJson() = """
        {"user_info":{"username":"$user","password":"$pass","message":"","auth":1,"status":"Active",
          "exp_date":"1798761600","is_trial":"0","active_cons":"0","created_at":"1700000000",
          "max_connections":"1","allowed_output_formats":["m3u8","ts","rtmp"]},
         "server_info":{"url":"127.0.0.1","port":"8080","https_port":"","server_protocol":"http",
          "rtmp_port":"","timezone":"Asia/Jakarta","timestamp_now":1761300000,"time_now":"2026-10-24 17:00:00"}}
    """.trimIndent()

    private fun categoriesJson(): String = buildString {
        append('[')
        for (c in 1..categories) {
            if (c > 1) append(',')
            append("""{"category_id":"$c","category_name":"Kategori \"$c\" Café","parent_id":0}""")
        }
        append(']')
    }

    private fun streamsJson(): String = buildString {
        append('[')
        for (i in 1..channels) {
            if (i > 1) append(',')
            val cat = (i % categories) + 1
            append("""{"num":$i,"name":"Channel $i HD","stream_type":"live","stream_id":${1000 + i},""")
            append(""""stream_icon":"http://img.example/$i.png","epg_channel_id":null,"added":"1700000000",""")
            append(""""category_id":"$cat","category_ids":[$cat],"custom_sid":"","tv_archive":0,"direct_source":"","tv_archive_duration":0}""")
        }
        append(']')
    }

    override fun close() = server.stop(0)
}
