package com.qingjian.core.storage

import java.util.UUID

enum class ChapterStatus(val label: String) {
  DRAFT("草稿"),
  FINALIZED("已定稿"),
}

enum class WritingMode(val label: String) {
  HUMAN_LED("人类主笔"),
  AI_DRIVEN("AI 驱动"),
}

enum class FeedbackVote {
  NONE,
  UP,
  DOWN,
}

data class ParagraphBlock(
  val id: String,
  val index: Int,
  val markdown: String,
  val vote: FeedbackVote = FeedbackVote.NONE,
  val annotation: String = "",
)

data class ChapterDocument(
  val chapterId: String,
  val bookId: String,
  val volumeTitle: String,
  val title: String,
  val order: Int,
  val status: ChapterStatus = ChapterStatus.DRAFT,
  val summary: String = "",
  val markdownContent: String = "",
  val blockFeedback: Map<String, FeedbackVote> = emptyMap(),
  val blockAnnotations: Map<String, String> = emptyMap(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  fun wordCount(): Int = markdownContent.count { !it.isWhitespace() }

  fun toBlocks(): List<ParagraphBlock> {
    val rawBlocks = markdownContent
      .replace("\r\n", "\n")
      .split(Regex("\\n\\s*\\n"))
      .map { it.trim() }
      .filter { it.isNotEmpty() }
    return rawBlocks.mapIndexed { idx, text ->
      val blockId = "blk_${chapterId}_${idx + 1}"
      ParagraphBlock(
        id = blockId,
        index = idx,
        markdown = text,
        vote = blockFeedback[blockId] ?: FeedbackVote.NONE,
        annotation = blockAnnotations[blockId].orEmpty(),
      )
    }
  }
}

/**
 * Pillar 2: Structured Handoff Brief generated when finalizing a chapter (or backfilled later).
 */
data class HandoffDirection(
  val id: String = "dir_${UUID.randomUUID().toString().take(8)}",
  val title: String,
  val premise: String,
  val tension: String,
)

data class HandoffBrief(
  val chapterId: String,
  val chapterTitle: String,
  val happenedFacts: List<String>,
  val writingLessons: List<String>,
  val nextChapterHooks: List<String>,
  val nextDirections: List<HandoffDirection>,
  val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Pillar 3: Five-stage cross-chapter foreshadow anchors bound to (chapterId, blockId).
 */
enum class ForeshadowStage(val label: String) {
  SETUP("铺垫"),
  PLANT("埋设"),
  CLUE("线索"),
  ECHO("呼应"),
  PAYOFF("回收"),
}

data class ForeshadowAnchor(
  val id: String = "fa_${UUID.randomUUID().toString().take(8)}",
  val stage: ForeshadowStage,
  val chapterId: String,
  val chapterTitle: String,
  val blockId: String,
  val excerpt: String,
  val note: String = "",
  val createdAt: Long = System.currentTimeMillis(),
)

data class ForeshadowThread(
  val id: String = "fs_${UUID.randomUUID().toString().take(8)}",
  val title: String,
  val description: String,
  val anchors: List<ForeshadowAnchor> = emptyList(),
  val isResolved: Boolean = false,
) {
  fun withAddedAnchor(anchor: ForeshadowAnchor): ForeshadowThread {
    val updated = anchors + anchor
    val resolvedNow = isResolved || anchor.stage == ForeshadowStage.PAYOFF
    return copy(anchors = updated, isResolved = resolvedNow)
  }
}

enum class LoreCategory(val label: String) {
  CHARACTER("人物"),
  WORLD_RULE("世界规则"),
  SCENE("场景"),
  EVENT("事件"),
  ANNOTATION("批注"),
}

data class LoreEntry(
  val id: String = "lore_${UUID.randomUUID().toString().take(8)}",
  val category: LoreCategory,
  val title: String,
  val content: String,
  val sourceChapterId: String = "",
  val sourceBlockId: String = "",
  val sourceExcerpt: String = "",
  val confirmedAt: Long = System.currentTimeMillis(),
)

data class LoreExtractionCandidate(
  val id: String = "cand_${UUID.randomUUID().toString().take(8)}",
  val category: LoreCategory,
  val title: String,
  val summary: String,
  val chapterId: String,
  val chapterTitle: String,
  val blockId: String,
  val verbatimExcerpt: String,
)

data class CausalNode(
  val id: String,
  val chapterId: String,
  val title: String,
  val detail: String,
  val x: Float,
  val y: Float,
)

data class CausalEdge(
  val id: String = "edge_${UUID.randomUUID().toString().take(8)}",
  val fromNodeId: String,
  val toNodeId: String,
  val relationLabel: String,
  val confirmed: Boolean = false,
  val aiSuggested: Boolean = true,
)

data class CausalGraphData(
  val nodes: List<CausalNode> = emptyList(),
  val edges: List<CausalEdge> = emptyList(),
)

/**
 * Strictly separated from confirmed facts: unconfirmed alternative plot directions and planning notes.
 */
data class PlotPlanIdea(
  val id: String = "plan_${UUID.randomUUID().toString().take(8)}",
  val title: String,
  val sourceChapterId: String,
  val premise: String,
  val keyBeats: List<String>,
  val branchTag: String = "构思草案",
  val createdAt: Long = System.currentTimeMillis(),
)

data class BookManifest(
  val id: String,
  val title: String,
  val author: String,
  val genre: String,
  val synopsis: String,
  val coverColorHex: Long,
  val coverAccentHex: Long,
  val wordCountGoalPerChapter: Int = 3000,
  val editorWidthDp: Int = 680,
  val writingMode: WritingMode = WritingMode.HUMAN_LED,
  val aiWizardCompleted: Boolean = false,
  val referenceStyleExcerpt: String = "",
  val correctionRules: List<String> = emptyList(),
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)
