package id.my.doncorleonez.multiview.core

/** Manual clock scheduler. */
class FakeScheduler : Scheduler {
    var now = 0L
    private class Task(val at: Long, val seq: Int, val block: () -> Unit) { var cancelled = false }
    private val tasks = ArrayList<Task>()
    private var seq = 0

    override fun post(delayMs: Long, block: () -> Unit): Cancellable {
        val t = Task(now + delayMs, seq++, block)
        tasks.add(t)
        return object : Cancellable { override fun cancel() { t.cancelled = true } }
    }

    fun pending(): List<Long> = tasks.filter { !it.cancelled }.map { it.at - now }.sorted()

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.at
            next.block()
        }
        now = end
        tasks.removeAll { it.cancelled }
    }
}

/** Records player calls; enforces "at most one live player" like a 1-connection provider would. */
class FakePort(val name: String = "port") : PlayerPort {
    val events = ArrayList<String>()
    var active = false
    var lastGen = -1
    var lastUrl: String? = null
    var audioOn = false
    var starts = 0
    var maxConcurrent = 0

    override fun start(gen: Int, url: String, hls: Boolean, audible: Boolean) {
        if (active) throw AssertionError("$name: start while previous player still active (two connections)")
        active = true
        lastGen = gen
        lastUrl = url
        this.audioOn = audible
        starts++
        maxConcurrent = maxOf(maxConcurrent, 1)
        events.add("start:" + (if (hls) "hls" else "ts") + ":" + (if (audible) "a" else "m"))
    }

    override fun setAudible(audible: Boolean) {
        this.audioOn = audible
        events.add("audible:$audible")
    }

    override fun release() {
        if (!active) throw AssertionError("$name: release without player")
        active = false
        audioOn = false
        events.add("release")
    }
}

class MemKV : KeyValue {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(changes: Map<String, String?>) {
        for ((k, v) in changes) if (v == null) map.remove(k) else map[k] = v
    }
}

class XorBox : SecretBox {
    override fun seal(plain: String) = "sealed:" + plain.reversed()
    override fun open(sealed: String): String {
        require(sealed.startsWith("sealed:"))
        return sealed.removePrefix("sealed:").reversed()
    }
}

class FailingBox : SecretBox {
    override fun seal(plain: String): String = throw java.security.KeyStoreException("fault injected")
    override fun open(sealed: String): String = throw java.security.KeyStoreException("fault injected")
}

class CapturingLogger : Logger {
    val lines = ArrayList<String>()
    override fun i(tag: String, msg: String) { synchronized(lines) { lines.add("I/$tag $msg") } }
    override fun w(tag: String, msg: String) { synchronized(lines) { lines.add("W/$tag $msg") } }
}

fun account(slot: Int = 0, fmt: OutputFormat = OutputFormat.AUTO, base: String = "http://prov.example:8080", pw: String = "pw-$slot") =
    Account(slot, "Akun ${slot + 1}", base, "user$slot", pw, fmt)
