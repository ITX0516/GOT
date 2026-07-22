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

        copyAssetIfNewer(context, "katago/model.bin.gz", modelFile)
        copyAssetIfNewer(context, "katago/gtp.cfg", configFile)

        val execFile = File(context.applicationInfo.nativeLibraryDir, "libkatago.so")

        KataGoPaths(
            executablePath = execFile.absolutePath,
            modelPath = modelFile.absolutePath,
            configPath = configFile.absolutePath
        )
    }

    private fun copyAssetIfNewer(context: Context, assetPath: String, targetFile: File) {
        val assetManager = context.assets
        val am = assetManager.openFd(assetPath)
        val assetSize = am.length
        am.close()

        if (targetFile.exists() && targetFile.length() == assetSize) {
            return
        }

        val inputStream = assetManager.open(assetPath)
        val outputStream = FileOutputStream(targetFile)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }
        inputStream.close()
        outputStream.flush()
        outputStream.close()
    }
}

data class KataGoPaths(
    val executablePath: String,
    val modelPath: String,
    val configPath: String
)
