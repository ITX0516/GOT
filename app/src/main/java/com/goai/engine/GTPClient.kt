package com.goai.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
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
    /** 启动命令 */
    private var commandStr: String = ""

    /** 引擎是否在运行 */
    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 进程退出码（如果已退出） */
    val exitValue: Int?
        get() = try {
            process?.exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }

    /** 获取最近的 stderr 输出 */
    val lastError: String
        get() = errorBuffer.toString()

    /** 读取所有可用的 stdout 输出（进程退出后调用） */
    fun readAllStdout(): String {
        val r = reader ?: return ""
        val builder = StringBuilder()
        try {
            var line: String?
            while (true) {
                line = r.readLine()
                if (line == null) break
                builder.append(line).append("\n")
            }
        } catch (_: Exception) {
        }
        return builder.toString()
    }

    /** 启动子进程 */
    suspend fun start(libDir: String? = null, workDir: String? = null): Unit = withContext(Dispatchers.IO) {
        val command = mutableListOf(executablePath).apply { addAll(arguments) }
        commandStr = command.joinToString(" ")
        Log.d(TAG, "Starting process: $commandStr")
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(false)
        if (workDir != null) {
            processBuilder.directory(File(workDir))
            Log.d(TAG, "Working directory: $workDir")
        }
        if (libDir != null) {
            val env = processBuilder.environment()
            // 完全照抄 BadukAI 的环境变量设置
            val currentLdPath = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] = if (currentLdPath.isNullOrEmpty()) {
                "$libDir:/vendor/lib64:/system/vendor/lib64"
            } else {
                "$libDir:$currentLdPath:/vendor/lib64:/system/vendor/lib64"
            }
            env["ADSP_LIBRARY_PATH"] = "$libDir;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"
            Log.d(TAG, "LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
            Log.d(TAG, "ADSP_LIBRARY_PATH=${env["ADSP_LIBRARY_PATH"]}")
        }
        process = processBuilder.start()
        writer = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
        reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
        errorReader = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))

        // stderr 收集线程
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

        // 等待 1.5 秒，检查进程是否异常退出
        try {
            Thread.sleep(1500)
        } catch (_: InterruptedException) {
        }
        val exit = try {
            process?.exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }
        if (exit != null) {
            Thread.sleep(300)
            val stderr = synchronized(errorBuffer) { errorBuffer.toString() }
            // 读取所有可用的 stdout
            val stdoutBuilder = StringBuilder()
            try {
                while (reader!!.ready()) {
                    val line = reader!!.readLine()
                    if (line != null) {
                        stdoutBuilder.append(line).append("\n")
                    } else {
                        break
                    }
                }
            } catch (_: Exception) {
            }
            // 如果 ready() 没读到，尝试直接读
            if (stdoutBuilder.isEmpty()) {
                try {
                    val chars = CharArray(4096)
                    val n = reader!!.read(chars)
                    if (n > 0) {
                        stdoutBuilder.append(chars, 0, n)
                    }
                } catch (_: Exception) {
                }
            }
            val stdout = stdoutBuilder.toString()
            throw IOException(
                "引擎进程已退出，退出码：$exit\n\n" +
                "启动命令：$commandStr\n\n" +
                "=== STDOUT ===\n$stdout\n\n" +
                "=== STDERR ===\n$stderr"
            )
        }
        Log.d(TAG, "Process started successfully")
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
