package com.example.minimalplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FastScrollerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var recyclerView: RecyclerView? = null
    private val letters = mutableListOf<Char>()
    private val letterPositions = mutableMapOf<Char, Int>()
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 32f; textAlign = Paint.Align.CENTER }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2979FF"); style = Paint.Style.FILL }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 64f; textAlign = Paint.Align.CENTER }

    private var showBubble = false
    private var currentBubbleLetter: Char? = null
    private var bubbleY = 0f

    init { setWillNotDraw(false) }

    fun attach(recyclerView: RecyclerView) { this.recyclerView = recyclerView }

    fun setSections(sections: Map<Char, Int>) {
        letters.clear(); letterPositions.clear()
        letters.addAll(sections.keys.sorted())
        letterPositions.putAll(sections)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val height = height.toFloat()
        val itemHeight = height / letters.size
        for (i in letters.indices) {
            val y = itemHeight * i + itemHeight / 2 + paint.textSize / 2
            canvas.drawText(letters[i].toString(), width / 2f, y, paint)
        }
        if (showBubble && currentBubbleLetter != null) {
            val bubbleSize = 150f
            // NOTE: Requires parent FrameLayout to have clipChildren="false"
            val cx = -bubbleSize / 2f 
            canvas.drawCircle(cx, bubbleY, bubbleSize / 2f, bubblePaint)
            val textY = bubbleY - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
            canvas.drawText(currentBubbleLetter.toString(), cx, textY, bubbleTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                showBubble = true
                val y = event.y
                val itemHeight = height.toFloat() / letters.size
                var index = (y / itemHeight).toInt()
                if (index < 0) index = 0
                if (index >= letters.size) index = letters.size - 1
                currentBubbleLetter = letters[index]
                bubbleY = y
                val position = letterPositions[currentBubbleLetter] ?: 0
                (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                showBubble = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
