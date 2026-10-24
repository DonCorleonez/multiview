package id.my.doncorleonez.multiview.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

object Palette {
    const val PANEL = 0xF0101010.toInt()
    const val TEXT = Color.WHITE
    const val DIM = 0xFFAAAAAA.toInt()
    const val FOCUS = 0xFF2F6FED.toInt()
    const val SELECTED = 0x66FFFFFF
}

/** Background for focusable rows/buttons: blue when focused, light when selected. */
fun focusBackground(): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(Palette.FOCUS))
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Palette.FOCUS))
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
}

fun border(context: Context, color: Int, widthDp: Int): GradientDrawable = GradientDrawable().apply {
    setColor(Color.TRANSPARENT)
    setStroke(context.dp(widthDp), color)
}

fun label(context: Context, sizeSp: Float = 18f, color: Int = Palette.TEXT): TextView = TextView(context).apply {
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

/** Simple text list adapter; rows are recycled. */
abstract class TextAdapter(private val context: Context) : BaseAdapter() {
    abstract fun text(position: Int): String
    override fun getItem(position: Int): Any = text(position)
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val tv = (convertView as? TextView) ?: TextView(context).apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            val pad = context.dp(10)
            setPadding(pad, context.dp(7), pad, context.dp(7))
        }
        tv.text = text(position)
        return tv
    }
}
