package com.goai.engine

import android.util.Log
import com.goai.model.AnalysisData
import com.goai.model.CandidateMove
import com.goai.model.GameState
import com.goai.model.Stone
import java.io.File

/**
 * 本地 KataGo 引擎
 * 启动逻辑完全照抄 BadukAI (lzwrapper.py)：
 * - 优先使用 20b DLC 模式 (20b.bin.gz + 20b.tflite)
 * - 设置 LD_LIBRARY_PATH 和 ADSP_LIBRARY_PATH
 * - 配置文件合并 gtp_static.cfg + 动态参数
 */
class LocalKataGoEngine(
    private val executablePath: String,
    private val model10bPath: String,
    private val model20bHeadPath: String,
    private val model20bTflitePath: String,
    private val configPath: String,
    private val libDir: String? = null,
    private val logFile: File? = null
) : GoEngine {

    override val name: String = "KataGo"

    private var gtpClient: GTPClient? = null

    override val isReady: Boolean
        get() = gtpClient?.isRunning == true

    private fun appendLog(msg: String) {
        try {
            logFile?.appendText(msg + "\n")
        } catch (_: Exception) {}
        Log.d("KataGoEngine", msg)
    }

    override suspend fun init(boardSize: Int, komi: Float): Boolean {
        appendLog("=== 开始初始化 KataGo 引擎 ===")
        appendLog("可执行文件: $executablePath (存在: ${File(executablePath).exists()})")
        appendLog("10b模型: $model10bPath (存在: ${File(model10bPath).exists()}, 大小: ${File(model10bPath).length()})")
        appendLog("20b_head模型: $model20bHeadPath (存在: ${File(model20bHeadPath).exists()}, 大小: ${File(model20bHeadPath).length()})")
        appendLog("20b_tflite模型: $model20bTflitePath (存在: ${File(model20bTflitePath).exists()}, 大小: ${File(model20bTflitePath).length()})")
        appendLog("配置文件: $configPath (存在: ${File(configPath).exists()}, 大小: ${File(configPath).length()})")
        appendLog("库目录: $libDir")

        libDir?.let {
            val libDirFile = File(it)
            if (libDirFile.exists() && libDirFile.isDirectory) {
                appendLog("库目录文件数: ${libDirFile.listFiles()?.size}")
            }
        }

        // 按优先级尝试启动：20b DLC 模式 > 10b 纯 CPU 模式
        val attempts = listOf(
            Triple("20b DLC模式 (20b.bin.gz + 20b.tflite)", model20bHeadPath, true),
            Triple("10b 纯CPU模式 (10b.bin.gz)", model10bPath, false),
        )

        for ((modeName, modelPath, _) in attempts) {
            appendLog("\n--- 尝试启动: $modeName ---")
            val success = tryStartGtp(modelPath, boardSize, komi)
            if (success) {
                appendLog("$modeName 启动成功!")
                return true
            }
            appendLog("$modeName 启动失败，尝试下一个...")
        }

        appendLog("\n所有模式均启动失败")
        throw Exception("所有引擎模式均启动失败，请查看日志详情")
    }

    /** 尝试以指定模型启动 GTP 模式 */
    private suspend fun tryStartGtp(modelPath: String, boardSize: Int, komi: Float): Boolean {
        val client = GTPClient(
            executablePath,
            listOf("gtp", "-model", modelPath, "-config", configPath)
        )
        return try {
            client.start(libDir)
            appendLog("进程启动成功，发送 name/version 测试...")
            val name = client.sendCommand("name")
            appendLog("name: $name")
            val version = client.sendCommand("version")
            appendLog("version: $version")
            client.sendCommand("boardsize $boardSize")
            client.sendCommand("komi $komi")
            client.sendCommand("time_settings 0 1 0")
            appendLog("GTP 初始化命令发送成功")
            gtpClient = client
            true
        } catch (e: Exception) {
            val exitCode = client.exitValue
            val stderr = client.lastError
            appendLog("启动失败，退出码: $exitCode")
            appendLog("异常: ${e.message}")
            appendLog("STDERR: $stderr")
            client.close()
            false
        }
    }

    override suspend fun genMove(color: Stone, gameState: GameState): String {
        val c = when (color) {
            Stone.BLACK -> "b"
            Stone.WHITE -> "w"
            else -> "b"
        }
        return gtpClient!!.sendCommand("genmove $c").trim()
    }

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

    override fun close() {
        gtpClient?.close()
    }

    private fun parseAnalyzeLine(line: String): AnalysisData? {
        val candidates = mutableListOf<CandidateMove>()
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
                    "pv" -> break
                }
                i += 2
            }
            if (move.isNotEmpty()) {
                candidates.add(CandidateMove(move, winrate / 100f, scoreLead, visits))
            }
        }
        if (candidates.isEmpty()) return null
        val best = candidates.first()
        return AnalysisData(
            winrate = best.winrate,
            bestMove = best.move,
            scoreLead = best.scoreLead,
            candidateMoves = candidates
        )
    }
}
