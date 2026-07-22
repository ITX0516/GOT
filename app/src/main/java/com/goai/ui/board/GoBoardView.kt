package com.goai.ui.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.goai.model.AnalysisData
import com.goai.model.GameState
import com.goai.model.Stone

/**
 * 围棋棋盘自定义 View。
 *
 * 渲染风格参考腾讯围棋：木色背景、黑色网格线、星位、坐标标注，
 * 黑白棋子带径向渐变与阴影，最后一手用红点标记。
 *
 * 支持触摸落子（通过 [onStonePlacedListener] 回调通知最近交叉点），
 * 以及 AI 分析标记叠加：bestMove 用绿色圆圈、candidateMoves 用蓝色小圆点，
 * 可选胜率热力图（半透明色块覆盖）。
 *
 * 所有 [Paint] 在 [init] 中创建并缓存，[onDraw] 中不创建对象；
 * 棋盘在 [onMeasure] 中保持正方形比例。
 */
class GoBoardView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 落子回调接口 */
    interface OnStonePlacedListener {
        fun onStonePlaced(x: Int, y: Int)
    }
    var onStonePlacedListener: OnStonePlacedListener? = null

    // ===== 状态数据 =====
    private var boardSize: Int = 19
    private var gameState: GameState? = null
    private var analysis: AnalysisData? = null
    private var showHints: Boolean = true
    private var showHeatmap: Boolean = false

    // 解析后的分析点（在 setAnalysis 中解析并缓存，避免 onDraw 创建对象）
    private var bestMoveGrid: IntArray? = null
    private var candidateRenders: Array<CandidateRender>? = null

    /** 缓存的候选走法渲染数据（网格坐标 + 胜率 + 访问次数） */
    private class CandidateRender(val x: Int, val y: Int, val winrate: Float, val visits: Int)

    // ===== 颜色常量（0xAARRGGBB） =====
    private val woodColor = 0xFFDCB35C.toInt()
    private val lineColor = 0xFF3D2B1F.toInt()
    private val starColor = 0xFF1A1A1A.toInt()
    private val coordColor = 0xFF5C3A1E.toInt()
    private val lastMoveColor = 0xFFE53935.toInt()
    private val bestMoveColor = 0xFF4CAF50.toInt()
    private val candidateColor = 0xFF2196F3.toInt()
    private val shadowColor = 0x44000000

    // ===== 画笔缓存 =====
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackStonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteStonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bestMovePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heatmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ===== 尺寸缓存（在 onSizeChanged 中计算） =====
    private var cellSize: Float = 0f
    private var gridOriginX: Float = 0f  // 第一条竖线的 x
    private var gridOriginY: Float = 0f  // 第一条横线的 y
    private var stoneRadius: Float = 0f
    private var starRadius: Float = 0f
    private var starPoints: Array<IntArray> = emptyArray()

    // GTP 字母表（跳过 I）
    private val gtpLetters = "ABCDEFGHJKLMNOPQRST"

    init {
        isClickable = true

        // 木色背景
        bgPaint.color = woodColor
        bgPaint.style = Paint.Style.FILL

        // 网格线
        linePaint.color = lineColor
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.5f

        // 星位
        starPaint.color = starColor
        starPaint.style = Paint.Style.FILL

        // 坐标文字
        coordPaint.color = coordColor
        coordPaint.textAlign = Paint.Align.CENTER

        // 棋子阴影
        shadowPaint.color = shadowColor
        shadowPaint.style = Paint.Style.FILL

        // 黑/白棋子（径向渐变 shader 在 onSizeChanged 中按半径设置）
        blackStonePaint.style = Paint.Style.FILL
        whiteStonePaint.style = Paint.Style.FILL

        // 最后一手红点
        lastMovePaint.color = lastMoveColor
        lastMovePaint.style = Paint.Style.FILL

        // bestMove 绿圈
        bestMovePaint.color = bestMoveColor
        bestMovePaint.style = Paint.Style.STROKE
        bestMovePaint.strokeWidth = 4f

        // candidate 蓝点
        candidatePaint.color = candidateColor
        candidatePaint.style = Paint.Style.FILL

        // 热力图色块
        heatmapPaint.style = Paint.Style.FILL
    }

    // ===== 公开 API =====

    /** 更新棋盘状态并重绘 */
    fun setGameState(gameState: GameState) {
        this.gameState = gameState
        if (this.boardSize != gameState.boardSize) {
            this.boardSize = gameState.boardSize
            // 棋盘大小变化后重新解析分析点坐标
            reparseAnalysis()
        }
        updateSizes(width, height)
        invalidate()
    }

    /** 设置 AI 分析数据并重绘 */
    fun setAnalysis(analysis: AnalysisData?) {
        this.analysis = analysis
        reparseAnalysis()
        invalidate()
    }

    /** 控制是否显示 AI 标记 */
    fun setShowHints(show: Boolean) {
        this.showHints = show
        invalidate()
    }

    /** 控制是否显示胜率热力图 */
    fun setShowHeatmap(show: Boolean) {
        this.showHeatmap = show
        invalidate()
    }

    // ===== 测量 =====

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        // 保持正方形比例：取宽高的较小值
        val size = when {
            w == 0 && h == 0 -> suggestedMinimumWidth.coerceAtLeast(200)
            w == 0 -> h
            h == 0 -> w
            else -> minOf(w, h)
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSizes(w, h)
    }

    /** 根据当前尺寸计算格子大小、星位、棋子渐变 shader */
    private fun updateSizes(w: Int, h: Int) {
        if (w == 0 || h == 0 || boardSize < 2) return
        val size = minOf(w, h).toFloat()
        // 留出边距用于坐标标注
        val padding = size * 0.05f
        val gridSize = size - padding * 2
        cellSize = gridSize / (boardSize - 1)
        stoneRadius = cellSize * 0.46f
        starRadius = cellSize * 0.10f
        coordPaint.textSize = cellSize * 0.36f
        gridOriginX = padding
        gridOriginY = padding

        // 星位（缓存数组，避免 onDraw 中创建对象）
        starPoints = buildStarPoints()

        // 黑棋径向渐变：左上高光 -> 黑色
        blackStonePaint.shader = RadialGradient(
            stoneRadius * 0.6f, stoneRadius * 0.6f, stoneRadius * 1.3f,
            intArrayOf(0xFF888888.toInt(), 0xFF222222.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // 白棋径向渐变：左上高光 -> 浅灰边缘
        whiteStonePaint.shader = RadialGradient(
            stoneRadius * 0.6f, stoneRadius * 0.6f, stoneRadius * 1.3f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF0F0F0.toInt(), 0xFFC8C8C8.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** 根据棋盘大小构建星位（9x9 仅天元；13x13 五点；19x19 标准九点） */
    private fun buildStarPoints(): Array<IntArray> {
        val mid = (boardSize - 1) / 2
        return when {
            boardSize == 19 -> arrayOf(
                intArrayOf(3, 3), intArrayOf(3, 9), intArrayOf(3, 15),
                intArrayOf(9, 3), intArrayOf(9, 9), intArrayOf(9, 15),
                intArrayOf(15, 3), intArrayOf(15, 9), intArrayOf(15, 15)
            )
            boardSize == 13 -> arrayOf(
                intArrayOf(3, 3), intArrayOf(3, 9),
                intArrayOf(6, 6),
                intArrayOf(9, 3), intArrayOf(9, 9)
            )
            boardSize == 9 -> arrayOf(intArrayOf(mid, mid))
            boardSize >= 7 -> arrayOf(intArrayOf(mid, mid))
            else -> emptyArray()
        }
    }

    // ===== 绘制 =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boardSize < 2 || cellSize == 0f) return

        // 1. 木色背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. 坐标标注
        drawCoordinates(canvas)

        // 3. 网格线
        drawGridLines(canvas)

        // 4. 星位
        for (sp in starPoints) {
            canvas.drawCircle(
                gridOriginX + sp[0] * cellSize,
                gridOriginY + sp[1] * cellSize,
                starRadius, starPaint
            )
        }

        // 5. 棋子
        drawStones(canvas)

        // 6. 最后一手红点
        drawLastMoveMark(canvas)

        // 7. AI 分析叠加
        if (showHints) {
            if (showHeatmap) drawHeatmap(canvas)
            drawAnalysisOverlay(canvas)
        }
    }

    /** 绘制网格线 */
    private fun drawGridLines(canvas: Canvas) {
        val xStart = gridOriginX
        val xEnd = gridOriginX + (boardSize - 1) * cellSize
        val yStart = gridOriginY
        val yEnd = gridOriginY + (boardSize - 1) * cellSize
        for (i in 0 until boardSize) {
            val pos = gridOriginY + i * cellSize
            // 横线
            canvas.drawLine(xStart, pos, xEnd, pos, linePaint)
            // 竖线
            val posX = gridOriginX + i * cellSize
            canvas.drawLine(posX, yStart, posX, yEnd, linePaint)
        }
    }

    /** 绘制坐标标注（A-T 跳过 I；行号 1-19，y=0 对应最大行号） */
    private fun drawCoordinates(canvas: Canvas) {
        val baseY = gridOriginY + (boardSize - 1) * cellSize
        for (i in 0 until boardSize) {
            val cx = gridOriginX + i * cellSize
            val letter = if (i < gtpLetters.length) gtpLetters[i].toString() else ""
            // 上下标注字母
            canvas.drawText(letter, cx, gridOriginY * 0.65f, coordPaint)
            canvas.drawText(letter, cx, baseY + gridOriginY * 0.5f, coordPaint)
        }
        for (j in 0 until boardSize) {
            val cy = gridOriginY + j * cellSize
            val num = (boardSize - j).toString()
            // 左右标注数字（略微下移以视觉居中）
            canvas.drawText(num, gridOriginX * 0.5f, cy + cellSize * 0.12f, coordPaint)
            canvas.drawText(
                num,
                gridOriginX + (boardSize - 1) * cellSize + gridOriginX * 0.5f,
                cy + cellSize * 0.12f, coordPaint
            )
        }
    }

    /** 绘制所有棋子 */
    private fun drawStones(canvas: Canvas) {
        val gs = gameState ?: return
        for (x in 0 until boardSize) {
            for (y in 0 until boardSize) {
                val stone = gs.getStone(x, y)
                if (stone == Stone.EMPTY) continue
                val cx = gridOriginX + x * cellSize
                val cy = gridOriginY + y * cellSize
                drawStone(canvas, cx, cy, stone)
            }
        }
    }

    /** 绘制单颗棋子（含阴影与径向渐变） */
    private fun drawStone(canvas: Canvas, cx: Float, cy: Float, stone: Stone) {
        // 阴影：向右下偏移
        val offset = stoneRadius * 0.12f
        canvas.drawCircle(cx + offset, cy + offset, stoneRadius, shadowPaint)
        // 棋子主体：通过 translate 让渐变 shader 落在棋子局部坐标
        canvas.save()
        canvas.translate(cx - stoneRadius, cy - stoneRadius)
        val paint = if (stone == Stone.BLACK) blackStonePaint else whiteStonePaint
        canvas.drawCircle(stoneRadius, stoneRadius, stoneRadius, paint)
        canvas.restore()
    }

    /** 绘制最后一手红点标记 */
    private fun drawLastMoveMark(canvas: Canvas) {
        val gs = gameState ?: return
        val last = gs.lastMove ?: return
        if (last.isPass) return
        if (last.x !in 0 until boardSize || last.y !in 0 until boardSize) return
        // 仅在该处确有棋子时标记
        if (gs.getStone(last.x, last.y) == Stone.EMPTY) return
        val cx = gridOriginX + last.x * cellSize
        val cy = gridOriginY + last.y * cellSize
        canvas.drawCircle(cx, cy, stoneRadius * 0.28f, lastMovePaint)
    }

    /** 绘制 AI 分析标记（bestMove 绿圈 + candidateMoves 蓝点） */
    private fun drawAnalysisOverlay(canvas: Canvas) {
        val best = bestMoveGrid
        if (best != null) {
            val cx = gridOriginX + best[0] * cellSize
            val cy = gridOriginY + best[1] * cellSize
            canvas.drawCircle(cx, cy, stoneRadius * 0.95f, bestMovePaint)
        }
        val candidates = candidateRenders
        if (candidates != null) {
            val r = stoneRadius * 0.22f
            for (c in candidates) {
                // 跳过 bestMove 位置（已用绿圈标记，避免蓝点重叠）
                if (best != null && c.x == best[0] && c.y == best[1]) continue
                val cx = gridOriginX + c.x * cellSize
                val cy = gridOriginY + c.y * cellSize
                canvas.drawCircle(cx, cy, r, candidatePaint)
            }
        }
    }

    /** 绘制候选走法胜率热力图（半透明色块：高胜率偏绿，低胜率偏红） */
    private fun drawHeatmap(canvas: Canvas) {
        val renders = candidateRenders ?: return
        if (renders.isEmpty()) return
        // 用 visits 归一化透明度
        val maxVisits = renders.maxOfOrNull { it.visits } ?: 0
        if (maxVisits <= 0) return
        val half = cellSize * 0.5f
        for (c in renders) {
            val cx = gridOriginX + c.x * cellSize
            val cy = gridOriginY + c.y * cellSize
            val wr = c.winrate.coerceIn(0f, 1f)
            val r = ((1f - wr) * 255).toInt().coerceIn(0, 255)
            val g = (wr * 255).toInt().coerceIn(0, 255)
            val alpha = ((c.visits.toFloat() / maxVisits) * 180).toInt().coerceIn(40, 180)
            heatmapPaint.color = Color.argb(alpha, r, g, 0)
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, heatmapPaint)
        }
    }

    // ===== 触摸落子 =====

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        if (cellSize == 0f) return false
        // 计算最近交叉点
        val gx = Math.round((event.x - gridOriginX) / cellSize)
        val gy = Math.round((event.y - gridOriginY) / cellSize)
        if (gx in 0 until boardSize && gy in 0 until boardSize) {
            onStonePlacedListener?.onStonePlaced(gx, gy)
        }
        return true
    }

    // ===== 辅助 =====

    /** 解析 GTP 坐标（如 "D17"）为 [x, y] 网格坐标 */
    private fun parseGTP(gtp: String): IntArray? {
        if (gtp.isBlank()) return null
        val x = gtpLetters.indexOf(gtp[0].uppercaseChar())
        if (x < 0) return null
        val y = try {
            boardSize - gtp.substring(1).trim().toInt()
        } catch (e: NumberFormatException) {
            return null
        }
        if (x !in 0 until boardSize || y !in 0 until boardSize) return null
        return intArrayOf(x, y)
    }

    /** 将 analysis 中的 GTP 坐标解析为网格坐标并缓存（含胜率/访问次数，供热力图使用） */
    private fun reparseAnalysis() {
        val analysis = this.analysis
        if (analysis == null) {
            bestMoveGrid = null
            candidateRenders = null
            return
        }
        bestMoveGrid = parseGTP(analysis.bestMove)
        // 缓存全部候选点（含坐标、胜率、访问次数），蓝点绘制时再排除 bestMove
        candidateRenders = analysis.candidateMoves.mapNotNull { cm ->
            parseGTP(cm.move)?.let { CandidateRender(it[0], it[1], cm.winrate, cm.visits) }
        }.toTypedArray()
    }
}
