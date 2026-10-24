package id.my.doncorleonez.multiview

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import id.my.doncorleonez.multiview.core.ChannelRef
import id.my.doncorleonez.multiview.core.Dir
import id.my.doncorleonez.multiview.core.FailKind
import id.my.doncorleonez.multiview.core.Grid
import id.my.doncorleonez.multiview.core.SlotEngine
import id.my.doncorleonez.multiview.core.StreamFormat
import id.my.doncorleonez.multiview.core.Wall
import id.my.doncorleonez.multiview.platform.ChannelRepo
import id.my.doncorleonez.multiview.platform.MainScheduler
import id.my.doncorleonez.multiview.player.Media3Port
import id.my.doncorleonez.multiview.player.PortEvents
import id.my.doncorleonez.multiview.ui.MenuView
import id.my.doncorleonez.multiview.ui.PickerView
import id.my.doncorleonez.multiview.ui.TileView

/**
 * The only screen in normal use: 1–4 tiles, highlight with D-pad, OK = channel picker,
 * long-press OK = menu, Back twice = exit (§2).
 */
class MainActivity : Activity(), SlotEngine.Listener, ChannelRepo.Listener {

    private lateinit var app: MultiviewApp
    private lateinit var root: FrameLayout
    private lateinit var tiles: List<TileView>
    private lateinit var engines: List<SlotEngine>
    private lateinit var wall: Wall
    private lateinit var picker: PickerView
    private lateinit var menu: MenuView

