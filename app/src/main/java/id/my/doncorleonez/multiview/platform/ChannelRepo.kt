package id.my.doncorleonez.multiview.platform

import android.os.Handler
import android.os.Looper
import id.my.doncorleonez.multiview.core.Account
import id.my.doncorleonez.multiview.core.AccountStore
import id.my.doncorleonez.multiview.core.ChannelList
import id.my.doncorleonez.multiview.core.Log
import id.my.doncorleonez.multiview.core.XtreamClient
import id.my.doncorleonez.multiview.core.XtreamException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Channel lists per slot: instant from the on-disk cache, refreshed in the background when older
 * than 24 h or on request (§4.3). All public methods and callbacks run on the main thread.
 */
class ChannelRepo(
    private val dir: File,
    private val client: XtreamClient,
    private val store: AccountStore,
) {
    fun interface Listener { fun onChannelsChanged(slot: Int) }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mv-io").apply { isDaemon = true } }

    private val lists = arrayOfNulls<ChannelList>(4)
    private val loadedFor = arrayOfNulls<String>(4)
    private val busyKey = arrayOfNulls<String>(4)
    private val errors = arrayOfNulls<String>(4)
    private val listeners = ArrayList<Listener>()

    /** Network refresh only while the app is in the foreground (§3). */
    @Volatile var foreground = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun list(slot: Int): ChannelList? = lists[slot]
    fun isBusy(slot: Int) = busyKey[slot] != null && busyKey[slot] == loadedFor[slot]
    fun error(slot: Int): String? = errors[slot]

    private fun key(acc: Account): String {
        val d = MessageDigest.getInstance("SHA-256").digest((acc.baseUrl + "|" + acc.username).toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(slot: Int, k: String) = File(dir, "acc$slot-$k.bin")

    /**
     * Make sure [slot] has a list for [acc]: load the cache if needed, refresh from the provider when
     * stale or [force]. A null account clears the slot.
     */
    fun ensure(slot: Int, acc: Account?, force: Boolean = false) {
        if (acc == null) {
            lists[slot] = null; loadedFor[slot] = null; errors[slot] = null
            notifyChanged(slot)
            return
        }
        val k = key(acc)
        if (loadedFor[slot] != k) { lists[slot] = null; loadedFor[slot] = k; errors[slot] = null }
        val have = lists[slot]
        val stale = have == null || System.currentTimeMillis() - have.fetchedAtMs > STALE_MS
        if (busyKey[slot] == k || (!force && !stale)) return
        busyKey[slot] = k
        errors[slot] = null
        notifyChanged(slot)
        val needDisk = have == null
        io.execute {
            var fromDisk: ChannelList? = null
            if (needDisk) {
                fromDisk = readCache(slot, k)
                fromDisk?.let { l ->
                    l.warm()
                    main.post { if (loadedFor[slot] == k) { lists[slot] = l; notifyChanged(slot) } }
                }
            }
            val diskFresh = fromDisk != null && System.currentTimeMillis() - fromDisk.fetchedAtMs <= STALE_MS
            if (!force && diskFresh) {
                main.post { finish(slot, k) }
                return@execute
            }
            if (!foreground) {
                main.post { finish(slot, k) }
                return@execute
            }
            try {
                val auth = client.auth(acc)
                if (!auth.ok) throw XtreamException("Login ditolak provider (cek username/password)")
                main.post { store.setAllowed(slot, auth.allowedFormats) }
                val fresh = client.channelList(acc)
                fresh.warm()
                writeCache(slot, k, fresh)
                Log.i(TAG, "slot ${slot + 1}: ${fresh.size} channels, ${fresh.categories.size} categories")
                main.post {
                    if (loadedFor[slot] == k) { lists[slot] = fresh; errors[slot] = null }
                    finish(slot, k)
                }
            } catch (e: Exception) {
                val msg = (e as? XtreamException)?.message ?: "Gagal memuat daftar channel"
                Log.w(TAG, "slot ${slot + 1} refresh failed", e)
                main.post { if (loadedFor[slot] == k) errors[slot] = msg; finish(slot, k) }
            }
        }
    }

    private fun finish(slot: Int, k: String) {
        if (busyKey[slot] == k) busyKey[slot] = null
        if (loadedFor[slot] == k) notifyChanged(slot)
    }

    /** Forget everything stored for [slot] (account removed). */
    fun clear(slot: Int) {
        lists[slot] = null; loadedFor[slot] = null; errors[slot] = null; busyKey[slot] = null
        io.execute { dir.listFiles { f -> f.name.startsWith("acc$slot-") }?.forEach { it.delete() } }
        notifyChanged(slot)
    }

    private fun readCache(slot: Int, k: String): ChannelList? {
        val f = cacheFile(slot, k)
        if (!f.exists()) return null
        return try { f.inputStream().use { ChannelList.readFrom(it) } } catch (e: Exception) {
            Log.w(TAG, "cache for slot ${slot + 1} unreadable", e); f.delete(); null
        }
    }

    private fun writeCache(slot: Int, k: String, l: ChannelList) {
        try {
            dir.mkdirs()
            // drop caches of other servers/users for this slot
            dir.listFiles { f -> f.name.startsWith("acc$slot-") && f.name != "acc$slot-$k.bin" }?.forEach { it.delete() }
            val tmp = File(dir, "acc$slot-$k.tmp")
            tmp.outputStream().use { l.writeTo(it) }
            if (!tmp.renameTo(cacheFile(slot, k))) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed", e)
        }
    }

    private fun notifyChanged(slot: Int) { for (l in listeners.toList()) l.onChannelsChanged(slot) }

    companion object {
        private const val TAG = "Channels"
        const val STALE_MS = 24L * 60 * 60 * 1000
    }
}
