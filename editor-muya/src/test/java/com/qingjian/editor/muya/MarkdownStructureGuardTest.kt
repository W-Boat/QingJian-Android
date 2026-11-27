package com.qingjian.editor.muya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStructureGuardTest {

  @Test
  fun detectsNoTextChangeWhenOnlyMarkdownFormattingDrifts() {
    val original = "沈砚推开**白塔**木门，风雪灌入长廊。"
    val candidate = "沈砚推开*白塔*木门，风雪灌入长廊。"
    val result = MarkdownStructureGuard.validate(original, candidate)
    assertTrue("Should flag NO_TEXT_CHANGE when plain text is unchanged", result is StructureGuardResult.NoTextChange)
  }

  @Test
  fun blocksAcceptanceWhenHeadingLevelOrEmphasisOrSceneBreakIsBroken() {
    // 1. Heading level broken
    val headingBroken = MarkdownStructureGuard.validate("## 第一章 雪夜登塔", "第一章 雪夜登塔")
    assertTrue(headingBroken is StructureGuardResult.StructureBroken)

    // 2. Unclosed bold emphasis
    val emphasisBroken = MarkdownStructureGuard.validate(
      "他按住腰间青铜印。",
      "他按住腰间**青铜印，目光微冷。",
    )
    assertTrue(emphasisBroken is StructureGuardResult.StructureBroken)

    // 3. Scene divider removed
    val dividerBroken = MarkdownStructureGuard.validate("---", "风雪停歇。")
    assertTrue(dividerBroken is StructureGuardResult.StructureBroken)

    // 4. Single paragraph abnormally split into 3+ blocks
    val splitBroken = MarkdownStructureGuard.validate(
      "雪夜白塔钟声三响，顾衡立在阶前未动。",
      "雪夜白塔钟声三响。\n\n顾衡立在阶前。\n\n门外忽然传来马蹄声。",
    )
    assertTrue(splitBroken is StructureGuardResult.StructureBroken)
  }

  @Test
  fun acceptsValidSubstantiveRevisionPreservingMarkdownStructure() {
    val original = "> 白塔禁令：子时之后不得启封诏匣。"
    val candidate = "> 白塔夜禁：子时一过，凡私启诏匣者格杀勿论。"
    val result = MarkdownStructureGuard.validate(original, candidate)
    assertEquals(StructureGuardResult.Valid, result)
  }

  @Test
  fun diffEngineAndAtomicUndoStackWorkCleanly() {
    val diffs = TextDiffEngine.diffLines("第一行\n旧第二行\n第三行", "第一行\n新第二行\n第三行")
    assertTrue(diffs.any { it.op == DiffOp.DELETE && it.text == "旧第二行" })
    assertTrue(diffs.any { it.op == DiffOp.INSERT && it.text == "新第二行" })

    val stack = RevisionUndoStack()
    stack.push(AtomicUndoRecord("ch_1", "接受单段改稿", "旧文", "新文"))
    assertTrue(stack.canUndo())
    val popped = stack.popUndo()!!
    assertEquals("旧文", popped.previousMarkdown)
    assertTrue(stack.canRedo())
  }
}
