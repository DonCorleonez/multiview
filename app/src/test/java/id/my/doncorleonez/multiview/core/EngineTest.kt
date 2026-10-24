package id.my.doncorleonez.multiview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import id.my.doncorleonez.multiview.core.SlotEngine.Phase

class EngineTest {
    private val sched = FakeScheduler()
    private val learnedEvents = ArrayList<StreamFormat>()
    private val listener = object : SlotEngine.Listener {
        override fun onStateChanged(slot: Int) {}
        override fun onFormatLearned(slot: Int, format: StreamFormat) { learnedEvents.add(format) }
    }

    private fun engine(port: FakePort = FakePort(), slot: Int = 0) = SlotEngine(slot, port, sched, listener)

    private fun ready(e: SlotEngine, p: FakePort) = e.onReady(p.lastGen)

    @Test fun statesWithoutAccountOrChannel() {
        val p = FakePort(); val e = engine(p)
        e.setVisible(true)
        assertEquals(Phase.NO_ACCOUNT, e.phase); assertEquals("Akun belum diisi", e.statusText())
        e.configure(account(), null, emptyList())
        assertEquals(Phase.NO_CHANNEL, e.phase); assertEquals("Pilih channel", e.statusText())
        e.play(ChannelRef(5, "Five"))
        assertEquals(Phase.CONNECTING, e.phase); assertEquals("Menyambung…", e.statusText())
        assertTrue(p.lastUrl!!.endsWith("/live/user0/pw-0/5.ts"))
        ready(e, p)
        assertEquals(Phase.PLAYING, e.phase); assertNull(e.statusText())
    }

