package com.sen0413.ultimatewidget.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A vertical list whose rows can be dragged to a new place by a handle and swiped left to
 * remove. Built on a plain LinearLayout inside the screen's ScrollView, which is plenty for
 * playlist-sized lists and keeps every row a normal, accessible view.
 */
class ReorderList(context: Context) : LinearLayout(context) {
    /** A row was dropped at a new index. */
    var onMove: ((from: Int, to: Int) -> Unit)? = null

    /** A row was swiped away; null disables swiping. */
    var onSwipe: ((index: Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // drag state
    private var dragged: View? = null
    private var dragFrom = -1
    private var dragTo = -1
    private var startRawY = 0f

    // swipe state
    private var swipeRow: View? = null
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    init {
        orientation = VERTICAL
    }

    fun setRows(rows: List<View>) {
        removeAllViews()
        rows.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    }

    /** Makes [handle] (inside [row]) start a drag when touched. */
    @SuppressLint("ClickableViewAccessibility")
    fun attachHandle(row: View, handle: View) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startDrag(row, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveDrag(event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    true
                }
                else -> false
            }
        }
    }

    private fun startDrag(row: View, rawY: Float) {
        parent?.requestDisallowInterceptTouchEvent(true)
        dragged = row
        dragFrom = indexOfChild(row)
        dragTo = dragFrom
        startRawY = rawY
        row.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
        row.elevation = row.resources.displayMetrics.density * 12f
    }

    private fun moveDrag(rawY: Float) {
        val row = dragged ?: return
        val dy = rawY - startRawY
        row.translationY = dy
        val center = row.top + dy + row.height / 2f
        var target = dragFrom
        for (i in 0 until childCount) {
            val other = getChildAt(i)
            if (other === row) continue
            val otherCenter = other.top + other.height / 2f
            if (i < dragFrom && center < otherCenter) target = minOf(target, i)
            if (i > dragFrom && center > otherCenter) target = maxOf(target, i)
        }
        if (target != dragTo) {
            dragTo = target
            for (i in 0 until childCount) {
                val other = getChildAt(i)
                if (other === row) continue
                val shift = when {
                    i in (dragTo until dragFrom) -> row.height.toFloat()
                    i in (dragFrom + 1)..dragTo -> -row.height.toFloat()
                    else -> 0f
                }
                other.animate().translationY(shift).setDuration(140).start()
            }
        }
    }

    private fun endDrag() {
        val row = dragged ?: return
        dragged = null
        row.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        row.elevation = 0f
        for (i in 0 until childCount) {
            getChildAt(i).animate().cancel()
            getChildAt(i).translationY = 0f
        }
        if (dragTo != dragFrom && dragFrom >= 0) {
            removeView(row)
            addView(row, dragTo)
            onMove?.invoke(dragFrom, dragTo)
        }
        dragFrom = -1
        dragTo = -1
    }

    // ---------------------------------------------------------------- swipe to remove

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (onSwipe == null || dragged != null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                swipeRow = childAt(ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (swipeRow != null && dx < -slop * 2 && abs(dx) > abs(dy) * 2) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val row = swipeRow ?: return false
        if (!swiping) return false
        val dx = (event.x - downX).coerceAtMost(0f)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                row.translationX = dx
                row.alpha = 1f - (abs(dx) / width).coerceIn(0f, 0.7f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val index = indexOfChild(row)
                if (abs(dx) > width * 0.35f && event.actionMasked == MotionEvent.ACTION_UP && index >= 0) {
                    row.animate().translationX(-width.toFloat()).alpha(0f).setDuration(160).withEndAction {
                        onSwipe?.invoke(index)
                    }.start()
                } else {
                    row.animate().translationX(0f).alpha(1f).setDuration(160).start()
                }
                swipeRow = null
                swiping = false
            }
        }
        return true
    }

    private fun childAt(y: Float): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (y >= c.top && y < c.bottom) return c
        }
        return null
    }
}
