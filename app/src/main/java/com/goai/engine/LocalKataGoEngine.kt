package com.goai.engine

import com.goai.model.AnalysisData
import com.goai.model.CandidateMove
import com.goai.model.GameState
import com.goai.model.Stone

/**
 * 本地 KataGo 引擎
 * 通过 [GTPClient] 与 katago 可执行文件通信，实现 [GoEngine] 接口
 */
class LocalKataGoEngine(
    private val executablePath: String,
    private val modelPath: String,
    private val configPath: String
) : GoEngine {

    override val name: String = "KataGo"

    /** GTP 客户端：以 gtp 模式启动 katago */
    private val gtpClient: GTPClient = GTPClient(
        executablePath,
        listOf("gtp", "-model", modelPath, "-config", configPath)
    )

    /** 引擎是否就绪：GTPClient 已启动且子进程存活 */
    override val isReady: Boolean
        get() = gtpClient.isRunning

    /** 初始化引擎：启动子进程并设置棋盘参数 */
    override suspend fun init(boardSize: Int, komi: Float): Boolean {
        return try {
            gtpClient.start()
            gtpClient.sendCommand("boardsize $boardSize")
            gtpClient.sendCommand("komi $komi")
            // 时间设置：主时间 0、byo-yomi 时间 1 秒、byo-yomi 次数 0
            gtpClient.sendCommand("time_settings 0 1 0")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 生成一步棋，返回 GTP 坐标（如 "D17" 或 "pass"） */
    override suspend fun genMove(color: Stone, gameState: GameState): String {
        val c = when (color) {
            Stone.BLACK -> "b"
            Stone.WHITE -> "w"
            else -> "b"
        }
        return gtpClient.sendCommand("genmove $c").trim()
    }

    /** 分析当前局面，返回分析数据；引擎不支持分析时返回 null */
    override suspend fun analyze(gameState: GameState): AnalysisData? {
        val line = try {
            gtpClient.sendAnalyze("kata-analyze interval100")
        } catch (e: GTPException) {
            // 不支持 kata-analyze 时回退到 lz-analyze
            try {
                gtpClient.sendAnalyze("lz-analyze interval100")
            } catch (e2: Exception) {
                return null
            }
        }
        // 发送其它命令以终止 analyze 并消费响应
        try {
            gtpClient.sendCommand("name")
        } catch (_: Exception) {
        }
        return parseAnalyzeLine(line)
    }

    /** 释放资源 */
    override fun close() {
        gtpClient.close()
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
