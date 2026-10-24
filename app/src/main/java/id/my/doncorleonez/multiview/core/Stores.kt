package id.my.doncorleonez.multiview.core

import java.net.URLEncoder

/** Key-value seam over SharedPreferences (in-memory map in tests). */
interface KeyValue {
    fun get(key: String): String?
    /** Applies all changes atomically; null value removes the key. */
    fun put(changes: Map<String, String?>)
}

/** Encryption seam over Android Keystore AES-GCM. Throws on any failure. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String
}

class StoreException(message: String) : Exception(message)

/**
 * Four fixed account slots. Non-secret fields in plain prefs; the password only as Keystore-sealed
 * ciphertext. If sealing fails, nothing is written (§5, A-11).
 */
class AccountStore(private val kv: KeyValue, private val box: SecretBox) {

    data class Draft(val label: String, val server: String, val username: String, val password: String?, val format: OutputFormat)

    fun load(slot: Int): Account? {
        val p = "acc$slot."
        val base = kv.get(p + "base") ?: return null
        val user = kv.get(p + "user") ?: return null
        val sealed = kv.get(p + "pw") ?: return null
        val pw = try { box.open(sealed) } catch (e: Exception) {
            Log.w("AccountStore", "cannot decrypt password for slot ${slot + 1}", e)
            return null
        }
        registerSecrets(user, pw)
        val fmt = runCatching { OutputFormat.valueOf(kv.get(p + "fmt") ?: "AUTO") }.getOrDefault(OutputFormat.AUTO)
        return Account(slot, kv.get(p + "label") ?: "Akun ${slot + 1}", base, user, pw, fmt)
    }

    fun hasPassword(slot: Int) = kv.get("acc$slot.pw") != null

    /** Label/server/user shown in the editor (never the password). */
    fun draftOf(slot: Int): Draft {
        val p = "acc$slot."
        return Draft(
            kv.get(p + "label") ?: "Akun ${slot + 1}",
            kv.get(p + "base") ?: "",
            kv.get(p + "user") ?: "",
            null,
            runCatching { OutputFormat.valueOf(kv.get(p + "fmt") ?: "AUTO") }.getOrDefault(OutputFormat.AUTO),
        )
    }

    /** Validates a draft and turns it into an Account; [Draft.password] null/blank = keep stored one. */
    fun resolve(slot: Int, d: Draft): Account {
        val base = StreamUrl.normalizeBase(d.server) ?: throw StoreException("Alamat server tidak valid (contoh: http://host:8080)")
        val user = d.username.trim()
        if (user.isEmpty()) throw StoreException("Username kosong")
        val pw = d.password?.takeIf { it.isNotEmpty() } ?: run {
            val sealed = kv.get("acc$slot.pw") ?: throw StoreException("Password kosong")
            try { box.open(sealed) } catch (e: Exception) { throw StoreException("Password tersimpan tidak bisa dibuka, isi ulang") }
        }
        registerSecrets(user, pw)
        return Account(slot, d.label.trim().ifEmpty { "Akun ${slot + 1}" }, base, user, pw, d.format)
    }

    fun save(acc: Account) {
        val sealed = try { box.seal(acc.password) } catch (e: Exception) {
            Log.w("AccountStore", "keystore seal failed", e)
            throw StoreException("Penyimpanan aman (Keystore) gagal — akun tidak disimpan")
        }
        val p = "acc${acc.slot}."
        val old = kv.get(p + "base") + "|" + kv.get(p + "user")
        val changes = mutableMapOf<String, String?>(
            p + "label" to acc.label, p + "base" to acc.baseUrl, p + "user" to acc.username,
            p + "pw" to sealed, p + "fmt" to acc.format.name,
        )
        if (old != acc.baseUrl + "|" + acc.username) {
            // different server/user: learned format and allowed formats no longer apply
            changes[p + "learned"] = null
            changes[p + "allowed"] = null
        }
        kv.put(changes)
    }

    fun clear(slot: Int) {
        val p = "acc$slot."
        kv.put(listOf("label", "base", "user", "pw", "fmt", "learned", "allowed").associate { p + it to null })
    }

    fun learned(slot: Int): StreamFormat? = kv.get("acc$slot.learned")?.let { runCatching { StreamFormat.valueOf(it) }.getOrNull() }
    fun setLearned(slot: Int, f: StreamFormat) = kv.put(mapOf("acc$slot.learned" to f.name))
    fun allowed(slot: Int): List<String> = kv.get("acc$slot.allowed")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    fun setAllowed(slot: Int, formats: List<String>) = kv.put(mapOf("acc$slot.allowed" to formats.joinToString(",")))

    private fun registerSecrets(user: String, pw: String) {
        Redact.register(pw)
        Redact.register(URLEncoder.encode(pw, "UTF-8"))
        Redact.register(URLEncoder.encode(pw, "UTF-8").replace("+", "%20"))
        // usernames are less secret but still part of the stream path
        if (user.length >= 4) Redact.register(user)
    }
}

/** Non-secret UI state (§6.7). */
class ViewState(private val kv: KeyValue) {
    var layout: Int
        get() = kv.get("layout")?.toIntOrNull()?.takeIf { it in 1..4 } ?: 4
        set(v) = kv.put(mapOf("layout" to v.toString()))

    var highlighted: Int
        get() = kv.get("highlight")?.toIntOrNull()?.takeIf { it in 0..3 } ?: 0
        set(v) = kv.put(mapOf("highlight" to v.toString()))

    fun lastChannel(slot: Int): ChannelRef? {
        val id = kv.get("ch$slot.id")?.toIntOrNull() ?: return null
        return ChannelRef(id, kv.get("ch$slot.name") ?: "")
    }

    fun setLastChannel(slot: Int, ref: ChannelRef?) {
        kv.put(mapOf("ch$slot.id" to ref?.streamId?.toString(), "ch$slot.name" to ref?.name))
    }

    fun history(slot: Int): List<ChannelRef> =
        kv.get("ch$slot.hist").orEmpty().split('\n').mapNotNull { line ->
            val id = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
            ChannelRef(id, line.substringAfter('\t', ""))
        }

    /** Most recent first, unique, max 20. */
    fun pushHistory(slot: Int, ref: ChannelRef) {
        val list = (listOf(ref) + history(slot).filter { it.streamId != ref.streamId }).take(20)
        kv.put(mapOf("ch$slot.hist" to list.joinToString("\n") { "${it.streamId}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" }))
    }

    fun clearSlot(slot: Int) = kv.put(mapOf("ch$slot.id" to null, "ch$slot.name" to null, "ch$slot.hist" to null))
}
