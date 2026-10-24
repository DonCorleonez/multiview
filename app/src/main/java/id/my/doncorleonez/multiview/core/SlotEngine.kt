package id.my.doncorleonez.multiview.core

/** What the player adapter reports when playback fails. */
enum class FailKind {
    /** Provider refused the stream: HTTP 401/403/429/458/509… (connection limit, expired, banned). */
    REJECTED,
    /** No hardware/software decoder instance available (too many tiles for the device). */
    DECODER,
    /** Codec/format the device cannot decode at all. */
    UNSUPPORTED,
    /** Container/manifest could not be parsed → try the other output format (AUTO only). */
    PARSE,
    /** Network, timeouts, 404, stream ended, anything else: reconnect with backoff. */
    NETWORK,
}

/** Player adapter. Implemented with Media3 on Android, faked in tests. Calls happen on one thread. */
interface PlayerPort {
    /** Create a new player for [url]. The engine guarantees the previous player was released. */
    fun start(gen: Int, url: String, hls: Boolean, audible: Boolean)
    fun setAudible(audible: Boolean)
    /** Release the player synchronously; after return the HTTP connection is closed. */
    fun release()
}

interface Cancellable { fun cancel() }

interface Scheduler { fun post(delayMs: Long, block: () -> Unit): Cancellable }

/**
 * State machine for one tile. Owns the "never two connections per account" rule, retry/backoff,
 * output-format fallback, stall detection and the state text shown in the tile.
 * Not thread-safe: call everything (including player callbacks) on the main thread.
 */
