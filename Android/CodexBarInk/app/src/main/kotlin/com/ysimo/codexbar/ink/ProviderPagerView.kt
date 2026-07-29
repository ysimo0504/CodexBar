package com.ysimo.codexbar.ink

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

internal object ProviderPageResolver {
    fun targetPage(
        currentPage: Int,
        pageCount: Int,
        pageWidth: Int,
        scrollX: Int,
        dragDistance: Float,
        velocityX: Int?,
    ): Int {
        if (pageCount <= 0 || pageWidth <= 0) return 0
        val fling = velocityX?.takeIf { abs(it) >= 1_000 }
        val target = when {
            fling != null -> currentPage + if (fling > 0) 1 else -1
            abs(dragDistance) >= pageWidth * 0.12f ->
                currentPage + if (dragDistance > 0) 1 else -1
            else -> (scrollX.toFloat() / pageWidth).roundToInt()
        }
        return target.coerceIn(0, pageCount - 1)
    }
}

class ProviderPagerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    var pageCount: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            currentPage = currentPage.coerceIn(0, (field - 1).coerceAtLeast(0))
        }

    var onPageSelected: ((Int) -> Unit)? = null

    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var currentPage = 0
    private var downX = 0f
    private var currentDragDistance = 0f
    private var releaseWasHandledByFling = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                currentDragDistance = 0f
                releaseWasHandledByFling = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                currentDragDistance = downX - event.x
            }
        }
        val handled = super.onTouchEvent(event)
        if (
            (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !releaseWasHandledByFling
        ) {
            snapToResolvedPage(dragDistance = currentDragDistance, velocityX = null)
        }
        return handled
    }

    override fun fling(velocityX: Int) {
        releaseWasHandledByFling = true
        snapToResolvedPage(
            dragDistance = currentDragDistance,
            velocityX = velocityX.takeIf {
                kotlin.math.abs(it) >= maxOf(minimumFlingVelocity, 1_000)
            },
        )
    }

    fun showPage(page: Int, animated: Boolean) {
        if (pageCount <= 0 || width <= 0) return
        val target = page.coerceIn(0, pageCount - 1)
        currentPage = target
        if (animated) {
            smoothScrollTo(target * width, 0)
        } else {
            scrollTo(target * width, 0)
        }
        onPageSelected?.invoke(target)
    }

    private fun snapToResolvedPage(dragDistance: Float, velocityX: Int?) {
        val target = ProviderPageResolver.targetPage(
            currentPage = currentPage,
            pageCount = pageCount,
            pageWidth = width,
            scrollX = scrollX,
            dragDistance = dragDistance,
            velocityX = velocityX,
        )
        showPage(target, animated = true)
    }
}
