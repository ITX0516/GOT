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

        val model10bFile = File(targetDir, "10b.bin.gz")
        val model20bHeadFile = File(targetDir, "20b.bin.gz")
        val model20bTfliteFile = File(targetDir, "20b.tflite")
        val configFile = File(targetDir, "gtp_static.cfg")

        copyAssetIfNewer(context, "katago/10b.bin", model10bFile)
        copyAssetIfNewer(context, "katago/20b_head.bin", model20bHeadFile)
        copyAssetIfNewer(context, "katago/20b.tflite", model20bTfliteFile)
        copyAssetIfNewer(context, "katago/gtp_static.cfg", configFile)

        val execFile = File(context.applicationInfo.nativeLibraryDir, "libkatago.so")

        KataGoPaths(
            executablePath = execFile.absolutePath,
            model10bPath = model10bFile.absolutePath,
            model20bHeadPath = model20bHeadFile.absolutePath,
            model20bTflitePath = model20bTfliteFile.absolutePath,
            configPath = configFile.absolutePath
        )
    }

    private fun copyAssetIfNewer(context: Context, assetPath: String, targetFile: File) {
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
    val model10bPath: String,
    val model20bHeadPath: String,
    val model20bTflitePath: String,
    val configPath: String
)
