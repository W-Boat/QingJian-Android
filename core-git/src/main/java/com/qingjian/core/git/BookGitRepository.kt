package com.qingjian.core.git

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator

/**
 * Pure JVM Eclipse JGit repository manager for a single novel.
 * Enforces mandatory automatic full-book restore point creation before any
 * destructive branch switch or historical commit checkout.
 */
class BookGitRepository(
  val layout: BookRepoLayout,
  private val authorName: String = "青简作者",
  private val authorEmail: String = "author@qingjian.local",
) {
  private val gitDir: File get() = File(layout.repoRoot, ".git")

  suspend fun initIfNeeded(initialCommitMessage: String = "初始化书稿仓库"): GitCommitInfo =
    withContext(Dispatchers.IO) {
      layout.ensureDirectories()
      if (!gitDir.exists()) {
        Git.init().setDirectory(layout.repoRoot).setInitialBranch("main").call().use { git ->
          git.add().addFilepattern(".").call()
          val commit = git.commit()
            .setAllowEmpty(true)
            .setAuthor(PersonIdent(authorName, authorEmail))
            .setCommitter(PersonIdent(authorName, authorEmail))
            .setMessage(initialCommitMessage)
            .call()
          commit.toCommitInfo(listOf("main"))
        }
      } else {
        listCommits(limit = 1).firstOrNull() ?: commitAll(initialCommitMessage, allowEmpty = true)
      }
    }

  suspend fun commitAll(
    message: String,
    allowEmpty: Boolean = false,
  ): GitCommitInfo = withContext(Dispatchers.IO) {
    openGit().use { git ->
      git.add().addFilepattern(".").call()
      git.add().setUpdate(true).addFilepattern(".").call()
      val status = git.status().call()
      if (!allowEmpty && status.isClean) {
        val headId = git.repository.resolve(Constants.HEAD)
        if (headId != null) {
          RevWalk(git.repository).use { walk ->
            val headCommit = walk.parseCommit(headId)
            return@withContext headCommit.toCommitInfo(resolveBranchesPointingAt(git, headCommit.name))
          }
        }
      }
      val commit = git.commit()
        .setAllowEmpty(allowEmpty)
        .setAuthor(PersonIdent(authorName, authorEmail))
        .setCommitter(PersonIdent(authorName, authorEmail))
        .setMessage(message.ifBlank { "保存书稿变更" })
        .call()
      commit.toCommitInfo(resolveBranchesPointingAt(git, commit.name))
    }
  }

  suspend fun currentBranch(): String = withContext(Dispatchers.IO) {
    openGit().use { git ->
      git.repository.branch ?: "main"
    }
  }

  suspend fun listBranches(): List<GitBranchInfo> = withContext(Dispatchers.IO) {
    openGit().use { git ->
      val current = git.repository.branch
      git.branchList().call().map { ref ->
        val shortName = ref.name.removePrefix("refs/heads/")
        GitBranchInfo(
          name = shortName,
          headCommitHash = ref.objectId?.name.orEmpty(),
          isCurrent = shortName == current,
        )
      }
    }
  }

  suspend fun listCommits(limit: Int = 50): List<GitCommitInfo> = withContext(Dispatchers.IO) {
    openGit().use { git ->
      val headId = git.repository.resolve(Constants.HEAD) ?: return@withContext emptyList()
      val refByHash = mutableMapOf<String, MutableList<String>>()
      git.branchList().call().forEach { ref ->
        val hash = ref.objectId?.name ?: return@forEach
        refByHash.getOrPut(hash) { mutableListOf() }.add(ref.name.removePrefix("refs/heads/"))
      }
      git.log().add(headId).setMaxCount(limit).call().map { rev ->
        rev.toCommitInfo(refByHash[rev.name].orEmpty())
      }
    }
  }

  /**
   * Create a new route branch starting from any historical commit (or HEAD if null),
   * optionally switching to it immediately with mandatory anti-loss protection.
   */
  suspend fun createBranchFromCommit(
    branchName: String,
    startPointHash: String? = null,
    switchImmediately: Boolean = true,
  ): BranchSwitchResult = withContext(Dispatchers.IO) {
    val sanitized = sanitizeBranchName(branchName)
    val safetyPoint = createFullBookRestorePoint(
      label = "切换分支前自动备份 ($sanitized)",
      reason = "AUTO_BEFORE_BRANCH_CREATE",
    )
    openGit().use { git ->
      val createCmd = git.branchCreate().setName(sanitized)
      if (!startPointHash.isNullOrBlank()) {
        createCmd.setStartPoint(startPointHash)
      }
      val ref = createCmd.call()
      if (switchImmediately) {
        git.checkout()
          .setName(sanitized)
          .setForced(true)
          .call()
      }
      BranchSwitchResult(
        targetBranch = sanitized,
        headCommitHash = ref.objectId?.name.orEmpty(),
        safetyRestorePoint = safetyPoint,
      )
    }
  }

  /**
   * Mandatory anti-loss guard: always captures a complete restore point of the
   * working tree before switching branches.
   */
  suspend fun switchBranchWithSafetyGuard(targetBranch: String): BranchSwitchResult =
    withContext(Dispatchers.IO) {
      val safetyPoint = createFullBookRestorePoint(
        label = "切换至分支 $targetBranch 前自动备份",
        reason = "AUTO_BEFORE_BRANCH_SWITCH",
      )
      openGit().use { git ->
        git.checkout()
          .setName(targetBranch)
          .setForced(true)
          .call()
        val headHash = git.repository.resolve(Constants.HEAD)?.name.orEmpty()
        BranchSwitchResult(
          targetBranch = targetBranch,
          headCommitHash = headHash,
          safetyRestorePoint = safetyPoint,
        )
      }
    }

  /**
   * Loads a historical commit into the working directory (creating a safety restore point first)
   * without leaving the user stranded in detached HEAD: writes the historical tree files cleanly
   * and commits a restorative snapshot on the active branch.
   */
  suspend fun loadHistoricalCommitWithSafetyGuard(
    commitHash: String,
    commitMessage: String = "载入历史提交 ${commitHash.take(7)}",
  ): BranchSwitchResult = withContext(Dispatchers.IO) {
    val safetyPoint = createFullBookRestorePoint(
      label = "载入历史提交 ${commitHash.take(7)} 前自动备份",
      reason = "AUTO_BEFORE_HISTORICAL_LOAD",
    )
    openGit().use { git ->
      val current = git.repository.branch ?: "main"
      git.checkout()
        .setStartPoint(commitHash)
        .setAllPaths(true)
        .call()
      git.add().addFilepattern(".").call()
      git.add().setUpdate(true).addFilepattern(".").call()
      val newCommit = git.commit()
        .setAllowEmpty(true)
        .setAuthor(PersonIdent(authorName, authorEmail))
        .setCommitter(PersonIdent(authorName, authorEmail))
        .setMessage(commitMessage)
        .call()
      BranchSwitchResult(
        targetBranch = current,
        headCommitHash = newCommit.name,
        safetyRestorePoint = safetyPoint,
      )
    }
  }

  /**
   * Computes a file-by-file unified diff between two commits (or parent -> commit when baseHash is null).
   */
  suspend fun diffCommitsFileByFile(
    targetHash: String,
    baseHash: String? = null,
  ): List<FileDiffEntry> = withContext(Dispatchers.IO) {
    openGit().use { git ->
      val repo = git.repository
      val targetId = repo.resolve(targetHash) ?: return@withContext emptyList()
      RevWalk(repo).use { revWalk ->
        val targetCommit = revWalk.parseCommit(targetId)
        val baseCommit = when {
          !baseHash.isNullOrBlank() -> repo.resolve(baseHash)?.let { revWalk.parseCommit(it) }
          targetCommit.parentCount > 0 -> revWalk.parseCommit(targetCommit.getParent(0).id)
          else -> null
        }

        val reader = repo.newObjectReader()
        val oldTreeIter = if (baseCommit != null) {
          CanonicalTreeParser().apply { reset(reader, baseCommit.tree.id) }
        } else {
          EmptyTreeIterator()
        }
        val newTreeIter = CanonicalTreeParser().apply { reset(reader, targetCommit.tree.id) }

        val entries = git.diff()
          .setOldTree(oldTreeIter)
          .setNewTree(newTreeIter)
          .call()

        entries.map { entry ->
          val patchOut = ByteArrayOutputStream()
          DiffFormatter(patchOut).use { formatter ->
            formatter.setRepository(repo)
            formatter.format(entry)
          }
          val patchText = patchOut.toString(Charsets.UTF_8.name())
          var added = 0
          var deleted = 0
          patchText.lineSequence().forEach { line ->
            when {
              line.startsWith("+") && !line.startsWith("+++") -> added++
              line.startsWith("-") && !line.startsWith("---") -> deleted++
            }
          }
          val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
          val changeType = when (entry.changeType) {
            DiffEntry.ChangeType.ADD -> FileChangeType.ADD
            DiffEntry.ChangeType.DELETE -> FileChangeType.DELETE
            DiffEntry.ChangeType.RENAME -> FileChangeType.RENAME
            else -> FileChangeType.MODIFY
          }
          FileDiffEntry(
            path = path,
            changeType = changeType,
            unifiedPatch = patchText,
            addedLines = added,
            deletedLines = deleted,
          )
        }
      }
    }
  }

  /**
   * Creates an immutable full-book ZIP snapshot of the working tree (excluding .git and .staging)
   * stored outside the working tree in restorePointsDir.
   */
  suspend fun createFullBookRestorePoint(
    label: String,
    reason: String = "MANUAL",
  ): RestorePointSnapshot = withContext(Dispatchers.IO) {
    layout.ensureDirectories()
    val id = "rp_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
    val zipFile = File(layout.restorePointsDir, "$id.zip")
    val branch = if (gitDir.exists()) currentBranch() else "main"
    val headHash = if (gitDir.exists()) {
      openGit().use { it.repository.resolve(Constants.HEAD)?.name.orEmpty() }
    } else ""

    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      zipDirectory(layout.repoRoot, layout.repoRoot, zos)
    }

    RestorePointSnapshot(
      id = id,
      label = label,
      reason = reason,
      branchName = branch,
      commitHash = headHash,
      snapshotZipPath = zipFile.absolutePath,
      createdAt = System.currentTimeMillis(),
    )
  }

  /**
   * Restores a previously captured full-book restore point ZIP back into the working tree.
   * First saves a pre-rollback backup so even rollback itself is reversible.
   */
  suspend fun restoreFromSnapshot(snapshotZipPath: String): RestorePointSnapshot =
    withContext(Dispatchers.IO) {
      val preRollback = createFullBookRestorePoint(
        label = "回滚还原点前自动备份",
        reason = "AUTO_BEFORE_RESTORE_POINT",
      )
      val sourceZip = File(snapshotZipPath)
      require(sourceZip.exists()) { "还原点文件不存在: $snapshotZipPath" }

      // Clear tracked working files except .git
      layout.repoRoot.listFiles()?.forEach { child ->
        if (child.name != ".git") {
          child.deleteRecursively()
        }
      }

      ZipInputStream(FileInputStream(sourceZip)).use { zis ->
        var entry = zis.nextEntry
        val rootCanonical = layout.repoRoot.canonicalPath
        while (entry != null) {
          val dest = File(layout.repoRoot, entry.name)
          val destCanonical = dest.canonicalPath
          require(destCanonical.startsWith(rootCanonical + File.separator) || destCanonical == rootCanonical) {
            "非法还原路径: ${entry.name}"
          }
          if (entry.isDirectory) {
            dest.mkdirs()
          } else {
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { fos -> zis.copyTo(fos) }
          }
          zis.closeEntry()
          entry = zis.nextEntry
        }
      }

      if (gitDir.exists()) {
        commitAll("从还原点恢复工作区 (${sourceZip.nameWithoutExtension})", allowEmpty = true)
      }
      preRollback
    }

  private fun zipDirectory(root: File, current: File, zos: ZipOutputStream) {
    val children = current.listFiles() ?: return
    for (child in children) {
      if (child.name == ".git" || child.name == ".staging") continue
      val relPath = root.toURI().relativize(child.toURI()).path
      if (child.isDirectory) {
        zipDirectory(root, child, zos)
      } else {
        zos.putNextEntry(ZipEntry(relPath))
        FileInputStream(child).use { fis -> fis.copyTo(zos) }
        zos.closeEntry()
      }
    }
  }

  private fun openGit(): Git = Git.open(layout.repoRoot)

  private fun resolveBranchesPointingAt(git: Git, commitHash: String): List<String> =
    git.branchList().call()
      .filter { it.objectId?.name == commitHash }
      .map { it.name.removePrefix("refs/heads/") }

  private fun org.eclipse.jgit.revwalk.RevCommit.toCommitInfo(branches: List<String>): GitCommitInfo =
    GitCommitInfo(
      hash = name,
      shortHash = name.take(7),
      message = fullMessage.trim(),
      author = authorIdent?.name ?: authorName,
      timestampMillis = commitTime.toLong() * 1000L,
      parentHashes = parents.map { it.name },
      branchRefs = branches,
    )

  companion object {
    fun sanitizeBranchName(raw: String): String {
      val cleaned = raw.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}_\\-/]"), "")
        .trim('-', '/')
      return cleaned.ifBlank { "route-${System.currentTimeMillis() % 10000}" }
    }
  }
}
