package id.my.doncorleonez.multiview.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallTest {
    private val sched = FakeScheduler()
    private val ports = List(4) { FakePort("slot${it + 1}") }
    private val noop = object : SlotEngine.Listener { override fun onStateChanged(slot: Int) {} }
    private val engines = List(4) { SlotEngine(it, ports[it], sched, noop) }

    private fun wall(layout: Int = 4, hl: Int = 0): Wall {
        engines.forEachIndexed { i, e -> e.configure(account(i), null, emptyList()); e.restoreChannel(ChannelRef(100 + i, "C$i")) }
        return Wall(engines, layout, hl).also { it.start() }
    }

    private fun audibleSlots() = engines.filter { it.audible }.map { it.slot }
    private fun activeSlots() = ports.indices.filter { ports[it].active }

    // A-06: rects
    @Test fun layoutRects1080p() {
        assertEquals(Rect(0, 0, 1920, 1080), Grid.rect(1, 2, 2, 1920, 1080))
        assertNull(Grid.rect(1, 0, 2, 1920, 1080))
        assertEquals(Rect(0, 270, 960, 540), Grid.rect(2, 0, 0, 1920, 1080))
        assertEquals(Rect(960, 270, 960, 540), Grid.rect(2, 1, 0, 1920, 1080))
        assertNull(Grid.rect(2, 2, 0, 1920, 1080))
        assertEquals(Rect(0, 540, 960, 540), Grid.rect(3, 2, 0, 1920, 1080))
        assertNull(Grid.rect(3, 3, 0, 1920, 1080))
        assertEquals(Rect(960, 540, 960, 540), Grid.rect(4, 3, 0, 1920, 1080))
    }

    // A-06: focus graph, no wrap
    @Test fun focusGraph() {
        val L = Dir.LEFT; val R = Dir.RIGHT; val U = Dir.UP; val D = Dir.DOWN
        val expect4 = mapOf(
            0 to mapOf(L to null, R to 1, U to null, D to 2),
            1 to mapOf(L to 0, R to null, U to null, D to 3),
            2 to mapOf(L to null, R to 3, U to 0, D to null),
            3 to mapOf(L to 2, R to null, U to 1, D to null),
        )
        for ((s, m) in expect4) for ((d, n) in m) assertEquals("L4 S$s $d", n, Grid.neighbour(4, s, d))
        assertEquals(null, Grid.neighbour(3, 2, R)) // bottom-right empty
        assertEquals(null, Grid.neighbour(3, 1, D))
        assertEquals(2, Grid.neighbour(3, 0, D))
        assertEquals(1, Grid.neighbour(2, 0, R)); assertEquals(null, Grid.neighbour(2, 0, D)); assertEquals(null, Grid.neighbour(2, 1, R))
        for (d in Dir.values()) assertEquals(null, Grid.neighbour(1, 3, d))
    }

    // A-07: exactly one audible slot in every layout after every move
    @Test fun exactlyOneAudible() {
        val w = wall()
        for (layout in listOf(4, 3, 2, 1, 4, 2, 3, 1)) {
            w.setLayout(layout)
            assertEquals(listOf(w.highlighted), audibleSlots())
            for (d in listOf(Dir.RIGHT, Dir.DOWN, Dir.LEFT, Dir.UP, Dir.RIGHT, Dir.DOWN, Dir.DOWN, Dir.RIGHT)) {
                w.move(d)
                assertEquals("layout $layout after $d", listOf(w.highlighted), audibleSlots())
                val audiblePorts = ports.indices.filter { ports[it].active && ports[it].audioOn }
                assertEquals(listOf(w.highlighted), audiblePorts)
            }
        }
    }

    // A-10: hidden slots released; stop releases all; start restores
    @Test fun releaseOnHideAndLifecycle() {
        val w = wall(layout = 4, hl = 3)
        assertEquals(listOf(0, 1, 2, 3), activeSlots())
        w.setLayout(2)
        assertEquals(listOf(0, 1), activeSlots())
        assertEquals(0, w.highlighted) // S4 no longer visible → S1
        w.setLayout(1)
        assertEquals(listOf(0), activeSlots())
        w.move(Dir.RIGHT) // no neighbours in layout 1
        assertEquals(0, w.highlighted)
        w.setLayout(4); w.move(Dir.RIGHT); w.move(Dir.DOWN)
        assertEquals(3, w.highlighted)
        w.setLayout(1) // maximise the highlighted slot
        assertEquals(listOf(3), activeSlots())
        w.stop()
        assertEquals(emptyList<Int>(), activeSlots())
        assertTrue(audibleSlots().isEmpty())
        w.start()
        assertEquals(listOf(3), activeSlots())
        assertEquals(listOf(3), audibleSlots())
        // channels survive the stop/start
        assertTrue(ports[3].lastUrl!!.endsWith("/103.ts"))
    }

    @Test fun layoutChangeReleasesBeforeOthersStart() {
        val w = wall(layout = 1, hl = 0)
        w.setLayout(4)
        assertEquals(listOf(0, 1, 2, 3), activeSlots())
        // started with correct audio flag: only highlighted started audible
        assertTrue(ports[0].audioOn)
        assertFalse(ports[1].audioOn || ports[2].audioOn || ports[3].audioOn)
    }
}
