package id.my.doncorleonez.multiview.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader

class XtreamTest {
    private val password = "S3cr3t-Pass!"
    private lateinit var log: CapturingLogger

    @Before fun setUp() {
        log = CapturingLogger()
        Log.sink = log
        Redact.clearRegistered()
    }

    @After fun tearDown() {
        // A-05: across every test in this class the password must never reach a log line
        for (l in log.lines) assertFalse("password leaked to log: $l", l.contains(password))
    }

    private fun acc(base: String, pw: String = password) = Account(0, "Akun 1", base, "oggie", pw, OutputFormat.AUTO)

    // A-01
    @Test fun authParsesUserInfo() {
        MockXtream("oggie", password).use { srv ->
            val a = XtreamClient().auth(acc(srv.base))
            assertTrue(a.ok)
            assertEquals("Active", a.status)
            assertEquals(1798761600L, a.expUnixSec)
            assertEquals(1, a.maxConnections)
            assertEquals(0, a.activeConnections)
            assertEquals(listOf("m3u8", "ts", "rtmp"), a.allowedFormats)
            assertEquals("Asia/Jakarta", a.serverTimezone)
        }
    }

    // A-01: wrong password → not ok (and nothing is saved: see AccountStoreTest)
    @Test fun authWrongPassword() {
        MockXtream("oggie", password).use { srv ->
            val a = XtreamClient().auth(acc(srv.base, "wrong"))
            assertFalse(a.ok)
        }
    }

    @Test fun authHttp403IsClearErrorWithoutUrl() {
        MockXtream("oggie", password).use { srv ->
            srv.rejectWith = 403
            try {
                XtreamClient().auth(acc(srv.base)); fail("expected exception")
            } catch (e: XtreamException) {
                assertEquals(403, e.httpCode)
                assertFalse(e.message!!.contains(password))
                assertFalse(e.message!!.contains("player_api"))
            }
        }
    }

    @Test fun unreachableServerMessageHasHostOnly() {
        try {
            XtreamClient(connectTimeoutMs = 500).auth(acc("http://127.0.0.1:9")); fail()
        } catch (e: XtreamException) {
            assertTrue(e.message!!.contains("127.0.0.1"))
            assertFalse(e.message!!.contains(password))
        }
    }

    // A-02
    @Test fun categoriesAndStreams() {
        MockXtream("oggie", password, channels = 50, categories = 5).use { srv ->
            val list = XtreamClient().channelList(acc(srv.base), 42L)
            assertEquals(5, list.categories.size)
            assertEquals("Kategori \"1\" Café", list.categories[0].name)
            assertEquals(50, list.size)
            assertEquals(1001, list.ids[0])
            assertEquals("Channel 1 HD", list.names[0])
            // channel 1 → cat (1%5)+1 = 2 → index 1
            assertEquals(1, list.categoryIndex[0])
            assertEquals(10, list.inCategory(0).size)
            assertEquals(42L, list.fetchedAtMs)
        }
    }

    // A-02: 30 000 channels load and filter fast
    @Test fun thirtyThousandChannels() {
        MockXtream("oggie", password, channels = 30_000, categories = 60).use { srv ->
            val t0 = System.nanoTime()
            val list = XtreamClient().channelList(acc(srv.base))
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(30_000, list.size)
            list.warm()
            val t1 = System.nanoTime()
            val hits = list.search("channel 2999")
            val filterMs = (System.nanoTime() - t1) / 1_000_000
            assertEquals(listOf(2999, 29990, 29991, 29992, 29993, 29994, 29995, 29996, 29997, 29998, 29999).size, hits.size)
            assertTrue("filter took $filterMs ms", filterMs < 300)
            val t2 = System.nanoTime()
            list.inCategory(7)
            assertTrue((System.nanoTime() - t2) / 1_000_000 < 300)
            println("30k: load ${loadMs}ms, search ${filterMs}ms")
        }
    }

    @Test fun cacheRoundTrip() {
        MockXtream("oggie", password, channels = 200).use { srv ->
            val list = XtreamClient().channelList(acc(srv.base), 7L)
            val out = ByteArrayOutputStream()
            list.writeTo(out)
            val back = ChannelList.readFrom(ByteArrayInputStream(out.toByteArray()))
            assertEquals(list.size, back.size)
            assertEquals(list.categories, back.categories)
            assertTrue(list.ids.contentEquals(back.ids))
            assertTrue(list.names.contentEquals(back.names))
            assertTrue(list.categoryIndex.contentEquals(back.categoryIndex))
            assertEquals(7L, back.fetchedAtMs)
            assertEquals(5, back.indexOfId(1006))
        }
    }

    @Test fun parseToleratesOddPanels() {
        // numbers as strings, nulls, nested objects, empty object instead of array, duplicates, bad ids
        val streams = """[{"stream_id":"5","name":" A ","category_id":7},{"stream_id":null,"name":"x"},
            {"stream_id":5,"name":"dup"},{"stream_id":6,"name":"Movie","stream_type":"movie"},
            {"stream_id":8,"name":"B","category_id":"9","extra":{"a":[1,2,{"b":null}]}}]"""
        val rows = XtreamClient.parseStreams(StringReader(streams))
        val list = ChannelList.build(listOf(Category("7", "Seven")), rows, 0)
        assertEquals(listOf(5, 8), list.ids.toList())
        assertEquals("A", list.names[0])
        assertEquals(0, list.categoryIndex[0])
        assertEquals(-1, list.categoryIndex[1])
        assertTrue(XtreamClient.parseCategories(StringReader("{}")).isEmpty())
        val auth = XtreamClient.parseAuth(StringReader("""{"user_info":{"auth":1,"max_connections":null,"allowed_output_formats":[]},"server_info":[]}"""))
        assertTrue(auth.ok)
        assertNull(auth.maxConnections)
    }

    @Test fun jsonEscapes() {
        val j = JsonReaderLite(StringReader("""{"a":"x\"y\\zé\n","b":[true,false,null,-1.5e3],"c":{}}"""))
        j.beginObject()
        assertEquals("a", j.nextName()); assertEquals("x\"y\\zé\n", j.nextStringOrNull())
        assertEquals("b", j.nextName()); j.beginArray()
        assertEquals("true", j.nextStringOrNull()); assertEquals("false", j.nextStringOrNull())
        assertNull(j.nextStringOrNull()); assertEquals("-1.5e3", j.nextStringOrNull())
        assertFalse(j.hasNext()); j.endArray()
        assertEquals("c", j.nextName()); j.skipValue()
        assertFalse(j.hasNext()); j.endObject()
    }
}
