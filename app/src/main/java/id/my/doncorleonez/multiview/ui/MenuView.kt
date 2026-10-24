package id.my.doncorleonez.multiview.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView

/** Long-press-OK menu: layouts, accounts, reload channel lists, exit (§2.4). */
class MenuView(context: Context, private val onAction: (Action) -> Unit) : LinearLayout(context) {

    sealed class Action {
        data class SetLayout(val layout: Int) : Action()
        object Accounts : Action()
        object Reload : Action()
        object Exit : Action()
    }

    private var currentLayout = 4
    private val items: List<Pair<() -> String, Action>> = listOf(
        { mark(1) + "Layout 1 · layar penuh" } to Action.SetLayout(1),
        { mark(2) + "Layout 2" } to Action.SetLayout(2),
        { mark(3) + "Layout 3" } to Action.SetLayout(3),
        { mark(4) + "Layout 4" } to Action.SetLayout(4),
        { "Akun" } to Action.Accounts,
        { "Muat ulang daftar channel" } to Action.Reload,
        { "Keluar" } to Action.Exit,
    )

    private fun mark(l: Int) = if (l == currentLayout) "● " else "   "

    private val adapter = object : TextAdapter(context) {
        override fun getCount() = items.size
        override fun text(position: Int) = items[position].first()
    }

    val list = ListView(context).apply {
        this.adapter = this@MenuView.adapter
        selector = ColorDrawable(Palette.FOCUS)
        divider = null
        isVerticalScrollBarEnabled = false
        setOnItemClickListener { _, _, pos, _ -> onAction(items[pos].second) }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.PANEL)
        val p = context.dp(16)
        setPadding(p, p, p, p)
        addView(label(context, 22f).apply { text = "Menu"; setPadding(context.dp(10), 0, 0, context.dp(10)) })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        layoutParams = FrameLayout.LayoutParams(context.dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            leftMargin = context.dp(48)
        }
    }

    fun show(layout: Int) {
        currentLayout = layout
        adapter.notifyDataSetChanged()
        visibility = VISIBLE
        list.requestFocus()
        list.setSelection(layout - 1)
    }
}
