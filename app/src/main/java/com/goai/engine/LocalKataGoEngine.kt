package com.goai.engine

import android.util.Log
import com.goai.model.AnalysisData
import com.goai.model.CandidateMove
import com.goai.model.GameState
import com.goai.model.Stone
import java.io.File

class LocalKataGoEngine(
    private val katagoPath: String,
    private val katagoNoSnpePath: String,
    private val modelBinGzPath: String,
    private val modelTflitePath: String?,
    private val configStaticPath: String,
    private val gtpLogDir: String,
    private val libDir: String?,
    private val workDir: String?,
    private val logFile: File?
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

    private fun captureLogcat(tag: String) {
        try {
            val proc = Runtime.getRuntime().exec("logcat -d -v time")
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val filtered = output.lines().filter { line ->
                line.contains("libkatago", ignoreCase = true) ||
                line.contains("KataGo", ignoreCase = true) ||
                line.contains("katago", ignoreCase = true) ||
                line.contains("tflite", ignoreCase = true) ||
                line.contains("TFLite", ignoreCase = true) ||
                line.contains("QNN", ignoreCase = true) ||
                line.contains("qnn", ignoreCase = true) ||
                line.contains("FATAL", ignoreCase = true) ||
                line.contains("ERROR", ignoreCase = true) ||
                line.contains("Fatal", ignoreCase = true) ||
                line.contains("Signal", ignoreCase = true) ||
                line.contains("signal", ignoreCase = true) ||
                line.contains("libc", ignoreCase = true) ||
                line.contains("DEBUG", ignoreCase = true)
            }
            appendLog("=== LOGCAT ($tag) ===")
            filtered.forEach { appendLog(it) }
            appendLog("=== LOGCAT END ===")
        } catch (e: Exception) {
            appendLog("logcat capture failed: ${e.message}")
        }
    }

    override suspend fun init(boardSize: Int, komi: Float): Boolean {
        appendLog("=== 开始初始化 KataGo 引擎 ===")
        appendLog("katago (DLC版): $katagoPath (${File(katagoPath).exists()})")
        appendLog("katago_nosnpe (纯CPU版): $katagoNoSnpePath (${File(katagoNoSnpePath).exists()})")
        appendLog("模型(bin.gz): $modelBinGzPath (${File(modelBinGzPath).exists()}, ${File(modelBinGzPath).length()} bytes)")
        if (modelTflitePath != null) {
            appendLog("模型(tflite): $modelTflitePath (${File(modelTflitePath).exists()}, ${File(modelTflitePath).length()} bytes)")
        }
        appendLog("静态配置: $configStaticPath (${File(configStaticPath).exists()})")
        appendLog("日志目录: $gtpLogDir")
        appendLog("lib目录: $libDir")
        appendLog("工作目录: $workDir")

        // 确保日志目录存在
        val gtpLogDirFile = File(gtpLogDir)
        if (!gtpLogDirFile.exists()) {
            gtpLogDirFile.mkdirs()
            appendLog("创建日志目录: ${gtpLogDirFile.absolutePath}")
        }

        // 生成 gtp.cfg（照抄 BadukAI：static + dynamic + logDir + defaultBoardSize）
        val gtpCfgFile = File(gtpLogDirFile.parentFile, "gtp.cfg")
        val configStaticFile = File(configStaticPath)
        val staticContent = if (configStaticFile.exists()) configStaticFile.readText() else ""
        val gtpCfgContent = buildString {
            append(staticContent)
            if (!staticContent.endsWith("\n")) append("\n")
            append("logDir = $gtpLogDir\n")
            append("defaultBoardSize = $boardSize\n")
            append("defaultKomi = $komi\n")
        }
        gtpCfgFile.writeText(gtpCfgContent)
        appendLog("生成 gtp.cfg: ${gtpCfgFile.absolutePath} (${gtpCfgContent.length} bytes)")

        // 清空 logcat
        try { Runtime.getRuntime().exec("logcat -c").waitFor() } catch (_: Exception) {}

        val hasTflite = modelTflitePath != null && File(modelTflitePath).exists()

        val attempts = listOf(
            Triple("DLC模式: katago.so + bin.gz + tflite", katagoPath, true),
            Triple("纯CPU模式: katago_nosnpe.so + bin.gz + tflite", katagoNoSnpePath, true),
            Triple("纯CPU模式: katago_nosnpe.so + bin.gz (无tflite)", katagoNoSnpePath, false),
        )

        for ((modeName, binary, useTflite) in attempts) {
            if (useTflite && !hasTflite) continue
            appendLog("\n--- 尝试启动: $modeName ---")
            appendLog("二进制: $binary")
            appendLog("模型: $modelBinGzPath")
            if (useTflite && modelTflitePath != null) {
                appendLog("TFLite模型: $modelTflitePath")
            }
            try {
                val success = tryStartGtp(binary, modelBinGzPath, gtpCfgFile.absolutePath, boardSize, komi)
                if (success) {
                    appendLog("$modeName 启动成功!")
                    captureLogcat("after-success")
                    return true
                }
            } catch (e: Exception) {
                appendLog("启动异常: ${e.message}")
                captureLogcat("after-failure-$modeName")
            }
            appendLog("$modeName 失败，继续尝试下一个...")
        }

        appendLog("\n所有模式均失败")
        throw Exception("所有引擎模式均启动失败，请查看日志详情")
    }

    private suspend fun tryStartGtp(
        binary: String,
        modelPath: String,
        configPath: String,
        boardSize: Int,
        komi: Float
    ): Boolean {
        val client = GTPClient(
            binary,
            listOf("gtp", "-model", modelPath, "-config", configPath)
        )
        return try {
            client.start(libDir, workDir)
            appendLog("进程已启动，发送 name 命令...")
            val name = client.sendCommand("name")
            appendLog("name: $name")
            val version = client.sendCommand("version")
            appendLog("version: $version")
            client.sendCommand("boardsize $boardSize")
            client.sendCommand("komi $komi")
            client.sendCommand("time_settings 0 1 0")
            appendLog("GTP 初始化完成")
            gtpClient = client
            true
        } catch (e: Exception) {
            val exitCode = client.exitValue
            appendLog("失败 - 退出码: $exitCode")
            appendLog("异常: ${e.message}")
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
        try { gtpClient!!.sendCommand("name") } catch (_: Exception) {}
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
