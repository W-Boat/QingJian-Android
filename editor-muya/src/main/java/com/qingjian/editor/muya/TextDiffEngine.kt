package com.qingjian.editor.muya

enum class DiffOp {
  EQUAL,
  INSERT,
  DELETE,
}

data class DiffLine(
  val op: DiffOp,
  val text: String,
)

/**
 * Pure Kotlin LCS line-level diff engine for code-review-style inline cards and full-screen Diff view.
 */
object TextDiffEngine {
  fun diffLines(original: String, revised: String): List<DiffLine> {
    val a = original.replace("\r\n", "\n").split("\n")
    val b = revised.replace("\r\n", "\n").split("\n")
    val n = a.size
    val m = b.size

    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
      for (j in m - 1 downTo 0) {
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
      }
    }

    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
      when {
        a[i] == b[j] -> {
          result.add(DiffLine(DiffOp.EQUAL, a[i]))
          i++
          j++
        }
        dp[i + 1][j] >= dp[i][j + 1] -> {
          result.add(DiffLine(DiffOp.DELETE, a[i]))
          i++
        }
        else -> {
          result.add(DiffLine(DiffOp.INSERT, b[j]))
          j++
        }
      }
    }
    while (i < n) {
      result.add(DiffLine(DiffOp.DELETE, a[i++]))
    }
    while (j < m) {
      result.add(DiffLine(DiffOp.INSERT, b[j++]))
    }
    return result
  }
}

data class AtomicUndoRecord(
  val chapterId: String,
  val label: String,
  val previousMarkdown: String,
  val nextMarkdown: String,
  val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Atomic undo/redo stack recording every accepted AI or manual revision.
 */
class RevisionUndoStack(private val maxDepth: Int = 50) {
  private val undoStack = ArrayDeque<AtomicUndoRecord>()
  private val redoStack = ArrayDeque<AtomicUndoRecord>()

  fun push(record: AtomicUndoRecord) {
    undoStack.addLast(record)
    if (undoStack.size > maxDepth) {
      undoStack.removeFirst()
    }
    redoStack.clear()
  }

  fun canUndo(): Boolean = undoStack.isNotEmpty()
  fun canRedo(): Boolean = redoStack.isNotEmpty()
  fun undoCount(): Int = undoStack.size

  fun popUndo(): AtomicUndoRecord? {
    val rec = undoStack.removeLastOrNull() ?: return null
    redoStack.addLast(rec)
    return rec
  }

  fun popRedo(): AtomicUndoRecord? {
    val rec = redoStack.removeLastOrNull() ?: return null
    undoStack.addLast(rec)
    return rec
  }
}
