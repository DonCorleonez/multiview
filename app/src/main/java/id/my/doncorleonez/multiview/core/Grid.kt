package id.my.doncorleonez.multiview.core

/** Pixel rectangle (left, top, width, height). */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

enum class Dir { LEFT, RIGHT, UP, DOWN }

/**
 * Layouts 1–4 on a fixed 2×2 grid. Slots are 0-based here (S1 = 0 … S4 = 3).
 *
 *   Layout 4: S1 S2 / S3 S4      Layout 3: S1 S2 / S3 --
 *   Layout 2: S1 | S2 (16:9 halves, vertically centred)
 *   Layout 1: the highlighted slot, full screen
 */
object Grid {
    const val SLOTS = 4

    fun visibleSlots(layout: Int, highlighted: Int): List<Int> = when (layout) {
        1 -> listOf(highlighted.coerceIn(0, SLOTS - 1))
        2 -> listOf(0, 1)
        3 -> listOf(0, 1, 2)
        else -> listOf(0, 1, 2, 3)
    }

    /** Highlight to use after switching to [layout]: keep it if still visible, else S1. */
    fun clampHighlight(layout: Int, highlighted: Int): Int =
        if (layout == 1) highlighted.coerceIn(0, SLOTS - 1)
        else if (highlighted in visibleSlots(layout, highlighted)) highlighted else 0

    fun rect(layout: Int, slot: Int, highlighted: Int, w: Int, h: Int): Rect? {
        if (slot !in visibleSlots(layout, highlighted)) return null
        val hw = w / 2
        val hh = h / 2
        return when (layout) {
            1 -> Rect(0, 0, w, h)
            2 -> {
                val th = hw * 9 / 16
                val top = (h - th) / 2
                if (slot == 0) Rect(0, top, hw, th) else Rect(hw, top, w - hw, th)
            }
            else -> when (slot) {
                0 -> Rect(0, 0, hw, hh)
                1 -> Rect(hw, 0, w - hw, hh)
                2 -> Rect(0, hh, hw, h - hh)
                else -> Rect(hw, hh, w - hw, h - hh)
            }
        }
    }

    /** D-pad neighbour of [slot] in [layout], or null at an edge (no wrap). */
    fun neighbour(layout: Int, slot: Int, dir: Dir): Int? {
        val n: Int? = when (layout) {
            1 -> null
            2 -> when {
                slot == 0 && dir == Dir.RIGHT -> 1
                slot == 1 && dir == Dir.LEFT -> 0
                else -> null
            }
            else -> {
                val col = slot % 2
                val row = slot / 2
                when (dir) {
                    Dir.LEFT -> if (col == 1) slot - 1 else null
                    Dir.RIGHT -> if (col == 0) slot + 1 else null
                    Dir.UP -> if (row == 1) slot - 2 else null
                    Dir.DOWN -> if (row == 0) slot + 2 else null
                }
            }
        }
        return n?.takeIf { it in visibleSlots(layout, slot) }
    }
}
