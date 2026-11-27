package com.qingjian.core.storage

import com.qingjian.core.git.BookRepoLayout
import java.io.File
import java.util.UUID

/**
 * Storage contract for pipeline checkpoints, implemented by Room in production
 * and by an in-memory store in fast JVM unit tests.
 */
interface PipelineStateStore {
  suspend fun getRun(runId: String): PipelineRunEntity?
  suspend fun upsert(run: PipelineRunEntity)
}

class RoomPipelineStateStore(private val dao: PipelineRunDao) : PipelineStateStore {
  override suspend fun getRun(runId: String): PipelineRunEntity? = dao.getRun(runId)
  override suspend fun upsert(run: PipelineRunEntity) = dao.upsert(run)
}

data class PipelineExecutionResult(
  val run: PipelineRunEntity,
  val draftText: String?,
  val reviewReport: String?,
  val reusedPersistedDraft: Boolean,
)

/**
 * Two-stage resumable AI generation pipeline:
 *   STAGE_DRAFT  -> generates draft and writes to staging disk file + commits checkpoint
 *   STAGE_REVIEW -> validates / reviews the persisted draft
 *
 * When STAGE_REVIEW fails, calling executeOrResume(existingRunId) MUST load the persisted
 * draft from disk and strictly skip STAGE_DRAFT, only re-running STAGE_REVIEW.
 */
class ResumablePipelineExecutor(
  private val stateStore: PipelineStateStore,
  private val layout: BookRepoLayout,
) {
  suspend fun executeOrResume(
    runId: String = "run_${UUID.randomUUID().toString().take(8)}",
    bookId: String,
    chapterId: String,
    taskType: String,
    promptSummary: String,
    onProgress: (stage: PipelineStage, message: String) -> Unit = { _, _ -> },
    generateDraft: suspend () -> String,
    runReview: suspend (draftText: String) -> String,
  ): PipelineExecutionResult {
    layout.ensureDirectories()
    val existing = stateStore.getRun(runId)

    val canReusePersistedDraft = existing != null &&
      !existing.persistedDraftPath.isNullOrBlank() &&
      File(existing.persistedDraftPath).exists() &&
      (existing.status == PipelineStatus.DRAFT_SAVED.name || existing.status == PipelineStatus.REVIEW_FAILED.name)

    var currentRun = existing ?: PipelineRunEntity(
      runId = runId,
      bookId = bookId,
      chapterId = chapterId,
      taskType = taskType,
      stage = PipelineStage.STAGE_DRAFT.name,
      status = PipelineStatus.RUNNING.name,
      promptSummary = promptSummary,
      persistedDraftPath = null,
      reviewReport = null,
      errorMessage = null,
      draftAttempts = 0,
      reviewAttempts = 0,
      updatedAt = System.currentTimeMillis(),
    )

    val draftText: String
    val reusedDraft: Boolean

    if (canReusePersistedDraft) {
      reusedDraft = true
      draftText = File(existing!!.persistedDraftPath!!).readText()
      onProgress(PipelineStage.STAGE_REVIEW, "检测到已落盘初稿，跳过生成并直接复审")
    } else {
      reusedDraft = false
      onProgress(PipelineStage.STAGE_DRAFT, "正在执行初稿生成阶段")
      currentRun = currentRun.copy(
        stage = PipelineStage.STAGE_DRAFT.name,
        status = PipelineStatus.RUNNING.name,
        errorMessage = null,
        draftAttempts = currentRun.draftAttempts + 1,
        updatedAt = System.currentTimeMillis(),
      )
      stateStore.upsert(currentRun)

      draftText = try {
        generateDraft()
      } catch (ex: Exception) {
        val failed = currentRun.copy(
          status = PipelineStatus.FAILED.name,
          errorMessage = ex.message ?: "初稿生成失败",
          updatedAt = System.currentTimeMillis(),
        )
        stateStore.upsert(failed)
        return PipelineExecutionResult(
          run = failed,
          draftText = null,
          reviewReport = null,
          reusedPersistedDraft = false,
        )
      }

      val draftFile = layout.stagingDraftFile(runId)
      draftFile.parentFile?.mkdirs()
      draftFile.writeText(draftText)

      currentRun = currentRun.copy(
        stage = PipelineStage.STAGE_REVIEW.name,
        status = PipelineStatus.DRAFT_SAVED.name,
        persistedDraftPath = draftFile.absolutePath,
        errorMessage = null,
        updatedAt = System.currentTimeMillis(),
      )
      stateStore.upsert(currentRun)
    }

    onProgress(PipelineStage.STAGE_REVIEW, "正在执行自动复审与质检阶段")
    currentRun = currentRun.copy(
      stage = PipelineStage.STAGE_REVIEW.name,
      status = PipelineStatus.RUNNING.name,
      reviewAttempts = currentRun.reviewAttempts + 1,
      errorMessage = null,
      updatedAt = System.currentTimeMillis(),
    )
    stateStore.upsert(currentRun)

    return try {
      val report = runReview(draftText)
      val completed = currentRun.copy(
        stage = PipelineStage.STAGE_COMPLETED.name,
        status = PipelineStatus.COMPLETED.name,
        reviewReport = report,
        errorMessage = null,
        updatedAt = System.currentTimeMillis(),
      )
      stateStore.upsert(completed)
      onProgress(PipelineStage.STAGE_COMPLETED, "初稿与复审阶段均已完成")
      PipelineExecutionResult(
        run = completed,
        draftText = draftText,
        reviewReport = report,
        reusedPersistedDraft = reusedDraft,
      )
    } catch (ex: Exception) {
      val reviewFailed = currentRun.copy(
        stage = PipelineStage.STAGE_REVIEW.name,
        status = PipelineStatus.REVIEW_FAILED.name,
        errorMessage = ex.message ?: "自动复审阶段异常",
        updatedAt = System.currentTimeMillis(),
      )
      stateStore.upsert(reviewFailed)
      PipelineExecutionResult(
        run = reviewFailed,
        draftText = draftText,
        reviewReport = null,
        reusedPersistedDraft = reusedDraft,
      )
    }
  }
}
