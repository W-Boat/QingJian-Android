package com.qingjian.core.storage

import com.qingjian.core.git.BookRepoLayout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumablePipelineExecutorTest {

  private class InMemoryPipelineStore : PipelineStateStore {
    private val map = mutableMapOf<String, PipelineRunEntity>()
    override suspend fun getRun(runId: String): PipelineRunEntity? = map[runId]
    override suspend fun upsert(run: PipelineRunEntity) {
      map[run.runId] = run
    }
  }

  @Test
  fun retryAfterReviewFailureReusesPersistedDraftWithoutRegenerating() = runTest {
    val tempDir = Files.createTempDirectory("qingjian_pipeline_test").toFile()
    try {
      val layout = BookRepoLayout(tempDir)
      val store = InMemoryPipelineStore()
      val executor = ResumablePipelineExecutor(store, layout)

      val draftCalls = AtomicInteger(0)
      val reviewCalls = AtomicInteger(0)

      // First run: draft succeeds & persists, review throws network error
      val firstResult = executor.executeOrResume(
        runId = "run_ch_004",
        bookId = "book_1",
        chapterId = "ch_004",
        taskType = "CHAPTER_DRAFT",
        promptSummary = "续写第四章白塔对峙",
        generateDraft = {
          draftCalls.incrementAndGet()
          "白塔钟声三响，雪屑从窗棂灌入。"
        },
        runReview = {
          reviewCalls.incrementAndGet()
          throw IllegalStateException("复审接口超时")
        },
      )

      assertEquals(1, draftCalls.get())
      assertEquals(1, reviewCalls.get())
      assertEquals(PipelineStatus.REVIEW_FAILED.name, firstResult.run.status)
      assertFalse(firstResult.reusedPersistedDraft)
      assertTrue(!firstResult.run.persistedDraftPath.isNullOrBlank())

      // Second run (resume): MUST reuse persisted draft and skip generateDraft completely
      val retryResult = executor.executeOrResume(
        runId = "run_ch_004",
        bookId = "book_1",
        chapterId = "ch_004",
        taskType = "CHAPTER_DRAFT",
        promptSummary = "续写第四章白塔对峙",
        generateDraft = {
          draftCalls.incrementAndGet()
          "不应被重新生成的草稿"
        },
        runReview = { persisted ->
          reviewCalls.incrementAndGet()
          "复审通过：字数 ${persisted.length}，结构完整"
        },
      )

      assertEquals("Stage 1 must NOT execute again on resume", 1, draftCalls.get())
      assertEquals("Stage 2 must execute on resume", 2, reviewCalls.get())
      assertTrue(retryResult.reusedPersistedDraft)
      assertEquals(PipelineStatus.COMPLETED.name, retryResult.run.status)
      assertEquals("白塔钟声三响，雪屑从窗棂灌入。", retryResult.draftText)
      assertTrue(retryResult.reviewReport!!.contains("复审通过"))
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
