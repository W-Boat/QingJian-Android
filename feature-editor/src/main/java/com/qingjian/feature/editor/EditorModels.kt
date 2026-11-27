package com.qingjian.feature.editor

import com.qingjian.core.git.FileDiffEntry
import com.qingjian.core.git.GitBranchInfo
import com.qingjian.core.git.GitCommitInfo
import com.qingjian.core.storage.RestorePointEntity

/**
 * Represents inline revision candidates attached directly under an edited paragraph.
 * Supports repeated regeneration, cycling between multiple versions, and manual tweaking.
 */
data class InlineRevisionCandidateState(
  val blockId: String,
  val originalMarkdown: String,
  val instruction: String,
  val candidates: List<String>,
  val activeIndex: Int = 0,
  val isGenerating: Boolean = false,
) {
  val activeCandidate: String get() = candidates.getOrElse(activeIndex) { originalMarkdown }
}

enum class FullScreenDiffKind(val label: String) {
  BATCH_REWRITE("多段联合改稿对比"),
  CHAPTER_SUMMARY("章节摘要审阅对比"),
  FULL_CHAPTER_DRAFT("整章初稿差异对比"),
}

data class FullScreenDiffProposal(
  val kind: FullScreenDiffKind,
  val title: String,
  val originalText: String,
  val proposedText: String,
  val targetBlockIds: List<String> = emptyList(),
  val reviewReport: String? = null,
)

data class GitWorkspaceSnapshot(
  val currentBranch: String = "main",
  val branches: List<GitBranchInfo> = emptyList(),
  val commits: List<GitCommitInfo> = emptyList(),
  val restorePoints: List<RestorePointEntity> = emptyList(),
  val selectedCommitHash: String? = null,
  val fileDiffs: List<FileDiffEntry> = emptyList(),
  val lastSafetyNotice: String? = null,
)
