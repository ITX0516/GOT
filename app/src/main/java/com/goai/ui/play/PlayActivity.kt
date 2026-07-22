package com.goai.ui.play

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.goai.R
import com.goai.ai.LLMService
import com.goai.data.AppPreferences
import com.goai.databinding.ActivityPlayBinding
import com.goai.engine.AssetExtractor
import com.goai.engine.GoEngine
import com.goai.engine.LocalKataGoEngine
import com.goai.model.AnalysisData
import com.goai.model.GameState
import com.goai.model.Move
import com.goai.model.Stone
import com.goai.ui.board.GoBoardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayBinding
    private lateinit var prefs: AppPreferences
    private lateinit var gameState: GameState
    private lateinit var boardView: GoBoardView

    private var engine: GoEngine? = null
    private var llmService: LLMService? = null
    private var isAiThinking = false

    // 胜率条组件
    private var winRateBar: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        // 初始化游戏状态
        gameState = GameState(boardSize = prefs.boardSize, komi = prefs.komi)

        // 初始化棋盘 View
        boardView = binding.boardView
        boardView.setGameState(gameState)
        boardView.onStonePlacedListener = object : GoBoardView.OnStonePlacedListener {
            override fun onStonePlaced(x: Int, y: Int) {
                onPlayerMove(x, y)
            }
        }

        // 初始化引擎
        setupEngine()

        // 初始化 LLM
        setupLLM()

        // 绑定按钮
        binding.btnHint.setOnClickListener { showHint() }
        binding.btnExplain.setOnClickListener { requestExplain() }
        binding.btnUndo.setOnClickListener { undoMove() }
        binding.btnPass.setOnClickListener { onPlayerPass() }
    }

    /** 初始化引擎 */
    private fun setupEngine() {
        when (prefs.engineMode) {
            "local" -> {
                lifecycleScope.launch {
                    binding.progressBar.visibility = View.VISIBLE
                    try {
                        val paths = AssetExtractor.extractKataGoAssets(this@PlayActivity)
                        val libDir = applicationInfo.nativeLibraryDir

                        // 收集调试信息
                        val debugInfo = buildString {
                            append("=== 引擎启动调试信息 ===\n")
                            append("katago (DLC版): ").append(paths.katagoPath)
                            append(" (存在: ").append(File(paths.katagoPath).exists()).append(")\n")
                            append("katago_nosnpe (纯CPU版): ").append(paths.katagoNoSnpePath)
                            append(" (存在: ").append(File(paths.katagoNoSnpePath).exists()).append(")\n")
                            append("20b_head模型: ").append(paths.model20bHeadPath)
                            append(" (存在: ").append(File(paths.model20bHeadPath).exists())
                            append(", 大小: ").append(File(paths.model20bHeadPath).length()).append(")\n")
                            append("20b_tflite模型: ").append(paths.model20bTflitePath)
                            append(" (存在: ").append(File(paths.model20bTflitePath).exists())
                            append(", 大小: ").append(File(paths.model20bTflitePath).length()).append(")\n")
                            append("10b模型: ").append(paths.model10bPath)
                            append(" (存在: ").append(File(paths.model10bPath).exists())
                            append(", 大小: ").append(File(paths.model10bPath).length()).append(")\n")
                            append("静态配置: ").append(paths.configStaticPath)
                            append(" (存在: ").append(File(paths.configStaticPath).exists())
                            append(", 大小: ").append(File(paths.configStaticPath).length()).append(")\n")
                            append("GTP日志目录: ").append(paths.gtpLogDir).append("\n")
                            append("lib目录: ").append(paths.libDir).append("\n")
                            append("尝试顺序: DLC版(katago.so) -> nosnpe版+TFLite -> nosnpe版纯CPU\n")
                            append("======================\n\n")
                        }

                        val logFile = File(getExternalFilesDir(null), "engine_error.log")
                        try { logFile.writeText("") } catch (_: Exception) {}
                        logFile.appendText(debugInfo)

                        engine = LocalKataGoEngine(
                            paths.katagoPath,
                            paths.katagoNoSnpePath,
                            paths.model20bHeadPath,
                            paths.model20bTflitePath,
                            paths.configStaticPath,
                            paths.gtpLogDir,
                            paths.libDir,
                            paths.libDir,
                            logFile
                        )
                        val ok = engine!!.init(gameState.boardSize, gameState.komi)
                        if (!ok) {
                            showToast("本地引擎初始化失败")
                            engine = null
                        }
                    } catch (e: Exception) {
                        val errorMsg = e.message ?: "未知错误"
                        val fullError = buildString {
                            append("\n\n=== 引擎启动失败(异常) ===\n")
                            append("异常类型: ").append(e.javaClass.simpleName).append("\n")
                            append("错误信息: ").append(errorMsg).append("\n")
                            append("\n=== 堆栈跟踪 ===\n")
                            append(e.stackTraceToString())
                        }
                        // 追加到日志文件（不要覆盖之前的诊断日志！）
                        try {
                            val logFile = File(getExternalFilesDir(null), "engine_error.log")
                            logFile.appendText(fullError)
                        } catch (_: Exception) {}
                        // 显示可复制的错误对话框
                        showErrorDialog(fullError)
                        engine = null
                    } finally {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
            "cloud" -> {
                showToast("云端引擎尚未配置")
            }
        }
    }

    /** 显示错误对话框，支持复制文本 */
    private fun showErrorDialog(message: String) {
        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = message
            textSize = 12f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(textView)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("引擎启动失败")
            .setView(scrollView)
            .setPositiveButton("复制到剪贴板") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("engine_error", message)
                clipboard.setPrimaryClip(clip)
                showToast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 初始化 LLM */
    private fun setupLLM() {
        if (prefs.llmKey.isNotEmpty()) {
            llmService = LLMService(prefs.llmUrl, prefs.llmKey, prefs.llmModel)
        }
    }

    /** 玩家落子 */
    private fun onPlayerMove(x: Int, y: Int) {
        if (isAiThinking) return
        if (gameState.currentPlayer != Stone.BLACK) return

        if (!gameState.play(x, y, Stone.BLACK)) {
            return
        }

        boardView.setGameState(gameState)
        runAnalysis()

        // AI 回合
        if (engine != null && gameState.currentPlayer == Stone.WHITE) {
            makeAiMove()
        }
    }

    /** 玩家停一手 */
    private fun onPlayerPass() {
        if (isAiThinking) return
        gameState.pass(Stone.BLACK)
        boardView.setGameState(gameState)
        if (engine != null) {
            makeAiMove()
        }
    }

    /** AI 走棋 */
    private fun makeAiMove() {
        isAiThinking = true
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val moveGTP = engine!!.genMove(Stone.WHITE, gameState)
                if (moveGTP.lowercase() == "pass") {
                    gameState.pass(Stone.WHITE)
                } else {
                    val move = Move.fromGTP(moveGTP, gameState.boardSize, Stone.WHITE)
                    gameState.play(move.x, move.y, Stone.WHITE)
                }
                boardView.setGameState(gameState)
                runAnalysis()
            } catch (e: Exception) {
                showToast("AI 走棋失败：${e.message}")
            } finally {
                isAiThinking = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /** 运行 AI 分析 */
    private fun runAnalysis() {
        if (engine == null) return

        lifecycleScope.launch {
            try {
                val analysis = engine!!.analyze(gameState)
                if (analysis != null) {
                    boardView.setAnalysis(analysis)
                    showWinRateBar(analysis)
                }
            } catch (e: Exception) {
                // 分析失败不影响对弈
            }
        }
    }

    /** 显示胜率条 */
    private fun showWinRateBar(analysis: AnalysisData) {
        val container = binding.winRateContainer
        container.removeAllViews()

        // 黑方视角胜率
        val blackWinrate = if (gameState.currentPlayer == Stone.BLACK) {
            analysis.winrate
        } else {
            1f - analysis.winrate
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val blackPercent = (blackWinrate * 100).toInt()
        val whitePercent = 100 - blackPercent

        val tvBlack = TextView(this).apply {
            text = "黑 $blackPercent%"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1A1A1A.toInt())
            textSize = 14f
            setPadding(24, 8, 24, 8)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, blackWinrate
            )
        }

        val tvWhite = TextView(this).apply {
            text = "$whitePercent% 白"
            setTextColor(0xFF1A1A1A.toInt())
            setBackgroundColor(0xFFFAFAFA.toInt())
            textSize = 14f
            setPadding(24, 8, 24, 8)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f - blackWinrate
            )
        }

        bar.addView(tvBlack)
        bar.addView(tvWhite)
        container.addView(bar)
        container.visibility = View.VISIBLE
    }

    /** 显示提示（AI 推荐选点） */
    private fun showHint() {
        if (engine == null) {
            showToast("无可用引擎")
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val analysis = engine!!.analyze(gameState)
                if (analysis != null) {
                    boardView.setAnalysis(analysis)
                    boardView.setShowHints(true)
                    showWinRateBar(analysis)
                    showToast("推荐选点：${analysis.bestMove}")
                }
            } catch (e: Exception) {
                showToast("分析失败")
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /** 请求 AI 讲解 */
    private fun requestExplain() {
        if (llmService == null || !llmService!!.isConfigured) {
            showToast("请先在设置中配置 LLM")
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.explainScroll.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // 截取棋盘截图
                val bitmap = captureBoard()

                val result = llmService!!.explain(gameState, bitmap)
                withContext(Dispatchers.Main) {
                    binding.tvExplain.text = result
                    binding.explainScroll.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvExplain.text = "AI 分析暂不可用：${e.message}"
                    binding.explainScroll.visibility = View.VISIBLE
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    /** 悔棋 */
    private fun undoMove() {
        if (isAiThinking) return
        // 撤销两步（玩家+AI）
        gameState.undo()
        gameState.undo()
        boardView.setGameState(gameState)
        boardView.setAnalysis(null)
        binding.winRateContainer.visibility = View.GONE
    }

    /** 截取棋盘 Bitmap */
    private fun captureBoard(): Bitmap {
        val width = boardView.width
        val height = boardView.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        boardView.draw(canvas)
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
