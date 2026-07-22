package com.goai.model

/** 一步棋 */
data class Move(
    val x: Int,       // 0-based 列号，0=A
    val y: Int,       // 0-based 行号，0=上方（GTP 坐标系）
    val color: Stone,
    val isPass: Boolean = false
) {
    /** 转成 GTP 坐标字符串，如 "D17" */
    fun toGTP(boardSize: Int): String {
        if (isPass) return "pass"
        val letters = "ABCDEFGHJKLMNOPQRST"
        if (x < 0 || x >= letters.length) return "pass"
        val gtpY = boardSize - y
        return "${letters[x]}$gtpY"
    }

    companion object {
        /** 从 GTP 坐标解析，如 "D17" → Move(x=3, y=2) on 19x19 */
        fun fromGTP(gtp: String, boardSize: Int, color: Stone): Move {
            if (gtp.lowercase() == "pass") return Move(0, 0, color, isPass = true)
            val letters = "ABCDEFGHJKLMNOPQRST"
            val x = letters.indexOf(gtp[0].uppercaseChar())
            val y = boardSize - gtp.substring(1).toInt()
            return Move(x, y, color)
        }
    }
}
