package com.qingjian.core.git

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGitRepositoryTest {

  @Test
  fun branchSwitchCreatesMandatorySafetyRestorePointAndPreservesRoutes() = runTest {
    val rootDir = Files.createTempDirectory("qingjian_git_test").toFile()
    try {
      val repoDir = File(rootDir, "book_white_tower")
      val layout = BookRepoLayout(repoDir)
      val gitRepo = BookGitRepository(layout)

      gitRepo.initIfNeeded("初始化《白塔遗诏》")
      layout.chapterFile("ch_001").writeText("第一段：雪夜登塔。\n\n第二段：沈砚执灯。")
      val baseCommit = gitRepo.commitAll("完成第一章初稿")

      // Branch 1: Reconciliation route
      gitRepo.createBranchFromCommit("route-reconciliation", baseCommit.hash, switchImmediately = true)
      layout.chapterFile("ch_001").writeText("第一段：雪夜登塔。\n\n第二段：沈砚与顾衡握手言和。")
      val reconcileCommit = gitRepo.commitAll("和解路线定稿")

      // Switch back to main with unsaved dirty edits to verify safety restore point captures them
      layout.chapterFile("ch_001").writeText("未提交的临时草稿：雪夜骤变。")
      val switchResult = gitRepo.switchBranchWithSafetyGuard("main")

      // 1. Verify mandatory safety restore point was created on disk before checkout
      val safetyZip = File(switchResult.safetyRestorePoint.snapshotZipPath)
      assertTrue("Safety restore point zip must exist before branch switch", safetyZip.exists())
      assertEquals("AUTO_BEFORE_BRANCH_SWITCH", switchResult.safetyRestorePoint.reason)
      ZipFile(safetyZip).use { zf ->
        val entry = zf.getEntry("chapters/ch_001.md")
        assertTrue("Restore point must contain dirty chapter content", entry != null)
        val savedText = zf.getInputStream(entry).bufferedReader().readText()
        assertTrue(savedText.contains("未提交的临时草稿"))
      }

      // 2. Verify working tree now reflects main branch
      assertEquals("main", gitRepo.currentBranch())
      assertTrue(layout.chapterFile("ch_001").readText().contains("沈砚执灯"))

      // 3. Verify file-by-file diff between baseCommit and reconcileCommit
      val diffs = gitRepo.diffCommitsFileByFile(reconcileCommit.hash, baseCommit.hash)
      assertEquals(1, diffs.size)
      assertEquals("chapters/ch_001.md", diffs.first().path)
      assertEquals(FileChangeType.MODIFY, diffs.first().changeType)
      assertTrue(diffs.first().unifiedPatch.contains("握手言和"))

      // 4. Verify restoring from safety snapshot brings back the unsaved draft
      gitRepo.restoreFromSnapshot(safetyZip.absolutePath)
      assertTrue(layout.chapterFile("ch_001").readText().contains("未提交的临时草稿"))
    } finally {
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun epubExportProducesValidContainerAndChapterEntries() {
    val outDir = Files.createTempDirectory("qingjian_export_test").toFile()
    try {
      val book = ExportableBook(
        id = "book_1",
        title = "白塔遗诏",
        author = "林墨",
        synopsis = "冬夜白塔中的旧案。",
        chapters = listOf(
          ExportableChapter("ch_1", "第一卷", "第一章 雪夜登塔", 1, "风雪未歇。\n\n---\n\n灯火微明。"),
        ),
      )
      val epubFile = BookExportEngine.exportBook(book, ExportFormat.EPUB, outDir)
      assertTrue(epubFile.exists())
      ZipFile(epubFile).use { zip ->
        assertTrue(zip.getEntry("mimetype") != null)
        assertTrue(zip.getEntry("META-INF/container.xml") != null)
        assertTrue(zip.getEntry("OEBPS/content.opf") != null)
        assertTrue(zip.getEntry("OEBPS/ch_1.xhtml") != null)
      }
    } finally {
      outDir.deleteRecursively()
    }
  }
}