    // A-08: rapid channel changes never overlap players (FakePort throws on overlap)
    @Test fun channelChangeReleasesBeforeStart() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true)
        repeat(20) { i -> e.play(ChannelRef(i + 1, "C$i")); if (i % 3 == 0) ready(e, p) }
        assertEquals(20, p.starts)
        assertEquals(19, p.events.count { it == "release" })
        assertTrue(p.active)
        // pattern is strictly start, release, start, release, ...
        val seq = p.events.filter { it.startsWith("start") || it == "release" }
        for (i in seq.indices) assertEquals(if (i % 2 == 0) "start" else "release", seq[i].substringBefore(':'))
    }

    // A-09: provider rejection → 3 s, 6 s, 12 s then stop
    @Test fun rejectedBackoffThenStops() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A"))
        e.onError(p.lastGen, FailKind.REJECTED)
        assertEquals(Phase.REJECTED_WAIT, e.phase); assertFalse(p.active)
        assertEquals("Koneksi ditolak provider", e.statusText())
        sched.advance(2_999); assertEquals(1, p.starts)
        sched.advance(1); assertEquals(2, p.starts)
        e.onError(p.lastGen, FailKind.REJECTED)
        sched.advance(5_999); assertEquals(2, p.starts); sched.advance(1); assertEquals(3, p.starts)
        e.onError(p.lastGen, FailKind.REJECTED)
        sched.advance(11_999); assertEquals(3, p.starts); sched.advance(1); assertEquals(4, p.starts)
        e.onError(p.lastGen, FailKind.REJECTED)
        assertEquals(Phase.REJECTED_STOPPED, e.phase); assertFalse(p.active)
        sched.advance(600_000); assertEquals(4, p.starts)
        // user picks again → retries
        e.play(ChannelRef(1, "A")); assertEquals(5, p.starts)
    }

    // A-09: network drop → reconnect loop 1,2,4,8,15,15…
    @Test fun networkReconnectLoop() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A")); ready(e, p)
        val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000, 15_000)
        for ((i, d) in delays.withIndex()) {
            e.onError(p.lastGen, FailKind.NETWORK)
            assertEquals(Phase.RECONNECTING, e.phase)
            sched.advance(d - 1); assertEquals("attempt $i", i + 1, p.starts)
            sched.advance(1); assertEquals(i + 2, p.starts)
        }
        // 60 s of clean playback resets the backoff
        ready(e, p); sched.advance(60_000)
        e.onError(p.lastGen, FailKind.NETWORK)
        sched.advance(1_000); assertEquals(delays.size + 2, p.starts)
    }

    @Test fun stallAfterEightSecondsReconnects() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A")); ready(e, p)
        e.onBuffering(p.lastGen)
        sched.advance(7_999); assertEquals(Phase.PLAYING, e.phase)
        sched.advance(1); assertEquals(Phase.RECONNECTING, e.phase)
        // buffering that recovers in time does nothing
        sched.advance(1_000); ready(e, p)
        e.onBuffering(p.lastGen); sched.advance(3_000); ready(e, p); sched.advance(10_000)
        assertEquals(Phase.PLAYING, e.phase)
    }

    @Test fun connectTimeoutCountsAsFailure() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A"))
        sched.advance(Backoff.CONNECT_TIMEOUT_MS)
        assertEquals(Phase.RECONNECTING, e.phase)
    }

    @Test fun decoderFailureIsLocalAndRetriedByPicking() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A"))
        e.onError(p.lastGen, FailKind.DECODER)
        assertEquals(Phase.DECODER_FULL, e.phase); assertEquals("Decoder penuh", e.statusText()); assertFalse(p.active)
        sched.advance(600_000); assertEquals(1, p.starts)
        e.play(ChannelRef(1, "A")); assertEquals(2, p.starts)
    }

    @Test fun autoFormatFallsBackOnceAndIsLearned() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(fmt = OutputFormat.AUTO), null, listOf("ts", "m3u8")); e.setVisible(true); e.play(ChannelRef(1, "A"))
        assertTrue(p.events.last().startsWith("start:ts"))
        e.onError(p.lastGen, FailKind.PARSE)
        assertTrue(p.events.last().startsWith("start:hls"))
        ready(e, p)
        assertEquals(listOf(StreamFormat.HLS), learnedEvents)
        // a second parse error does not flip again; it reconnects in the same format
        e.onError(p.lastGen, FailKind.PARSE)
        assertEquals(Phase.RECONNECTING, e.phase)
        sched.advance(1_000)
        assertTrue(p.events.last().startsWith("start:hls"))
    }

    @Test fun explicitFormatNeverFallsBack() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(fmt = OutputFormat.TS), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A"))
        e.onError(p.lastGen, FailKind.PARSE)
        assertEquals(Phase.RECONNECTING, e.phase)
        sched.advance(1_000)
        assertTrue(p.events.last().startsWith("start:ts"))
    }

    @Test fun staleCallbacksIgnored() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A"))
        val oldGen = p.lastGen
        e.play(ChannelRef(2, "B"))
        e.onError(oldGen, FailKind.REJECTED)
        assertEquals(Phase.CONNECTING, e.phase)
        e.onReady(oldGen)
        assertEquals(Phase.CONNECTING, e.phase)
    }

    @Test fun hideReleasesAndAccountChangeRestarts() {
        val p = FakePort(); val e = engine(p)
        e.configure(account(), null, emptyList()); e.setVisible(true); e.play(ChannelRef(1, "A")); ready(e, p)
        e.setVisible(false); assertFalse(p.active); assertEquals(Phase.HIDDEN, e.phase)
        e.setVisible(true); assertTrue(p.active)
        val starts = p.starts
        e.configure(account(), null, emptyList()) // same fingerprint
        assertEquals(starts, p.starts)
        e.configure(account(pw = "new"), null, emptyList())
        assertEquals(starts + 1, p.starts)
        assertTrue(p.lastUrl!!.contains("/new/"))
        e.configure(null, null, emptyList())
        assertFalse(p.active); assertEquals(Phase.NO_ACCOUNT, e.phase)
    }
}
