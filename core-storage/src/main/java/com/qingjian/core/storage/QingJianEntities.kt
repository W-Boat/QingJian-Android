package com.qingjian.core.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room stores ONLY fast indexes and runtime metadata—never full chapter text or books.
 */
@Entity(tableName = "book_index")
data class BookIndexEntity(
  @PrimaryKey val bookId: String,
  val title: String,
  val author: String,
  val genre: String,
  val synopsis: String,
  val coverColorHex: Long,
  val coverAccentHex: Long,
  val wordCountGoalPerChapter: Int,
  val editorWidthDp: Int,
  val totalWords: Int,
  val chapterCount: Int,
  val currentBranch: String,
  val repoPath: String,
  val aiWizardCompleted: Boolean,
  val updatedAt: Long,
)

@Entity(tableName = "chapter_index")
data class ChapterIndexEntity(
  @PrimaryKey val compositeId: String,
  val bookId: String,
  val chapterId: String,
  val volumeTitle: String,
  val chapterOrder: Int,
  val title: String,
  val status: String,
  val wordCount: Int,
  val hasHandoff: Boolean,
  val searchExcerpt: String,
  val updatedAt: Long,
)

@Entity(tableName = "foreshadow_anchor_index")
data class ForeshadowAnchorEntity(
  @PrimaryKey val id: String,
  val bookId: String,
  val foreshadowId: String,
  val foreshadowTitle: String,
  val stage: String,
  val chapterId: String,
  val blockId: String,
  val excerpt: String,
  val resolved: Boolean,
  val createdAt: Long,
)

@Entity(tableName = "causal_edge_index")
data class CausalEdgeEntity(
  @PrimaryKey val id: String,
  val bookId: String,
  val fromNodeId: String,
  val toNodeId: String,
  val relationLabel: String,
  val confirmed: Boolean,
  val aiSuggested: Boolean,
)

enum class PipelineStage {
  STAGE_DRAFT,
  STAGE_REVIEW,
  STAGE_COMPLETED,
}

enum class PipelineStatus {
  PENDING,
  RUNNING,
  DRAFT_SAVED,
  REVIEW_FAILED,
  COMPLETED,
  FAILED,
}

/**
 * Durable checkpoint entity enabling resumable two-stage AI pipelines:
 * when STAGE_REVIEW fails after STAGE_DRAFT succeeded, retry reuses persistedDraftPath.
 */
@Entity(tableName = "pipeline_runs")
data class PipelineRunEntity(
  @PrimaryKey val runId: String,
  val bookId: String,
  val chapterId: String,
  val taskType: String,
  val stage: String,
  val status: String,
  val promptSummary: String,
  val persistedDraftPath: String?,
  val reviewReport: String?,
  val errorMessage: String?,
  val draftAttempts: Int,
  val reviewAttempts: Int,
  val updatedAt: Long,
)

@Entity(tableName = "ai_usage_logs")
data class AiUsageEntity(
  @PrimaryKey val id: String,
  val bookId: String,
  val providerName: String,
  val profileLabel: String,
  val modelName: String,
  val thinkingLevel: String,
  val promptTokens: Int,
  val completionTokens: Int,
  val latencyMs: Long,
  val timestamp: Long,
)

@Entity(tableName = "restore_points")
data class RestorePointEntity(
  @PrimaryKey val id: String,
  val bookId: String,
  val label: String,
  val reason: String,
  val branchName: String,
  val commitHash: String,
  val snapshotZipPath: String,
  val createdAt: Long,
)
