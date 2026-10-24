package id.my.doncorleonez.multiview.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** One tile: video (letterboxed), a state line when not playing, a white border when highlighted. */
@OptIn(UnstableApi::class)
class TileView(context: Context) : FrameLayout(context) {

    val playerView: PlayerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(false)
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private val status: TextView = label(context, 20f).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(0xB0000000.toInt())
        val p = context.dp(12)
        setPadding(p, context.dp(6), p, context.dp(6))
        visibility = View.GONE
    }

    private val frame = View(context).apply {
        background = border(context, Color.WHITE, 4)
        visibility = View.GONE
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun setHighlighted(on: Boolean) {
        frame.visibility = if (on) View.VISIBLE else View.GONE
    }
}
