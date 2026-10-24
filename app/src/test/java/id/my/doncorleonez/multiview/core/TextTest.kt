package id.my.doncorleonez.multiview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextTest {
    @Before fun setUp() = Redact.clearRegistered()

    // A-03
    @Test fun searchNormalisation() {
        assertEquals("cafe tv one", Search.normalize("  Café   TV\tONE "))
        assertEquals("sport 1", Search.normalize("SPÖRT  1"))
        val list = ChannelList.build(
            listOf(Category("1", "x")),
            listOf(StreamRow(1, "beIN Sports 1 HD", "1"), StreamRow(2, "Café  Brasil", "1"), StreamRow(3, "News", "1")),
            0,
        )
        assertEquals(listOf(0), list.search("BEIN sports").toList())
        assertEquals(listOf(1), list.search("cafe brasil").toList())
        assertEquals(listOf(1), list.search(" CAFÉ ").toList())
        assertEquals(3, list.search("").size)
        assertEquals(0, list.search("zzz").size)
    }

    // A-04
    @Test fun streamUrls() {
        val a = Account(0, "A", "http://prov.example:8080", "us er", "p@ss/w", OutputFormat.AUTO)
        assertEquals("http://prov.example:8080/live/us%20er/p%40ss%2Fw/123.ts", StreamUrl.live(a, 123, StreamFormat.TS))
        assertEquals("http://prov.example:8080/live/us%20er/p%40ss%2Fw/123.m3u8", StreamUrl.live(a, 123, StreamFormat.HLS))
        assertTrue(StreamUrl.api(a, "get_live_streams").endsWith("player_api.php?username=us+er&password=p%40ss%2Fw&action=get_live_streams"))
    }

    @Test fun normalizeBase() {
        assertEquals("http://host:8080", StreamUrl.normalizeBase(" host:8080/ "))
        assertEquals("https://h.example", StreamUrl.normalizeBase("https://h.example/player_api.php?x=1"))
        assertNull(StreamUrl.normalizeBase("ftp://h"))
        assertNull(StreamUrl.normalizeBase("http://h:99999"))
        assertNull(StreamUrl.normalizeBase("http://u:p@h"))
        assertNull(StreamUrl.normalizeBase(""))
    }

    @Test fun initialFormat() {
        assertEquals(StreamFormat.TS, StreamUrl.initialFormat(OutputFormat.AUTO, null, listOf("m3u8", "ts")))
        assertEquals(StreamFormat.HLS, StreamUrl.initialFormat(OutputFormat.AUTO, null, listOf("m3u8")))
        assertEquals(StreamFormat.HLS, StreamUrl.initialFormat(OutputFormat.AUTO, StreamFormat.HLS, listOf("ts")))
        assertEquals(StreamFormat.TS, StreamUrl.initialFormat(OutputFormat.TS, StreamFormat.HLS, emptyList()))
        assertEquals(StreamFormat.HLS, StreamUrl.initialFormat(OutputFormat.HLS, null, emptyList()))
    }

    // A-04
    @Test fun redaction() {
        val url = "http://prov.example:8080/live/oggie/S3cret/123.ts"
        assertEquals("http://prov.example:8080/live/***/***/123.ts", Redact.redact(url))
        assertEquals("http://h/player_api.php?username=***&password=***&action=x",
            Redact.redact("http://h/player_api.php?username=oggie&password=S3cret&action=x"))
        // Media3-style exception message
        val msg = "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException: Response code: 403 " +
            "dataSpec=DataSpec[GET http://prov.example/live/oggie/S3cret/9.m3u8, 0, -1, null, 0]"
        val r = Redact.redact(msg)
        assertFalse(r.contains("S3cret")); assertFalse(r.contains("oggie")); assertTrue(r.contains("403"))
        assertEquals("http://***@h/x", Redact.redact("http://u:p@h/x"))
        // registered secrets are removed anywhere
        Redact.register("Hunter22")
        assertEquals("pw is ***", Redact.redact("pw is Hunter22"))
        assertEquals("prov.example:8080", Redact.hostOf("http://prov.example:8080/live/a/b/1.ts"))
    }

    @Test fun describeThrowableIsRedacted() {
        val e = RuntimeException("wrap", java.io.IOException("GET http://h/live/oggie/S3cret/1.ts failed"))
        val d = Redact.describe(e)
        assertFalse(d.contains("S3cret"))
        assertTrue(d.contains("IOException"))
    }

    @Test fun accountToStringHasNoSecrets() {
        val a = Account(0, "A", "http://prov.example:8080", "oggie", "S3cret", OutputFormat.AUTO)
        assertFalse(a.toString().contains("S3cret"))
        assertFalse(a.toString().contains("oggie"))
    }
}