    private var okLongPressed = false
    private var swallowNextOkUp = false
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = MultiviewApp.of(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val scheduler = MainScheduler()
        val ua = "Multiview/${BuildConfig.VERSION_NAME}"
        tiles = List(Grid.SLOTS) { TileView(this) }
        engines = List(Grid.SLOTS) { i ->
            val port = Media3Port(this, i, tiles[i].playerView, ua)
            val engine = SlotEngine(i, port, scheduler, this)
            port.events = object : PortEvents {
                override fun onReady(gen: Int) = engine.onReady(gen)
                override fun onBuffering(gen: Int) = engine.onBuffering(gen)
                override fun onError(gen: Int, kind: FailKind) = engine.onError(gen, kind)
                override fun onEnded(gen: Int) = engine.onEnded(gen)
            }
            engine
        }
        tiles.forEach { root.addView(it, FrameLayout.LayoutParams(0, 0)) }

        picker = PickerView(this) { slot, ref -> onChannelPicked(slot, ref) }
        menu = MenuView(this) { onMenu(it) }.apply { visibility = View.GONE }
        root.addView(picker)
        root.addView(menu)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) root.post { layoutTiles() }
        }
        setContentView(root)
        hideSystemBars()

        wall = Wall(engines, app.state.layout, app.state.highlighted)
    }

    override fun onStart() {
        super.onStart()
        app.channels.foreground = true
        app.channels.addListener(this)
        configureAccounts()
        wall.start()
        layoutTiles()
        refreshStatuses()
    }

    override fun onStop() {
        wall.stop() // releases every player → provider connections freed (§6.1)
        app.channels.removeListener(this)
        app.channels.foreground = false
        closeOverlays()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- accounts & channels

    private fun configureAccounts() {
        for (i in 0 until Grid.SLOTS) {
            val acc = app.accounts.load(i)
            engines[i].restoreChannel(app.state.lastChannel(i))
            engines[i].configure(acc, app.accounts.learned(i), app.accounts.allowed(i))
            app.channels.ensure(i, acc)
        }
    }

    override fun onChannelsChanged(slot: Int) {
        val list = app.channels.list(slot)
        // A remembered channel that disappeared after a successful refresh → "Pilih channel" (§4.3)
        val ch = engines[slot].channel
        if (list != null && list.size > 0 && ch != null && !list.contains(ch.streamId) && !app.channels.isBusy(slot)) {
            engines[slot].restoreChannel(null)
            app.state.setLastChannel(slot, null)
        }
        if (picker.visibility == View.VISIBLE && picker.slot == slot) picker.update(list, pickerStatus(slot))
    }

    private fun pickerStatus(slot: Int): String? = when {
        app.channels.isBusy(slot) && app.channels.list(slot) == null -> "Memuat daftar channel…"
        app.channels.isBusy(slot) -> null
        else -> app.channels.error(slot)
    }

    // ---------------------------------------------------------------- engine callbacks

    override fun onStateChanged(slot: Int) {
        tiles[slot].setStatus(engines[slot].statusText())
    }

    override fun onFormatLearned(slot: Int, format: StreamFormat) {
        app.accounts.setLearned(slot, format)
    }

    private fun refreshStatuses() {
        for (i in 0 until Grid.SLOTS) {
            tiles[i].setStatus(engines[i].statusText())
            tiles[i].setHighlighted(i == wall.highlighted && wall.layout != 1)
        }
    }

    // ---------------------------------------------------------------- layout

    private fun layoutTiles() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        for (i in 0 until Grid.SLOTS) {
            val r = Grid.rect(wall.layout, i, wall.highlighted, w, h)
            val t = tiles[i]
            if (r == null) {
                t.visibility = View.GONE
            } else {
                t.layoutParams = FrameLayout.LayoutParams(r.w, r.h).apply { leftMargin = r.x; topMargin = r.y }
                t.visibility = View.VISIBLE
            }
        }
        picker.layoutParams = (picker.layoutParams as FrameLayout.LayoutParams).apply { width = (w * 0.4f).toInt() }
        refreshStatuses()
    }

    private fun setLayout(l: Int) {
        wall.setLayout(l)
        app.state.layout = wall.layout
        app.state.highlighted = wall.highlighted
        layoutTiles()
    }

    private fun move(dir: Dir) {
        if (wall.move(dir)) {
            app.state.highlighted = wall.highlighted
            refreshStatuses()
        }
    }

    // ---------------------------------------------------------------- overlays

    private fun overlayOpen() = picker.visibility == View.VISIBLE || menu.visibility == View.VISIBLE

    private fun closeOverlays() {
        if (picker.visibility == View.VISIBLE) picker.close()
        menu.visibility = View.GONE
    }

    private fun openPicker() {
        val s = wall.highlighted
        val acc = engines[s].account
        if (acc == null) {
            Toast.makeText(this, "Akun ${s + 1} belum diisi — buka Menu › Akun", Toast.LENGTH_SHORT).show()
            return
        }
        app.channels.ensure(s, acc)
        menu.visibility = View.GONE
        picker.open(s, acc.label, app.channels.list(s), app.state.history(s), engines[s].channel, pickerStatus(s))
    }

    private fun onChannelPicked(slot: Int, ref: ChannelRef) {
        picker.close()
        app.state.setLastChannel(slot, ref)
        app.state.pushHistory(slot, ref)
        engines[slot].play(ref) // releases the old player first (§6.5)
    }

    private fun onMenu(a: MenuView.Action) {
        when (a) {
            is MenuView.Action.SetLayout -> { menu.visibility = View.GONE; setLayout(a.layout) }
            MenuView.Action.Accounts -> { menu.visibility = View.GONE; startActivity(Intent(this, AccountsActivity::class.java)) }
            MenuView.Action.Reload -> {
                menu.visibility = View.GONE
                for (i in 0 until Grid.SLOTS) engines[i].account?.let { app.channels.ensure(i, it, force = true) }
                Toast.makeText(this, "Memuat ulang daftar channel…", Toast.LENGTH_SHORT).show()
            }
            MenuView.Action.Exit -> finish()
        }
    }

    // ---------------------------------------------------------------- keys

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // The OK release that ends a long press must not click the freshly opened menu.
        if (swallowNextOkUp && isOk(code) && event.action == KeyEvent.ACTION_UP) {
            swallowNextOkUp = false
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onBack() {
        if (overlayOpen()) { closeOverlays(); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2_000) {
            finish()
        } else {
            lastBackAt = now
            Toast.makeText(this, "Tekan Kembali sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlayOpen()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(Dir.LEFT); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(Dir.RIGHT); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(Dir.UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(Dir.DOWN); return true }
            KeyEvent.KEYCODE_MENU -> { menu.show(wall.layout); return true }
        }
        if (isOk(keyCode)) {
            if (event.repeatCount == 0) { okLongPressed = false; event.startTracking() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode) && !overlayOpen()) {
            okLongPressed = true
            swallowNextOkUp = true
            picker.close()
            menu.show(wall.layout)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode) && !overlayOpen()) {
            if (!okLongPressed && event.isTracking && !event.isCanceled) openPicker()
            okLongPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
