package id.my.doncorleonez.multiview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoreTest {

    @Test fun saveLoadNeverStoresPlaintext() {
        val kv = MemKV()
        val store = AccountStore(kv, XorBox())
        val acc = store.resolve(1, AccountStore.Draft("Utama", "prov.example:8080", "oggie", "S3cret!", OutputFormat.HLS))
        store.save(acc)
        assertFalse(kv.map.values.any { it.contains("S3cret!") })
        val back = store.load(1)!!
        assertEquals("http://prov.example:8080", back.baseUrl)
        assertEquals("S3cret!", back.password)
        assertEquals(OutputFormat.HLS, back.format)
        assertEquals("Utama", back.label)
        assertNull(store.draftOf(1).password)
        // blank password on edit keeps the stored one
        val edited = store.resolve(1, AccountStore.Draft("Utama", "prov.example:8080", "oggie", "", OutputFormat.AUTO))
        assertEquals("S3cret!", edited.password)
    }

    // A-11: Keystore failure → nothing persisted
    @Test fun keystoreFailureSavesNothing() {
        val kv = MemKV()
        val store = AccountStore(kv, FailingBox())
        val acc = store.resolve(0, AccountStore.Draft("A", "http://h:1", "u", "pw-secret", OutputFormat.AUTO))
        try { store.save(acc); fail("expected StoreException") } catch (e: StoreException) {
            assertTrue(e.message!!.contains("Keystore"))
        }
        assertTrue(kv.map.isEmpty())
        assertNull(store.load(0))
    }

    @Test fun validation() {
        val store = AccountStore(MemKV(), XorBox())
        for (d in listOf(
            AccountStore.Draft("", "ftp://x", "u", "p", OutputFormat.AUTO),
            AccountStore.Draft("", "http://x", " ", "p", OutputFormat.AUTO),
            AccountStore.Draft("", "http://x", "u", "", OutputFormat.AUTO),
        )) {
            try { store.resolve(0, d); fail("should reject $d") } catch (_: StoreException) {}
        }
    }

    @Test fun clearAndLearnedFormatReset() {
        val kv = MemKV()
        val store = AccountStore(kv, XorBox())
        store.save(store.resolve(2, AccountStore.Draft("", "http://a:1", "u", "p1", OutputFormat.AUTO)))
        store.setLearned(2, StreamFormat.HLS); store.setAllowed(2, listOf("m3u8"))
        // same server/user: learned kept
        store.save(store.resolve(2, AccountStore.Draft("", "http://a:1", "u", "p2", OutputFormat.AUTO)))
        assertEquals(StreamFormat.HLS, store.learned(2))
        // different server: reset
        store.save(store.resolve(2, AccountStore.Draft("", "http://b:1", "u", "p2", OutputFormat.AUTO)))
        assertNull(store.learned(2)); assertTrue(store.allowed(2).isEmpty())
        store.clear(2)
        assertNull(store.load(2)); assertTrue(kv.map.isEmpty())
    }

    @Test fun viewStateAndHistory() {
        val vs = ViewState(MemKV())
        assertEquals(4, vs.layout); assertEquals(0, vs.highlighted)
        vs.layout = 2; vs.highlighted = 1
        assertEquals(2, vs.layout); assertEquals(1, vs.highlighted)
        vs.setLastChannel(1, ChannelRef(9, "Nine"))
        assertEquals(ChannelRef(9, "Nine"), vs.lastChannel(1))
        for (i in 1..25) vs.pushHistory(1, ChannelRef(i, "C\t$i"))
        vs.pushHistory(1, ChannelRef(10, "C 10"))
        val h = vs.history(1)
        assertEquals(20, h.size)
        assertEquals(10, h[0].streamId)
        assertEquals(1, h.count { it.streamId == 10 })
        vs.clearSlot(1)
        assertNull(vs.lastChannel(1)); assertTrue(vs.history(1).isEmpty())
    }
}
