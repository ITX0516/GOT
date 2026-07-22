package com.goai.engine

import android.util.Log
import com.goai.model.AnalysisData
import com.goai.model.CandidateMove
import com.goai.model.GameState
import com.goai.model.Stone
import java.io.File

/**
 * 本地 KataGo 引擎
 * 通过 [GTPClient] 与 katago 可执行文件通信，实现 [GoEngine] 接口
 */
class LocalKataGoEngine(
    private val executablePath: String,
    private val modelPath: String,
    private val configPath: String,
    private val libDir: String? = null,
    private val logFile: File? = null
) : GoEngine {

    override val name: String = "KataGo"

    /** GTP 客户端：以 gtp 模式启动 katago */
    private var gtpClient: GTPClient? = null

    /** 引擎是否就绪：GTPClient 已启动且子进程存活 */
    override val isReady: Boolean
        get() = gtpClient?.isRunning == true

    private fun appendLog(msg: String) {
        try {
            logFile?.appendText(msg + "\n")
        } catch (_: Exception) {}
        Log.d("KataGoEngine", msg)
    }

    /** 初始化引擎：：先测试二进制，再启动 GTP 模式 */
    override suspend fun init(boardSize: Int, komi: Float): Boolean {
        appendLog("=== 开始初始化 KataGo 引擎 ===")
        appendLog("可执行文件: $executablePath (存在: ${File(executablePath).exists()})")
        appendLog("模型文件: $modelPath (存在: ${File(modelPath).exists()}, 大小: ${File(modelPath).length()})")
        appendLog("配置文件: $configPath (存在: ${File(configPath).exists()}, 大小: ${File(configPath).length()})")
        appendLog("库目录: $libDir")

        // 第一步：测试 version 命令
        appendLog("\n--- 测试1: version 命令 ---")
        try {
            runAndCapture(listOf("version"))
        } catch (e: Exception) {
            appendLog("version 命令失败: ${e.message}")
        }

        // 第二步：测试 help 命令
        appendLog("\n--- 测试2: -help 命令 ---")
        try {
            runAndCapture(listOf("-help"))
        } catch (e: Exception) {
            appendLog("-help 命令失败: ${e.message}")
        }

        // 第三步：正常启动 GTP 模式
        appendLog("\n--- 启动 GTP 模式 ---")
        return try {
            gtpClient = GTPClient(
                executablePath,
                listOf("gtp", "-model", modelPath, "-config", configPath)
            )
            gtpClient!!.start(libDir)
            appendLog("GTP 模式启动成功，发送初始化命令...")
            gtpClient!!.sendCommand("boardsize $boardSize")
            gtpClient!!.sendCommand("komi $komi")
            gtpClient!!.sendCommand("time_settings 0 1 0")
            appendLog("初始化完成")
            true
        } catch (e: Exception) {
            val stderr = gtpClient?.lastError ?: ""
            val exitCode = gtpClient?.exitValue
            appendLog("GTP 启动失败，退出码: $exitCode")
            appendLog("异常: ${e.message}")
            if (stderr.isNotEmpty()) {
                appendLog("STDERR: $stderr")
            }
            throw Exception("${e.message}\nSTDERR: $stderr")
        }
    }

    /** 运行命令并捕获输出，写到日志里 */
    private suspend fun runAndCapture(args: List<String>) {
        val client = GTPClient(executablePath, args)
        try {
            client.start(libDir)
            Thread.sleep(2000)
        } catch (e: Exception) {
            // 命令执行完会抛异常（因为进程退出了），这是正常的
        } finally {
            val exitCode = client.exitValue
            val stdout = try {
                client.readAllStdout()
            } catch (_: Exception) { "" }
            val stderr = client.lastError
            appendLog("退出码: $exitCode")
            appendLog("=== STDOUT ===\n$stdout")
            appendLog("=== STDERR ===\n$stderr")
            client.close()
        }
    }

    /** 生成一步棋，返回 GTP 坐标（如 "D17" 或 "pass"） */
    override suspend fun genMove(color: Stone, gameState: GameState): String {
        val c = when (color) {
            Stone.BLACK -> "b"
            Stone.WHITE -> "w"
            else -> "b"
        }
        return gtpClient!!.sendCommand("genmove $c").trim()
    }

    /** 分析当前局面，返回分析数据；引擎不支持分析时返回 null */
    override suspend fun analyze(gameState: GameState): AnalysisData? {
        val line = try {
            gtpClient!!.sendAnalyze("kata-analyze interval100")
        } catch (e: GTPException) {
            try {
                gtpClient!!.sendAnalyze("lz-analyze interval100")
            } catch (e2: Exception) {
                return null
            }
        }
        try {
            gtpClient!!.sendCommand("name")
        } catch (_: Exception) {
        }
        return parseAnalyzeLine(line)
    }

    /** 释放资源 */
    override fun close() {
        gtpClient?.close()
    }

    /**
     * 解析 kata-analyze / lz-analyze 的 info 行
     * 格式：info move D17 visits 7 winrate 52.95 scoreLead -0.08 pv D17 Q16 ...
     * 一行可能包含多个 info 段（以 " info " 分隔），每个段对应一个候选走法
     */
    private fun parseAnalyzeLine(line: String): AnalysisData? {
        val candidates = mutableListOf<CandidateMove>()
        // 按 " info " 切分出各候选信息段，并补回前缀
        val segments = line.split(" info ").mapIndexed { index, segment ->
            if (index == 0) segment else "info $segment"
        }
        for (segment in segments) {
            val tokens = segment.split(" ")
            if (tokens.isEmpty() || tokens[0] != "info") continue
            var move = ""
            var winrate = 0f
            var scoreLead = 0f
            var visits = 0
            var i = 1
            while (i + 1 < tokens.size) {
                val key = tokens[i]
                val value = tokens[i + 1]
                when (key) {
                    "move" -> move = value
                    "winrate" -> winrate = value.toFloatOrNull() ?: 0f
                    "scoreLead" -> scoreLead = value.toFloatOrNull() ?: 0f
                    "visits" -> visits = value.toIntOrNull() ?: 0
                    "pv" -> break  // pv 之后是着法序列，停止解析本段
                }
                i += 2
            }
            if (move.isNotEmpty()) {
                // winrate 输出范围为 0~100，转换为 0~1
                candidates.add(CandidateMove(move, winrate / 100f, scoreLead, visits))
            }
        }
        if (candidates.isEmpty()) return null
        // 第一个候选为最佳走法
        val best = candidates.first()
        return AnalysisData(
            winrate = best.winrate,
            bestMove = best.move,
            scoreLead = best.scoreLead,
            candidateMoves = candidates
        )
    }
}
