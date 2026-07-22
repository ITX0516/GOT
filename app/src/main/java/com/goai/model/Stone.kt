package com.goai.model

/** 棋子颜色 */
enum class Stone(val value: Int) {
    EMPTY(0),
    BLACK(1),
    WHITE(2);

    companion object {
        fun fromInt(v: Int): Stone = when (v) {
            1 -> BLACK
            2 -> WHITE
            else -> EMPTY
        }
    }

    /** 返回对手颜色 */
    fun opponent(): Stone = when (this) {
        BLACK -> WHITE
        WHITE -> BLACK
        else -> EMPTY
    }
}