class SlotEngine(
    val slot: Int,
    private val port: PlayerPort,
    private val scheduler: Scheduler,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(slot: Int)
        fun onFormatLearned(slot: Int, format: StreamFormat) {}
    }

    enum class Phase { HIDDEN, NO_ACCOUNT, NO_CHANNEL, CONNECTING, PLAYING, RECONNECTING, REJECTED_WAIT, REJECTED_STOPPED, DECODER_FULL, UNSUPPORTED }

    var phase: Phase = Phase.HIDDEN; private set
    var account: Account? = null; private set
    var channel: ChannelRef? = null; private set
    var visible = false; private set
    var audible = false; private set
    /** True while a player instance exists (holds a provider connection). */
    var playerActive = false; private set
    var currentFormat: StreamFormat? = null; private set

    private var learned: StreamFormat? = null
    private var allowed: List<String> = emptyList()
    private var gen = 0
    private var triedFallback = false
    private var netAttempt = 0
    private var rejAttempt = 0

    private var retryTimer: Cancellable? = null
    private var stallTimer: Cancellable? = null
    private var resetTimer: Cancellable? = null
    private var connectTimer: Cancellable? = null

    fun statusText(): String? = when (phase) {
        Phase.HIDDEN, Phase.PLAYING -> null
        Phase.NO_ACCOUNT -> "Akun belum diisi"
        Phase.NO_CHANNEL -> "Pilih channel"
        Phase.CONNECTING -> "Menyambung…"
        Phase.RECONNECTING -> "Menyambung ulang…"
        Phase.REJECTED_WAIT, Phase.REJECTED_STOPPED -> "Koneksi ditolak provider"
        Phase.DECODER_FULL -> "Decoder penuh"
        Phase.UNSUPPORTED -> "Format video tidak didukung"
    }

    // ------------------------------------------------------------------ inputs from UI

    /** Set or change the account bound to this slot. Restarts only if it actually changed. */
    fun configure(acc: Account?, learnedFormat: StreamFormat?, allowedFormats: List<String>) {
        learned = learnedFormat
        allowed = allowedFormats
        if (acc?.fingerprint == account?.fingerprint && (acc == null) == (account == null)) {
            account = acc
            return
        }
        account = acc
        resetAttempts()
        restart()
    }

    /** User picked a channel (also used to retry after a rejection or decoder error). */
    fun play(ref: ChannelRef) {
        channel = ref
        resetAttempts()
        restart()
    }

    /** Restore a remembered channel without forcing a restart if it is the same one. */
    fun restoreChannel(ref: ChannelRef?) {
        if (ref == channel) return
        channel = ref
        resetAttempts()
        restart()
    }

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        if (v) { resetAttempts(); restart() } else { stopPlayer(); setPhase(Phase.HIDDEN) }
    }

    fun setAudible(a: Boolean) {
        if (a == audible) return
        audible = a
        if (playerActive) port.setAudible(a)
    }

    /** Activity stopped: free the connection, keep the configuration. */
    fun suspend() {
        visible = false
        stopPlayer()
        setPhase(Phase.HIDDEN)
    }

    // ------------------------------------------------------------------ inputs from player

    fun onReady(g: Int) {
        if (g != gen || !playerActive) return
        stallTimer?.cancel(); stallTimer = null
        connectTimer?.cancel(); connectTimer = null
        rejAttempt = 0
        if (triedFallback && account?.format == OutputFormat.AUTO) {
            currentFormat?.let { f -> if (f != learned) { learned = f; listener.onFormatLearned(slot, f) } }
        }
        if (phase != Phase.PLAYING) {
            resetTimer?.cancel()
            resetTimer = scheduler.post(Backoff.RESET_AFTER_MS) { netAttempt = 0 }
        }
        setPhase(Phase.PLAYING)
    }

    fun onBuffering(g: Int) {
        if (g != gen || !playerActive) return
        if (phase == Phase.PLAYING && stallTimer == null) {
            stallTimer = scheduler.post(Backoff.STALL_MS) { stallTimer = null; if (g == gen) fail(FailKind.NETWORK) }
        }
    }

    fun onError(g: Int, kind: FailKind) {
        if (g != gen || !playerActive) return
        fail(kind)
    }

    /** A live stream should never end; treat it as a dropped connection. */
    fun onEnded(g: Int) = onError(g, FailKind.NETWORK)

    // ------------------------------------------------------------------ internals

    private fun fail(kind: FailKind) {
        val acc = account ?: return
        when (kind) {
            FailKind.DECODER -> { stopPlayer(); setPhase(Phase.DECODER_FULL) }
            FailKind.UNSUPPORTED -> { stopPlayer(); setPhase(Phase.UNSUPPORTED) }
            FailKind.REJECTED -> {
                stopPlayer()
                if (rejAttempt < Backoff.REJECTED_MS.size) {
                    val d = Backoff.REJECTED_MS[rejAttempt++]
                    setPhase(Phase.REJECTED_WAIT)
                    retryTimer = scheduler.post(d) { retryTimer = null; startPlayer(Phase.REJECTED_WAIT) }
                } else {
                    setPhase(Phase.REJECTED_STOPPED)
                }
            }
            FailKind.PARSE -> {
                if (acc.format == OutputFormat.AUTO && !triedFallback) {
                    triedFallback = true
                    stopPlayer()
                    currentFormat = currentFormat?.other()
                    startPlayer(Phase.CONNECTING, keepFormat = true)
                } else scheduleNetworkRetry()
            }
            FailKind.NETWORK -> scheduleNetworkRetry()
        }
    }

    private fun scheduleNetworkRetry() {
        stopPlayer()
        val d = Backoff.networkDelayMs(netAttempt++)
        setPhase(Phase.RECONNECTING)
        retryTimer = scheduler.post(d) { retryTimer = null; startPlayer(Phase.RECONNECTING, keepFormat = true) }
    }

    private fun resetAttempts() {
        netAttempt = 0
        rejAttempt = 0
        triedFallback = false
    }

    private fun restart() {
        stopPlayer()
        if (!visible) { setPhase(Phase.HIDDEN); return }
        when {
            account == null -> setPhase(Phase.NO_ACCOUNT)
            channel == null -> setPhase(Phase.NO_CHANNEL)
            else -> startPlayer(Phase.CONNECTING)
        }
    }

    private fun startPlayer(label: Phase, keepFormat: Boolean = false) {
        val acc = account ?: return
        val ch = channel ?: return
        if (!visible) return
        check(!playerActive) { "player must be released before a new one starts" }
        if (!keepFormat || currentFormat == null) currentFormat = StreamUrl.initialFormat(acc.format, learned, allowed)
        val fmt = currentFormat!!
        gen++
        val g = gen
        playerActive = true
        setPhase(label)
        port.start(g, StreamUrl.live(acc, ch.streamId, fmt), fmt == StreamFormat.HLS, audible)
        connectTimer = scheduler.post(Backoff.CONNECT_TIMEOUT_MS) {
            connectTimer = null
            if (g == gen && playerActive && phase != Phase.PLAYING) fail(FailKind.NETWORK)
        }
    }

    private fun stopPlayer() {
        retryTimer?.cancel(); retryTimer = null
        stallTimer?.cancel(); stallTimer = null
        resetTimer?.cancel(); resetTimer = null
        connectTimer?.cancel(); connectTimer = null
        gen++ // invalidate callbacks from the old player
        if (playerActive) {
            playerActive = false
            port.release()
        }
    }

    private fun setPhase(p: Phase) {
        if (p == phase) return
        phase = p
        listener.onStateChanged(slot)
    }
}
