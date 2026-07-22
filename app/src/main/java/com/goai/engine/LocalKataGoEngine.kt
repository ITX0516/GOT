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
    private val configPath: String,
    private val gtpLogDir: String,
    private val libDir: String?,
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
                line.contains("DEBUG", ignoreCase = true) ||
                line.contains("crash", ignoreCase = true) ||
                line.contains("Crash", ignoreCase = true) ||
                line.contains("dlopen", ignoreCase = true) ||
                line.contains("linker", ignoreCase = true) ||
                line.contains("Linker", ignoreCase = true) ||
                line.contains("CANNOT LINK", ignoreCase = true)
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
        appendLog("配置文件: $configPath (${File(configPath).exists()}, ${File(configPath).length()} bytes)")
        appendLog("GTP日志目录: $gtpLogDir")
        appendLog("lib目录: $libDir")
        libDir?.let {
            val libDirFile = File(it)
            if (libDirFile.exists() && libDirFile.isDirectory) {
                val files = libDirFile.listFiles()?.map { f -> f.name }?.sorted()
                appendLog("lib目录文件数: ${files?.size ?: 0}")
                files?.forEach { f -> appendLog("  - $f") }
            }
        }

        val gtpLogDirFile = File(gtpLogDir)
        if (!gtpLogDirFile.exists()) {
            gtpLogDirFile.mkdirs()
            appendLog("创建日志目录: ${gtpLogDirFile.absolutePath}")
        }

        try { Runtime.getRuntime().exec("logcat -c").waitFor() } catch (_: Exception) {}

        val hasTflite = modelTflitePath != null && File(modelTflitePath).exists()

        val attempts = buildList {
            if (hasTflite && modelTflitePath != null) {
                add(Triple("nosnpe + bin.gz + tflite", katagoNoSnpePath, modelBinGzPath to modelTflitePath))
                add(Triple("dlc + bin.gz + tflite", katagoPath, modelBinGzPath to modelTflitePath))
            }
            add(Triple("nosnpe + bin.gz (纯CPU)", katagoNoSnpePath, modelBinGzPath to null))
            add(Triple("dlc + bin.gz", katagoPath, modelBinGzPath to null))
        }

        for ((modeName, binary, modelPair) in attempts) {
            val (model, tfliteModel) = modelPair

            appendLog("\n--- 尝试启动: $modeName ---")
            appendLog("二进制: $binary")
            appendLog("模型: $model")
            if (tfliteModel != null) {
                appendLog("TFLite模型: $tfliteModel")
            }

            try {
                val success = tryStartGtp(binary, model, tfliteModel, configPath, boardSize, komi)
                if (success) {
                    appendLog("$modeName 启动成功!")
                    captureLogcat("after-success")
                    return true
                }
            } catch (e: Exception) {
                appendLog("启动异常: ${e.message}")
                captureLogcat("after-failure-${modeName.replace(" ", "_")}")
            }
            appendLog("$modeName 失败，继续尝试下一个...")
        }

        captureLogcat("final-failure")
        appendLog("\n所有模式均失败")
        throw Exception("所有引擎模式均启动失败，请查看日志详情")
    }

    private suspend fun tryStartGtp(
        binary: String,
        modelPath: String,
        tfliteModelPath: String?,
        configPath: String,
        boardSize: Int,
        komi: Float
    ): Boolean {
        val args = mutableListOf("gtp", "-model", modelPath, "-config", configPath)
        if (tfliteModelPath != null) {
            args.add("-tflite-model")
            args.add(tfliteModelPath)
        }

        val commandStr = "$binary ${args.joinToString(" ")}"
        appendLog("启动命令：$commandStr")

        val client = GTPClient(binary, args)
        return try {
            client.start(libDir, libDir)
            appendLog("进程已启动，等待输出...")
            Thread.sleep(3000)
            val exitCode = client.exitValue
            if (exitCode != null) {
                appendLog("进程已退出，退出码: $exitCode")
                appendLog("STDERR: ${client.lastError}")
                val stdout = client.readAllStdout()
                appendLog("STDOUT: $stdout")
                client.close()
                return false
            }
            appendLog("进程仍在运行，发送 name 命令...")
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
            appendLog("STDERR: ${client.lastError}")
            try {
                val stdout = client.readAllStdout()
                appendLog("STDOUT: $stdout")
            } catch (_: Exception) {}
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
