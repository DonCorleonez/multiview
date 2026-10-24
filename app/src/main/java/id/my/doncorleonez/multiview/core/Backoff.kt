package id.my.doncorleonez.multiview.core

/** Retry delays (§6.5, §6.6). */
object Backoff {
    /** Provider refused the stream (401/403/458/509…): 3 s, 6 s, 12 s, then stop. */
    val REJECTED_MS = longArrayOf(3_000, 6_000, 12_000)

    /** Network/source errors and stalls: 1, 2, 4, 8, 15, 15, … s — forever while visible. */
    fun networkDelayMs(attempt: Int): Long = when (attempt) {
        0 -> 1_000; 1 -> 2_000; 2 -> 4_000; 3 -> 8_000
        else -> 15_000
    }

    /** Clean playback this long resets the network backoff. */
    const val RESET_AFTER_MS = 60_000L

    /** Buffering this long while previously playing counts as a stall. */
    const val STALL_MS = 8_000L

    /** First connect (or reconnect) that hasn't reached READY within this time counts as a failure. */
    const val CONNECT_TIMEOUT_MS = 20_000L
}
