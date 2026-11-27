package com.qingjian.editor.muya

/**
 * Pure Kotlin Markdown Structure Guard (Pillar 1):
 * 1. Detects when candidate only tweaks Markdown syntax while plain text is identical ("正文无改动").
 * 2. Blocks acceptance when candidate breaks original Markdown structural invariants
 *    (heading level, list structure, blockquote, scene divider, balanced inline emphasis, or illegal paragraph split).
 */
sealed class StructureGuardResult {
  data object Valid : StructureGuardResult()
  data class NoTextChange(val message: String) : StructureGuardResult()
  data class StructureBroken(val issues: List<String>) : StructureGuardResult()
}

object MarkdownStructureGuard {

  fun validate(originalMarkdown: String, candidateMarkdown: String): StructureGuardResult {
    val orig = originalMarkdown.replace("\r\n", "\n").trim()
    val cand = candidateMarkdown.replace("\r\n", "\n").trim()

    if (cand.isBlank()) {
      return StructureGuardResult.StructureBroken(listOf("候选内容为空，无法替换原段落。"))
    }

    val issues = mutableListOf<String>()

    // 1. Heading level preservation
    val origHeading = Regex("^(#{1,6})\\s+").find(orig)?.groupValues?.get(1)
    val candHeading = Regex("^(#{1,6})\\s+").find(cand)?.groupValues?.get(1)
    if (origHeading != candHeading) {
      issues.add(
        when {
          origHeading != null && candHeading == null -> "原段落为 ${origHeading.length} 级标题，候选丢失了标题标记（$origHeading）。"
          origHeading == null && candHeading != null -> "原段落为正文，候选被意外改写为 ${candHeading.length} 级标题。"
          else -> "标题层级发生变化（原为 ${origHeading!!.length} 级，候选为 ${candHeading!!.length} 级）。"
        }
      )
    }

    // 2. Scene divider preservation
    val origIsDivider = isSceneDivider(orig)
    val candIsDivider = isSceneDivider(cand)
    if (origIsDivider != candIsDivider) {
      issues.add("场景分隔线结构被破坏（原段落与候选的分隔线标记不一致）。")
    } else {
      val origDividerCount = countSceneDividers(orig)
      val candDividerCount = countSceneDividers(cand)
      if (origDividerCount != candDividerCount) {
        issues.add("场景分隔线数量发生变化（原 $origDividerCount 处，候选 $candDividerCount 处）。")
      }
    }

    // 3. Blockquote structure preservation
    val origIsQuote = orig.lines().all { it.trim().startsWith(">") }
    val candIsQuote = cand.lines().all { it.trim().startsWith(">") }
    if (origIsQuote && !candIsQuote) {
      issues.add("原段落为引用块（>），候选破坏了引用前缀结构。")
    }

    // 4. List prefix preservation
    val origUnordered = orig.lines().all { Regex("^[-*+]\\s+").containsMatchIn(it.trim()) }
    val candUnordered = cand.lines().all { Regex("^[-*+]\\s+").containsMatchIn(it.trim()) }
    if (origUnordered && !candUnordered) {
      issues.add("原段落为无序列表，候选破坏了列表项标记。")
    }

    val origOrdered = orig.lines().all { Regex("^\\d+\\.\\s+").containsMatchIn(it.trim()) }
    val candOrdered = cand.lines().all { Regex("^\\d+\\.\\s+").containsMatchIn(it.trim()) }
    if (origOrdered && !candOrdered) {
      issues.add("原段落为有序列表，候选破坏了编号列表结构。")
    }

    // 5. Paired inline emphasis / code / strikethrough balance
    checkPairedToken(cand, "**", "加粗标记 (**)")?.let { issues.add(it) }
    checkPairedToken(cand, "~~", "删除线标记 (~~)")?.let { issues.add(it) }
    checkPairedToken(cand, "`", "行内代码反引号 (`)")?.let { issues.add(it) }
    val withoutBold = cand.replace("**", "")
    val singleAsterisks = withoutBold.count { it == '*' }
    if (singleAsterisks % 2 != 0) {
      issues.add("斜体标记 (*) 未成对闭合，请重新生成或手动修复。")
    }

    // 6. Abnormal paragraph splitting when rewriting a single paragraph block
    val origBlockCount = countParagraphBlocks(orig)
    val candBlockCount = countParagraphBlocks(cand)
    if (origBlockCount == 1 && candBlockCount > 2) {
      issues.add("单段改稿被异常拆分为 $candBlockCount 个独立段落，破坏原有章节锚点结构。")
    }

    if (issues.isNotEmpty()) {
      return StructureGuardResult.StructureBroken(issues)
    }

    // 7. No-text-change detection: plain text after stripping Markdown syntax is identical
    if (stripMarkdownToPlainText(orig) == stripMarkdownToPlainText(cand)) {
      return StructureGuardResult.NoTextChange("正文无改动（仅 Markdown 标记或空白有细微变化）")
    }

    return StructureGuardResult.Valid
  }

  fun stripMarkdownToPlainText(markdown: String): String {
    return markdown
      .replace("\r\n", "\n")
      .replace(Regex("(?m)^#{1,6}\\s+"), "")
      .replace(Regex("(?m)^>\\s?"), "")
      .replace(Regex("(?m)^[-*+]\\s+"), "")
      .replace(Regex("(?m)^\\d+\\.\\s+"), "")
      .replace("**", "")
      .replace("~~", "")
      .replace("*", "")
      .replace("_", "")
      .replace("`", "")
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun isSceneDivider(block: String): Boolean =
    Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(block.trim())

  private fun countSceneDividers(text: String): Int =
    text.lines().count { isSceneDivider(it) }

  private fun countParagraphBlocks(text: String): Int =
    text.split(Regex("\\n\\s*\\n")).map { it.trim() }.count { it.isNotEmpty() }

  private fun checkPairedToken(text: String, token: String, label: String): String? {
    var count = 0
    var idx = 0
    while (true) {
      val found = text.indexOf(token, idx)
      if (found < 0) break
      count++
      idx = found + token.length
    }
    return if (count % 2 != 0) "$label 未成对闭合（共出现 $count 次）。" else null
  }
}
