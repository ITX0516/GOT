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
 *
 * 重要：BadukAI 的 KataGo 使用 __android_log_print 输出日志到 logcat，
 * 而不是 stdout/stderr。因此需要在启动前后捕获 logcat 来获取错误信息。
 */
class LocalKataGoEngine(
    private val executablePath: String,
    private val modelPath: String,
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

    /** 清空 logcat 缓冲区 */
    private fun clearLogcat() {
        try {
            val pb = ProcessBuilder("logcat", "-c")
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.waitFor()
        } catch (_: Exception) {}
    }

    /** 读取 logcat 输出（dump 模式） */
    private fun dumpLogcat(): String {
        return try {
            val pb = ProcessBuilder("logcat", "-d")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            // 过滤出与 KataGo 相关的日志行
            output.split("\n").filter { line ->
                line.contains("KataGo", true) ||
                line.contains("katago", true) ||
                line.contains("tflite", true) ||
                line.contains("TfLite", true) ||
                line.contains("QNN", true) ||
                line.contains("qnn", true) ||
                line.contains("NPU", true) ||
                line.contains("nnapi", true) ||
                line.contains("NNAPI", true) ||
                line.contains("model", true) ||
                line.contains("config", true) ||
                line.contains("error", true) ||
                line.contains("Error", true) ||
                line.contains("ERROR", true) ||
                line.contains("FATAL", true) ||
                line.contains("fail", true) ||
                line.contains("Fail", true) ||
                line.contains("FAIL", true) ||
                line.contains("backend", true) ||
                line.contains("Backend", true) ||
                line.contains("GTP", true) ||
                line.contains("libkatago", true) ||
                line.contains("Exception", true) ||
                line.contains("abort", true) ||
                line.contains("cannot", true) ||
                line.contains("Cannot", true) ||
                line.contains("Could not", true) ||
                line.contains("WARNING", true) ||
                line.contains("unused", true) ||
                line.contains("Unknown", true) ||
                line.contains("invalid", true) ||
                line.contains("Invalid", true)
            }.joinToString("\n")
        } catch (e: Exception) {
            "读取logcat失败: ${e.message}"
        }
    }

    /** 初始化引擎 */
    override suspend fun init(boardSize: Int, komi: Float): Boolean {
        appendLog("=== 开始初始化 KataGo 引擎 ===")
        appendLog("可执行文件: $executablePath (存在: ${File(executablePath).exists()})")
        appendLog("模型文件: $modelPath (存在: ${File(modelPath).exists()}, 大小: ${File(modelPath).length()})")
        appendLog("配置文件: $configPath (存在: ${File(configPath).exists()}, 大小: ${File(configPath).length()})")
        appendLog("库目录: $libDir")

        // 列出库目录中的所有文件
        libDir?.let {
            val libDirFile = File(it)
            if (libDirFile.exists() && libDirFile.isDirectory) {
                appendLog("库目录文件列表:")
                libDirFile.listFiles()?.forEach { f ->
                    appendLog("  ${f.name} (${f.length()} bytes)")
                }
            }
        }

        // 测试1: version 命令（捕获 logcat）
        appendLog("\n--- 测试1: version 命令 ---")
        clearLogcat()
        runAndCaptureWithLogcat(listOf("version"))

        // 测试2: -help 命令（捕获 logcat）
        appendLog("\n--- 测试2: -help 命令 ---")
        clearLogcat()
        runAndCaptureWithLogcat(listOf("-help"))

        // 测试3: 完整 GTP 启动
        appendLog("\n--- 测试3: 完整GTP启动 (model + config) ---")
        clearLogcat()
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
            appendLog("异常消息: ${e.message}")
            appendLog("STDERR: $stderr")

            // 捕获 logcat 输出
            val logcatOutput = dumpLogcat()
            appendLog("\n=== LOGCAT 输出 ===")
            appendLog(logcatOutput)
            appendLog("=== LOGCAT 结束 ===\n")

            throw Exception("${e.message}\nSTDERR: $stderr\n\nLOGCAT:\n$logcatOutput")
        }
    }

    /** 运行命令并捕获 stdout/stderr 和 logcat */
    private suspend fun runAndCaptureWithLogcat(args: List<String>) {
        val client = GTPClient(executablePath, args)
        var startException: Exception? = null
        try {
            client.start(libDir)
            Thread.sleep(2000)
            if (client.isRunning) {
                appendLog("进程仍在运行")
            }
        } catch (e: Exception) {
            startException = e
        }

        val exitCode = client.exitValue
        val stderr = client.lastError
        val exceptionOutput = startException?.message ?: ""
        val remainingStdout = try {
            client.readAllStdout()
        } catch (_: Exception) { "" }

        appendLog("退出码: $exitCode")
        appendLog("=== 异常消息(含stdout/stderr) ===\n$exceptionOutput")
        appendLog("=== 剩余STDOUT ===\n$remainingStdout")
        appendLog("=== STDERR缓冲 ===\n$stderr")

        // 捕获 logcat
        val logcatOutput = dumpLogcat()
        appendLog("=== LOGCAT 输出 ===")
        appendLog(logcatOutput)
        appendLog("=== LOGCAT 结束 ===")

        client.close()
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
