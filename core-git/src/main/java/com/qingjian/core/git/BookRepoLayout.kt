package com.qingjian.core.git

import java.io.File

/**
 * Canonical Git-First directory layout for every independent novel repository.
 * Prose and structured metadata live as readable Markdown / JSON files so
 * SQLite never stores large text blobs (avoiding Android CursorWindow 2MB crashes).
 */
class BookRepoLayout(val repoRoot: File) {
  val bookManifestFile: File get() = File(repoRoot, "book.json")
  val chaptersDir: File get() = File(repoRoot, "chapters")
  val stagingDir: File get() = File(repoRoot, "chapters/.staging")
  val handoffsDir: File get() = File(repoRoot, "handoffs")
  val loreDir: File get() = File(repoRoot, "lore")
  val loreEntitiesFile: File get() = File(loreDir, "entities.json")
  val loreCandidatesFile: File get() = File(loreDir, "candidates.json")
  val causalGraphFile: File get() = File(loreDir, "causal_graph.json")
  val plotPlansFile: File get() = File(loreDir, "plot_plans.json")
  val worldbookDir: File get() = File(repoRoot, "worldbook")
  val worldbookEntriesFile: File get() = File(worldbookDir, "entries.json")
  val foreshadowsFile: File get() = File(repoRoot, "foreshadows.json")
  val skillsDir: File get() = File(repoRoot, "skills")
  val skillsConfigFile: File get() = File(skillsDir, "config.json")
  val skillScriptsDir: File get() = File(skillsDir, "scripts")
  val rpDir: File get() = File(repoRoot, "rp")
  val rpCardsFile: File get() = File(rpDir, "cards.json")
  val rpSessionsFile: File get() = File(rpDir, "sessions.json")
  val restorePointsDir: File get() = File(repoRoot.parentFile, ".restore_points/${repoRoot.name}")

  fun chapterFile(chapterId: String): File = File(chaptersDir, "$chapterId.md")
  fun chapterMetaFile(chapterId: String): File = File(chaptersDir, "$chapterId.meta.json")
  fun handoffFile(chapterId: String): File = File(handoffsDir, "$chapterId.json")
  fun stagingDraftFile(runId: String): File = File(stagingDir, "${runId}_draft.md")

  fun ensureDirectories() {
    listOf(
      repoRoot,
      chaptersDir,
      stagingDir,
      handoffsDir,
      loreDir,
      worldbookDir,
      skillsDir,
      skillScriptsDir,
      rpDir,
      restorePointsDir,
    ).forEach { dir ->
      if (!dir.exists()) dir.mkdirs()
    }
    val gitIgnore = File(repoRoot, ".gitignore")
    if (!gitIgnore.exists()) {
      gitIgnore.writeText("chapters/.staging/\n.restore_points/\n")
    }
  }
}

data class GitCommitInfo(
  val hash: String,
  val shortHash: String,
  val message: String,
  val author: String,
  val timestampMillis: Long,
  val parentHashes: List<String>,
  val branchRefs: List<String> = emptyList(),
)

data class GitBranchInfo(
  val name: String,
  val headCommitHash: String,
  val isCurrent: Boolean,
)

enum class FileChangeType {
  ADD,
  MODIFY,
  DELETE,
  RENAME,
}

data class FileDiffEntry(
  val path: String,
  val changeType: FileChangeType,
  val unifiedPatch: String,
  val addedLines: Int,
  val deletedLines: Int,
)

data class RestorePointSnapshot(
  val id: String,
  val label: String,
  val reason: String,
  val branchName: String,
  val commitHash: String,
  val snapshotZipPath: String,
  val createdAt: Long,
)

data class BranchSwitchResult(
  val targetBranch: String,
  val headCommitHash: String,
  val safetyRestorePoint: RestorePointSnapshot,
)
