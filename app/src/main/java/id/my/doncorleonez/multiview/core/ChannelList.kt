package id.my.doncorleonez.multiview.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.Locale

/**
 * All live channels of one account, stored column-wise (parallel arrays) so 30 000+ entries stay
 * compact and filter fast. Immutable once built.
 */
class ChannelList(
    val categories: List<Category>,
    val ids: IntArray,
    val names: Array<String>,
    /** index into [categories], or -1 when the stream's category is unknown */
    val categoryIndex: IntArray,
    val fetchedAtMs: Long,
) {
    val size: Int get() = ids.size

    /** Search keys, computed once. */
    private val keys: Array<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Array(names.size) { Search.normalize(names[it]) }
    }
    private val idToIndex: HashMap<Int, Int> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HashMap<Int, Int>(ids.size * 2).also { m -> for (i in ids.indices) m[ids[i]] = i }
    }

    fun indexOfId(streamId: Int): Int = idToIndex[streamId] ?: -1
    fun contains(streamId: Int): Boolean = indexOfId(streamId) >= 0

    /** Warm the lazy indexes off the main thread. */
    fun warm() { keys.size; idToIndex.size }

    /** Channel indices of a category, in provider order. */
    fun inCategory(catIdx: Int): IntArray {
        var n = 0
        for (c in categoryIndex) if (c == catIdx) n++
        val out = IntArray(n)
        var j = 0
        for (i in categoryIndex.indices) if (categoryIndex[i] == catIdx) out[j++] = i
        return out
    }

    fun all(): IntArray = IntArray(size) { it }

    /** Indices whose normalised name contains the normalised query (all categories). */
    fun search(query: String): IntArray {
        val q = Search.normalize(query)
        if (q.isEmpty()) return all()
        val k = keys
        val tmp = IntArray(k.size)
        var n = 0
        for (i in k.indices) if (k[i].contains(q)) tmp[n++] = i
        return tmp.copyOf(n)
    }

    fun ref(i: Int) = ChannelRef(ids[i], names[i])

    // ---- compact binary cache ----
    fun writeTo(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeLong(fetchedAtMs)
        d.writeInt(categories.size)
        for (c in categories) { d.writeUTF(c.id.take(MAX_UTF)); d.writeUTF(c.name.take(MAX_UTF)) }
        d.writeInt(ids.size)
        for (i in ids.indices) { d.writeInt(ids[i]); d.writeUTF(names[i].take(MAX_UTF)); d.writeInt(categoryIndex[i]) }
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x4D564331 // "MVC1"
        private const val MAX_UTF = 8000

        fun readFrom(input: InputStream): ChannelList {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "bad cache" }
            val ts = d.readLong()
            val nc = d.readInt()
            require(nc in 0..100_000)
            val cats = ArrayList<Category>(nc)
            repeat(nc) { cats.add(Category(d.readUTF(), d.readUTF())) }
            val n = d.readInt()
            require(n in 0..2_000_000)
            val ids = IntArray(n)
            val names = arrayOfNulls<String>(n)
            val ci = IntArray(n)
            for (i in 0 until n) { ids[i] = d.readInt(); names[i] = d.readUTF(); ci[i] = d.readInt() }
            @Suppress("UNCHECKED_CAST")
            return ChannelList(cats, ids, names as Array<String>, ci, ts)
        }

        /** Builds the list from provider rows; drops rows without a numeric id. Keeps provider order. */
        fun build(categories: List<Category>, rows: List<StreamRow>, fetchedAtMs: Long): ChannelList {
            val catPos = HashMap<String, Int>(categories.size * 2)
            categories.forEachIndexed { i, c -> catPos[c.id] = i }
            val seen = HashSet<Int>(rows.size * 2)
            val kept = rows.filter { it.id > 0 && seen.add(it.id) }
            return ChannelList(
                categories,
                IntArray(kept.size) { kept[it].id },
                Array(kept.size) { kept[it].name.ifBlank { "Channel ${kept[it].id}" } },
                IntArray(kept.size) { catPos[kept[it].categoryId] ?: -1 },
                fetchedAtMs,
            )
        }
    }
}

class StreamRow(val id: Int, val name: String, val categoryId: String?)

object Search {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        return spaces.replace(marks.replace(decomposed, ""), " ").trim().lowercase(Locale.ROOT)
    }
}
