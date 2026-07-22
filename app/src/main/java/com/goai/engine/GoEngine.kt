package com.goai.engine

import com.goai.model.AnalysisData
import com.goai.model.Stone

/** 围棋引擎接口 */
interface GoEngine {
    /** 引擎名称 */
    val name: String

    /** 引擎是否就绪 */
    val isReady: Boolean

    /** 初始化引擎 */
    suspend fun init(boardSize: Int, komi: Float): Boolean

    /** 生成一步棋 */
    suspend fun genMove(color: Stone, gameState: com.goai.model.GameState): String

    /** 分析当前局面 */
    suspend fun analyze(gameState: com.goai.model.GameState): AnalysisData?

    /** 释放资源 */
    fun close()
}
