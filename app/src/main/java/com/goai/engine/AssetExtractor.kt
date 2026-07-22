package com.goai.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {

    private const val KATAGO_DIR = "katago"

    suspend fun extractKataGoAssets(context: Context): KataGoPaths = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, KATAGO_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val modelFile = File(targetDir, "model.bin.gz")
        val configFile = File(targetDir, "gtp.cfg")

        copyAssetIfNewer(context, "katago/model.bin", modelFile)
        copyAssetIfNewer(context, "katago/gtp.cfg", configFile)

        val execFile = File(context.applicationInfo.nativeLibraryDir, "libkatago.so")

        KataGoPaths(
            executablePath = execFile.absolutePath,
            modelPath = modelFile.absolutePath,
            configPath = configFile.absolutePath
        )
    }

    private fun copyAssetIfNewer(context: Context, assetPath: String, targetFile: File) {
        // openFd 对压缩的 asset 会抛异常，改用版本标记文件判断是否已释放
        val versionFile = File(targetFile.parentFile, ".extracted_version")
        val currentVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode.toString()

        if (versionFile.exists() && versionFile.readText() == currentVersion && targetFile.exists()) {
            return
        }

        val inputStream = context.assets.open(assetPath)
        val outputStream = FileOutputStream(targetFile)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        inputStream.close()
        outputStream.flush()
        outputStream.close()

        versionFile.writeText(currentVersion)
    }
}

data class KataGoPaths(
    val executablePath: String,
    val modelPath: String,
    val configPath: String
)
