package id.my.doncorleonez.multiview.core

/**
 * The four slots together: current layout, highlight, which slots are visible (others released)
 * and which single slot is audible (§2.2, §2.4, §6.3).
 */
class Wall(val engines: List<SlotEngine>, layout: Int, highlighted: Int) {

    var layout: Int = layout.coerceIn(1, 4); private set
    var highlighted: Int = Grid.clampHighlight(this.layout, highlighted); private set
    private var started = false

    fun visible(): List<Int> = Grid.visibleSlots(layout, highlighted)

    /** Activity started: show visible slots, highlight gets audio. */
    fun start() {
        started = true
        apply()
    }

    /** Activity stopped: release every player. */
    fun stop() {
        started = false
        engines.forEach { it.setAudible(false); it.suspend() }
    }

    fun setLayout(l: Int) {
        layout = l.coerceIn(1, 4)
        highlighted = Grid.clampHighlight(layout, highlighted)
        if (started) apply()
    }

    /** Moves the highlight; returns false at an edge. */
    fun move(dir: Dir): Boolean {
        val n = Grid.neighbour(layout, highlighted, dir) ?: return false
        highlighted = n
        if (started) apply()
        return true
    }

    private fun apply() {
        val vis = visible()
        // 1) audio off everywhere except the highlight, before anything starts (never two audible)
        engines.forEach { if (it.slot != highlighted) it.setAudible(false) }
        // 2) release hidden slots first so their connections and decoders are free
        engines.forEach { if (it.slot !in vis) it.setVisible(false) }
        // 3) highlight audible (a player started next will start with audio), then show visible slots
        engines[highlighted].setAudible(true)
        engines.forEach { if (it.slot in vis) it.setVisible(true) }
    }
}
