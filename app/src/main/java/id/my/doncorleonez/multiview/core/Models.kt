package id.my.doncorleonez.multiview.core

/** Output format chosen by the owner per account. */
enum class OutputFormat { AUTO, TS, HLS;
    fun label() = when (this) { AUTO -> "Otomatis"; TS -> "TS"; HLS -> "HLS" }
    fun next() = values()[(ordinal + 1) % values().size]
}

/** Concrete container used for a playback URL. */
enum class StreamFormat(val ext: String) { TS("ts"), HLS("m3u8");
    fun other() = if (this == TS) HLS else TS
}

/**
 * One Xtream account. [baseUrl] is scheme://host[:port] without trailing slash.
 * toString() never prints credentials.
 */
class Account(
    val slot: Int,
    val label: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val format: OutputFormat,
) {
    /** Stable identity used to detect "account changed → restart slot / reload channels". */
    val fingerprint: String get() = "$baseUrl|$username|${password.hashCode()}|$format"
    override fun toString() = "Account(slot=$slot, label=$label, host=${Redact.hostOf(baseUrl)})"
}

data class Category(val id: String, val name: String)

/** Result of player_api.php auth. */
data class AuthInfo(
    val ok: Boolean,
    val status: String?,
    val expUnixSec: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val allowedFormats: List<String>,
    val serverTimezone: String?,
)

/** A channel the user picked for a slot (persisted). */
data class ChannelRef(val streamId: Int, val name: String)
