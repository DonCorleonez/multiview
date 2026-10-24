package id.my.doncorleonez.multiview.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import id.my.doncorleonez.multiview.core.ChannelList
import id.my.doncorleonez.multiview.core.ChannelRef
import java.util.concurrent.Executors

/**
 * Channel picker overlay on the right 40 % (§2.5): search, categories (Semua, Terakhir, provider
 * categories), channels. Filtering runs off the main thread; stale results are dropped.
 */
class PickerView(context: Context, private val onPick: (slot: Int, ref: ChannelRef) -> Unit) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mv-filter").apply { isDaemon = true } }

    var slot = -1; private set
    private var list: ChannelList? = null
    private var history: List<ChannelRef> = emptyList()
    private var current: ChannelRef? = null
    private var category = 0 // 0 = Semua, 1 = Terakhir, n+2 = provider category n
    private var seq = 0

    // shown channels: indices into `list`, or explicit refs (history)
    private var shownIdx: IntArray = IntArray(0)
    private var shownRefs: List<ChannelRef>? = null

    private val header = label(context, 20f)
    private val info = label(context, 15f, Palette.DIM)

    val search = EditText(context).apply {
        id = View.generateViewId()
        hint = "Cari channel…"
        setHintTextColor(Palette.DIM)
        setTextColor(Palette.TEXT)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = focusBackground()
        val p = context.dp(10)
        setPadding(p, p, p, p)
    }

    private val catAdapter = object : TextAdapter(context) {
        override fun getCount() = 2 + (list?.categories?.size ?: 0)
        override fun text(position: Int) = when (position) {
            0 -> "Semua"
            1 -> "Terakhir"
            else -> list?.categories?.getOrNull(position - 2)?.name ?: ""
        }
    }

    private val chAdapter = object : TextAdapter(context) {
        override fun getCount() = shownRefs?.size ?: shownIdx.size
        override fun text(position: Int): String {
            val r = refAt(position)
            return (if (r != null && r.streamId == current?.streamId) "● " else "") + (r?.name ?: "")
        }
    }

    val categories = ListView(context).apply {
        id = View.generateViewId()
        adapter = catAdapter
        selector = ColorDrawable(Palette.FOCUS)
        divider = null
        isVerticalScrollBarEnabled = false
    }

    val channels = ListView(context).apply {
        id = View.generateViewId()
        adapter = chAdapter
        selector = ColorDrawable(Palette.FOCUS)
        divider = null
        isFastScrollEnabled = true
    }

    private val searchDebounce = Runnable { refilter() }
    private val categoryDebounce = Runnable { refilter() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.PANEL)
        val p = context.dp(16)
        setPadding(p, p, p, p)
        addView(header)
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(categories, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.38f))
        row.addView(View(context).apply { setBackgroundColor(0x33FFFFFF) }, LayoutParams(context.dp(1), LayoutParams.MATCH_PARENT))
        row.addView(channels, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.62f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) })

        search.nextFocusDownId = categories.id
        categories.nextFocusUpId = search.id
        channels.nextFocusUpId = search.id
        categories.nextFocusRightId = channels.id
        channels.nextFocusLeftId = categories.id

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(searchDebounce)
                main.postDelayed(searchDebounce, 250)
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            channels.requestFocus()
            true
        }

        categories.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == category) return
                category = position
                if (search.text.isEmpty()) {
                    main.removeCallbacks(categoryDebounce)
                    main.postDelayed(categoryDebounce, 150)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        categories.setOnItemClickListener { _, _, position, _ ->
            category = position
            if (search.text.isNotEmpty()) search.setText("")
            refilter { channels.requestFocus() }
        }
        channels.setOnItemClickListener { _, _, position, _ ->
            val r = refAt(position) ?: return@setOnItemClickListener
            onPick(slot, r)
        }

        layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
        visibility = GONE
    }

    private fun refAt(position: Int): ChannelRef? {
        shownRefs?.let { return it.getOrNull(position) }
        val l = list ?: return null
        val i = shownIdx.getOrNull(position) ?: return null
        return l.ref(i)
    }

    /** Opens for [slot]. Width is set by the caller (40 % of the screen). */
    fun open(slot: Int, label: String, list: ChannelList?, history: List<ChannelRef>, current: ChannelRef?, status: String?) {
        this.slot = slot
        this.list = list
        this.history = history
        this.current = current
        header.text = "$label · pilih channel"
        search.setText("")
        main.removeCallbacks(searchDebounce)
        // start on the current channel's category, or Semua
        val curIdx = if (list != null && current != null) list.indexOfId(current.streamId) else -1
        category = if (curIdx >= 0 && list!!.categoryIndex[curIdx] >= 0) list.categoryIndex[curIdx] + 2 else 0
        catAdapter.notifyDataSetChanged()
        setStatus(status)
        visibility = VISIBLE
        categories.setSelection(category)
        refilter {
            val pos = current?.let { c -> positionOf(c.streamId) } ?: -1
            if (pos >= 0) {
                channels.requestFocus()
                channels.setSelection(pos)
            } else {
                categories.requestFocus()
                categories.setSelection(category)
            }
        }
    }

    /** List finished loading/refreshing while the picker is open. */
    fun update(list: ChannelList?, status: String?) {
        if (visibility != VISIBLE) return
        val changed = list !== this.list
        this.list = list
        setStatus(status)
        if (changed) {
            if (category >= 2 + (list?.categories?.size ?: 0)) category = 0
            catAdapter.notifyDataSetChanged()
            refilter()
        }
    }

    private var externalStatus: String? = null

    private fun setStatus(status: String?) {
        externalStatus = status
        showInfo()
    }

    private fun showInfo() {
        val l = list
        info.text = externalStatus ?: if (l == null) "Belum ada daftar channel" else "${l.size} channel"
    }

    fun close() {
        hideKeyboard()
        visibility = GONE
        slot = -1
    }

    private fun positionOf(streamId: Int): Int {
        shownRefs?.let { refs -> return refs.indexOfFirst { it.streamId == streamId } }
        val l = list ?: return -1
        val i = l.indexOfId(streamId)
        if (i < 0) return -1
        for (p in shownIdx.indices) if (shownIdx[p] == i) return p
        return -1
    }

    private fun refilter(then: (() -> Unit)? = null) {
        val my = ++seq
        val l = list
        val q = search.text.toString()
        val cat = category
        val hist = history
        if (q.isBlank() && cat == 1) {
            shownRefs = hist; shownIdx = IntArray(0)
            chAdapter.notifyDataSetChanged()
            then?.invoke()
            return
        }
        if (l == null) {
            shownRefs = null; shownIdx = IntArray(0)
            chAdapter.notifyDataSetChanged()
            then?.invoke()
            return
        }
        worker.execute {
            val idx = when {
                q.isNotBlank() -> l.search(q)
                cat == 0 -> l.all()
                else -> l.inCategory(cat - 2)
            }
            main.post {
                if (my != seq) return@post
                shownRefs = null; shownIdx = idx
                chAdapter.notifyDataSetChanged()
                if (q.isNotBlank()) info.text = "${idx.size} hasil untuk \"$q\""
                else showInfo()
                channels.setSelection(0)
                then?.invoke()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }
}
