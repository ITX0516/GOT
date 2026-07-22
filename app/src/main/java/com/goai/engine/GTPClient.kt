package com.goai.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** GTP 错误响应异常 */
class GTPException(message: String) : Exception(message)

/**
 * GTP (Go Text Protocol) 客户端
 * 通过子进程的 stdin/stdout 与围棋引擎（如 KataGo）通信
 */
class GTPClient(
    private val executablePath: String,
    private val arguments: List<String> = emptyList()
) {
    private val TAG = "GTPClient"

    /** 子进程 */
    private var process: Process? = null
    /** 写入子进程 stdin */
    private var writer: OutputStreamWriter? = null
    /** 读取子进程 stdout */
    private var reader: BufferedReader? = null
    /** 读取子进程 stderr */
    private var errorReader: BufferedReader? = null
    /** stderr 收集的错误信息 */
    private val errorBuffer = StringBuilder()

    /** 引擎子进程是否在运行 */
    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 获取最近的 stderr 输出 */
    val lastError: String
        get() = errorBuffer.toString()

    /** 启动子进程 */
    suspend fun start(): Unit = withContext(Dispatchers.IO) {
        val command = mutableListOf(executablePath).apply { addAll(arguments) }
        Log.d(TAG, "Starting process: ${command.joinToString(" ")}")
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(false)
        process = processBuilder.start()
        writer = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
        reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
        errorReader = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))

        Thread {
            try {
                var line: String?
                while (errorReader!!.readLine().also { line = it } != null) {
                    Log.e(TAG, "STDERR: $line")
                    synchronized(errorBuffer) {
                        errorBuffer.append(line).append("\n")
                        if (errorBuffer.length > 8192) {
                            errorBuffer.delete(0, errorBuffer.length - 8192)
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 发送 GTP 命令，返回响应内容（已去掉 "= " 前缀）
     * 错误响应（以 "? " 开头）会抛出 [GTPException]
     * 读取时按行读取，直到遇到空行（GTP 协议响应以空行结束）
     */
    suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
        val w = writer ?: throw IOException("GTPClient 未启动")
        val r = reader ?: throw IOException("GTPClient 未启动")
        // 写入命令并刷新
        w.write(command)
        w.write("\n")
        w.flush()
        readResponse(r)
    }

    /**
     * 发送 analyze 类命令（如 kata-analyze / lz-analyze），
     * 读取并返回第一条 info 行
     * 注意：analyze 命令会持续输出，本方法只取第一条；
     * 调用方需随后发送其它命令（如 name）以终止 analyze
     */
    suspend fun sendAnalyze(command: String): String = withContext(Dispatchers.IO) {
        val w = writer ?: throw IOException("GTPClient 未启动")
        val r = reader ?: throw IOException("GTPClient 未启动")
        w.write(command)
        w.write("\n")
        w.flush()
        while (true) {
            val line = r.readLine() ?: throw IOException("引擎已关闭输出流")
            if (line.startsWith("info ")) {
                return@withContext line
            }
            if (line.startsWith("?")) {
                // 命令不支持等错误：收集错误信息并消费完整错误响应（直到空行）
                val msg = if (line.startsWith("? ")) line.substring(2) else line.substring(1)
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.isEmpty()) break
                }
                throw GTPException(msg)
            }
            // 其它行（空行、analyze 期间的非 info 行）跳过
        }
        throw IOException("未能读取到 analyze 输出")
    }

    /** 关闭子进程及流 */
    fun close() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        process?.destroy()
        process = null
        writer = null
        reader = null
    }

    /**
     * 读取一个标准 GTP 响应（以 "= " 或 "? " 开头，空行结束）
     * 中间遇到的非标准行（如 analyze 残留的 info 行）会被跳过
     */
    private fun readResponse(r: BufferedReader): String {
        val response = StringBuilder()
        var headerHandled = false
        while (true) {
            val line = r.readLine() ?: throw IOException("引擎已关闭输出流")
            if (!headerHandled) {
                if (line.isEmpty()) {
                    // 响应头之前的空行，跳过
                    continue
                }
                when {
                    line.startsWith("= ") -> {
                        response.append(line.substring(2))
                        headerHandled = true
                    }
                    line.startsWith("=") -> {
                        response.append(line.substring(1))
                        headerHandled = true
                    }
                    line.startsWith("? ") -> throw GTPException(line.substring(2))
                    line.startsWith("?") -> throw GTPException(line.substring(1))
                    else -> {
                        // 非标准行（可能是 analyze 残留输出），跳过
                        continue
                    }
                }
            } else {
                if (line.isEmpty()) {
                    // 空行表示响应结束
                    break
                }
                response.append("\n").append(line)
            }
        }
        return response.toString().trim()
    }
}
