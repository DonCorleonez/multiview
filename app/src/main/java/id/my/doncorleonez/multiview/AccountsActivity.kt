package id.my.doncorleonez.multiview

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import id.my.doncorleonez.multiview.core.AccountStore
import id.my.doncorleonez.multiview.core.AuthInfo
import id.my.doncorleonez.multiview.core.Grid
import id.my.doncorleonez.multiview.core.OutputFormat
import id.my.doncorleonez.multiview.core.Redact
import id.my.doncorleonez.multiview.core.StoreException
import id.my.doncorleonez.multiview.ui.Palette
import id.my.doncorleonez.multiview.ui.dp
import id.my.doncorleonez.multiview.ui.focusBackground
import id.my.doncorleonez.multiview.ui.label
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Four fixed account rows (§2.6). FLAG_SECURE: no screenshots of this screen. */
class AccountsActivity : Activity() {

    private lateinit var app: MultiviewApp
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private inner class Row(val slot: Int) {
        val label = field("Nama (mis. Akun ${slot + 1})")
        val server = field("Server, mis. http://host:8080", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field("Username")
        val pass = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        var format = OutputFormat.AUTO
        val formatBtn = button("") { format = format.next(); renderFormat() }
        val status = label(this@AccountsActivity, 15f, Palette.DIM)

        fun renderFormat() { formatBtn.text = "Format: ${format.label()}" }

        fun draft() = AccountStore.Draft(label.text.toString(), server.text.toString(), user.text.toString(), pass.text.toString(), format)

        fun load() {
            val d = app.accounts.draftOf(slot)
            label.setText(d.label)
            server.setText(app.accounts.load(slot)?.baseUrl ?: d.server)
            user.setText(d.username)
            pass.setText("")
            pass.hint = if (app.accounts.hasPassword(slot)) "Password tersimpan — isi hanya untuk mengganti" else "Password"
            format = d.format
            renderFormat()
        }
    }

    private val rows = ArrayList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        app = MultiviewApp.of(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(32)
            setPadding(p, dp(24), p, dp(24))
        }
        col.addView(label(this, 26f).apply { text = "Akun Xtream" })
        col.addView(label(this, 15f, Palette.DIM).apply {
            text = "Slot N selalu memakai Akun N. Satu akun = satu koneksi. Ketik lebih mudah lewat aplikasi Google TV di HP."
            setPadding(0, dp(4), 0, dp(12))
        })
        for (i in 0 until Grid.SLOTS) {
            val r = Row(i)
            rows.add(r)
            col.addView(section(r))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        })
        rows.forEach { it.load() }
        rows.first().label.requestFocus()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun section(r: Row): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        box.addView(label(this, 20f).apply { text = "Akun ${r.slot + 1}" })
        val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((v, w) in listOf(r.label to 0.18f, r.server to 0.34f, r.user to 0.22f, r.pass to 0.26f)) {
            fields.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w).apply { marginEnd = dp(8) })
        }
        box.addView(fields)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        buttons.addView(r.formatBtn)
        buttons.addView(button("Simpan") { save(r) })
        buttons.addView(button("Tes") { test(r) })
        buttons.addView(button("Hapus") { clear(r) })
        box.addView(buttons)
        box.addView(r.status)
        return box
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(0xFF777777.toInt())
        setTextColor(Color.WHITE)
        setSingleLine(true)
        inputType = type
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = focusBackground()
        val p = dp(10)
        setPadding(p, p, p, p)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = focusBackground()
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun save(r: Row) {
        try {
            val acc = app.accounts.resolve(r.slot, r.draft())
            app.accounts.save(acc)
            r.status.setTextColor(Palette.DIM)
            r.status.text = "Tersimpan. Daftar channel dimuat saat kembali ke layar utama."
            r.load()
            app.channels.ensure(r.slot, acc, force = true)
        } catch (e: StoreException) {
            r.status.setTextColor(0xFFFF8080.toInt())
            r.status.text = e.message
        }
    }

    private fun test(r: Row) {
        val acc = try { app.accounts.resolve(r.slot, r.draft()) } catch (e: StoreException) {
            r.status.setTextColor(0xFFFF8080.toInt()); r.status.text = e.message; return
        }
        r.status.setTextColor(Palette.DIM)
        r.status.text = "Mengetes ${Redact.hostOf(acc.baseUrl)}…"
        io.execute {
            val text = try {
                val a = app.xtream.auth(acc)
                if (a.ok && app.accounts.load(r.slot)?.fingerprint == acc.fingerprint) {
                    main.post { app.accounts.setAllowed(r.slot, a.allowedFormats) }
                }
                describe(a)
            } catch (e: Exception) {
                Redact.redact(e.message ?: "Gagal")
            }
            main.post { if (!isDestroyed) r.status.text = text }
        }
    }

    private fun describe(a: AuthInfo): String {
        if (!a.ok) return "Login ditolak (username/password salah atau akun nonaktif)"
        val exp = a.expUnixSec?.let {
            SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).apply { timeZone = TimeZone.getTimeZone("Asia/Jakarta") }.format(Date(it * 1000))
        } ?: "tanpa batas"
        val formats = if (a.allowedFormats.isEmpty()) "-" else a.allowedFormats.joinToString(", ")
        return "Status: ${a.status ?: "-"} · berlaku s/d $exp · koneksi maks: ${a.maxConnections ?: "-"} · aktif: ${a.activeConnections ?: "-"} · format: $formats"
    }

    private fun clear(r: Row) {
        app.accounts.clear(r.slot)
        app.state.clearSlot(r.slot)
        app.channels.clear(r.slot)
        r.load()
        r.label.setText("")
        r.status.setTextColor(Palette.DIM)
        r.status.text = "Akun ${r.slot + 1} dihapus."
    }
}
