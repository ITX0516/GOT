package com.goai.model

/** AI 分析数据 */
data class AnalysisData(
    val winrate: Float,          // 0.0 ~ 1.0（当前轮到方的胜率）
    val bestMove: String,        // GTP 坐标，如 "D17"
    val scoreLead: Float,        // 目差
    val candidateMoves: List<CandidateMove>
)

/** 候选走法 */
data class CandidateMove(
    val move: String,            // GTP 坐标
    val winrate: Float,          // 0.0 ~ 1.0
    val scoreLead: Float,
    val visits: Int              // 搜索次数
)
