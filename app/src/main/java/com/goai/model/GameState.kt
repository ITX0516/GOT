package com.goai.model

/** 游戏状态：棋盘 + 走棋历史 + 规则判定 */
class GameState(
    val boardSize: Int = 19,
    val komi: Float = 7.5f
) {
    // 棋盘：board[x][y]，0=空 1=黑 2=白
    private val board: Array<IntArray> = Array(boardSize) { IntArray(boardSize) }
    private val moveHistory: MutableList<Move> = mutableListOf()
    private var blackCaptured = 0  // 黑方提子数
    private var whiteCaptured = 0  // 白方提子数

    // 打劫禁着点
    private var koPoint: IntArray? = null

    /** 当前轮到谁 */
    var currentPlayer: Stone = Stone.BLACK
        private set

    /** 最后一步（用于标记） */
    var lastMove: Move? = null
        private set

    /** 获取棋盘某点 */
    fun getStone(x: Int, y: Int): Stone {
        if (x !in 0 until boardSize || y !in 0 until boardSize) return Stone.EMPTY
        return Stone.fromInt(board[x][y])
    }

    /** 获取走棋历史 */
    fun getMoves(): List<Move> = moveHistory.toList()

    /** 获取走棋历史（GTP 格式） */
    fun getMovesGTP(): List<String> = moveHistory.map { it.toGTP(boardSize) }

    /** 提子数 */
    fun getBlackCaptured() = blackCaptured
    fun getWhiteCaptured() = whiteCaptured

    /** 尝试落子，返回是否成功 */
    fun play(x: Int, y: Int, color: Stone): Boolean {
        if (x !in 0 until boardSize || y !in 0 until boardSize) return false
        if (board[x][y] != 0) return false

        // 检查打劫
        koPoint?.let { if (it[0] == x && it[1] == y) return false }

        // 临时落子
        board[x][y] = color.value

        // 检查并提除对方无气棋子
        val opponent = color.opponent()
        val captured = mutableListOf<IntArray>()
        for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until boardSize && ny in 0 until boardSize && board[nx][ny] == opponent.value) {
                val group = findGroup(nx, ny)
                if (countLiberties(group) == 0) {
                    captured.addAll(group)
                }
            }
        }

        // 提子
        for (pos in captured) {
            board[pos[0]][pos[1]] = 0
        }

        // 检查自杀
        if (captured.isEmpty()) {
            val myGroup = findGroup(x, y)
            if (countLiberties(myGroup) == 0) {
                board[x][y] = 0
                return false
            }
        }

        // 更新打劫点：如果只提了一子，且落子方只有一气
        if (captured.size == 1) {
            val myGroup = findGroup(x, y)
            if (myGroup.size == 1 && countLiberties(myGroup) == 1) {
                koPoint = intArrayOf(captured[0][0], captured[0][1])
            } else {
                koPoint = null
            }
        } else {
            koPoint = null
        }

        // 记录
        val move = Move(x, y, color)
        moveHistory.add(move)
        lastMove = move

        // 更新提子计数
        when (color) {
            Stone.BLACK -> blackCaptured += captured.size
            Stone.WHITE -> whiteCaptured += captured.size
            else -> {}
        }

        // 切换玩家
        currentPlayer = opponent
        return true
    }

    /** pass（停一手） */
    fun pass(color: Stone) {
        moveHistory.add(Move(0, 0, color, isPass = true))
        lastMove = null
        koPoint = null
        currentPlayer = color.opponent()
    }

    /** 悔棋（撤销最后一步） */
    fun undo(): Boolean {
        if (moveHistory.isEmpty()) return false
        // 重新从头走一遍，跳过最后一步
        val moves = moveHistory.dropLast(1)
        reset()
        for (m in moves) {
            if (m.isPass) {
                pass(m.color)
            } else {
                play(m.x, m.y, m.color)
            }
        }
        return true
    }

    /** 重置棋盘 */
    fun reset() {
        for (x in 0 until boardSize) {
            for (y in 0 until boardSize) {
                board[x][y] = 0
            }
        }
        moveHistory.clear()
        blackCaptured = 0
        whiteCaptured = 0
        koPoint = null
        currentPlayer = Stone.BLACK
        lastMove = null
    }

    /** 导出棋盘快照 */
    fun snapshot(): Array<IntArray> {
        return Array(boardSize) { board[it].copyOf() }
    }

    /** 从快照恢复 */
    fun restore(snapshot: Array<IntArray>) {
        for (x in 0 until boardSize) {
            for (y in 0 until boardSize) {
                board[x][y] = snapshot[x][y]
            }
        }
    }

    /** 找到连通棋块 */
    private fun findGroup(x: Int, y: Int): MutableList<IntArray> {
        val color = board[x][y]
        if (color == 0) return mutableListOf()
        val visited = mutableSetOf("$x,$y")
        val group = mutableListOf(IntArray(2).apply { this[0] = x; this[1] = y })
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(x, y))

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                val nx = pos[0] + dx
                val ny = pos[1] + dy
                val key = "$nx,$ny"
                if (nx in 0 until boardSize && ny in 0 until boardSize && !visited.contains(key) && board[nx][ny] == color) {
                    visited.add(key)
                    group.add(intArrayOf(nx, ny))
                    queue.add(intArrayOf(nx, ny))
                }
            }
        }
        return group
    }

    /** 计算棋块的气数 */
    private fun countLiberties(group: List<IntArray>): Int {
        val liberties = mutableSetOf<String>()
        for (pos in group) {
            for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                val nx = pos[0] + dx
                val ny = pos[1] + dy
                if (nx in 0 until boardSize && ny in 0 until boardSize && board[nx][ny] == 0) {
                    liberties.add("$nx,$ny")
                }
            }
        }
        return liberties.size
    }
}
