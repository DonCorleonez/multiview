package id.my.doncorleonez.multiview.platform

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import id.my.doncorleonez.multiview.core.Cancellable
import id.my.doncorleonez.multiview.core.KeyValue
import id.my.doncorleonez.multiview.core.Logger
import id.my.doncorleonez.multiview.core.Scheduler
import id.my.doncorleonez.multiview.core.SecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PrefsKV(private val prefs: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(changes: Map<String, String?>) {
        val e = prefs.edit()
        for ((k, v) in changes) if (v == null) e.remove(k) else e.putString(k, v)
        e.apply()
    }
}

/**
 * AES-256-GCM with a non-exportable key in Android Keystore (hardware-backed on Google TV Streamer).
 * Sealed format: base64(iv) + ":" + base64(ciphertext+tag). Any failure throws — callers refuse to save.
 */
class KeystoreBox(private val alias: String = "multiview.accounts.v1") : SecretBox {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String {
        val iv = Base64.decode(sealed.substringBefore(':'), Base64.NO_WRAP)
        val ct = Base64.decode(sealed.substringAfter(':'), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}

/** logcat sink. Messages arrive already redacted (core.Log). */
class AndroidLogger : Logger {
    override fun i(tag: String, msg: String) { android.util.Log.i("MV/$tag", msg) }
    override fun w(tag: String, msg: String) { android.util.Log.w("MV/$tag", msg) }
}

class MainScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun post(delayMs: Long, block: () -> Unit): Cancellable {
        val r = Runnable { block() }
        handler.postDelayed(r, delayMs)
        return object : Cancellable { override fun cancel() = handler.removeCallbacks(r) }
    }
}
