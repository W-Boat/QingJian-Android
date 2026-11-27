package com.qingjian.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingjian.core.eta.ContextWindowConfig
import com.qingjian.core.eta.WorldbookEntry
import com.qingjian.core.storage.ChapterDocument
import com.qingjian.core.storage.ChapterIndexEntity
import com.qingjian.core.storage.ChapterStatus
import com.qingjian.core.storage.FeedbackVote
import com.qingjian.core.storage.ForeshadowStage
import com.qingjian.core.storage.ForeshadowThread
import com.qingjian.core.storage.HandoffBrief
import com.qingjian.core.storage.HandoffDirection
import com.qingjian.core.storage.LoreEntry
import com.qingjian.core.storage.PipelineRunEntity
import com.qingjian.core.storage.PipelineStatus
import com.qingjian.core.storage.WritingMode
import com.qingjian.editor.muya.MarkdownStructureGuard
import com.qingjian.editor.muya.MuyaEditorController
import com.qingjian.editor.muya.QingJianMuyaWebView
import com.qingjian.editor.muya.StructureGuardResult

@Composable
fun EditorWorkspaceScreen(
  bookTitle: String,
  writingMode: WritingMode,
  wordCountGoal: Int,
  editorWidthDp: Int,
  isDarkTheme: Boolean,
  accentHex: String,
  isTabletWide: Boolean,
  chapters: List<ChapterIndexEntity>,
  activeChapter: ChapterDocument,
  activeHandoff: HandoffBrief?,
  foreshadows: List<ForeshadowThread>,
  loreEntries: List<LoreEntry>,
  worldbookEntries: List<WorldbookEntry>,
  contextConfig: ContextWindowConfig,
  inlineCandidate: InlineRevisionCandidateState?,
  fullScreenDiff: FullScreenDiffProposal?,
  activePipelineRun: PipelineRunEntity?,
  gitSnapshot: GitWorkspaceSnapshot,
  canUndo: Boolean,
  pendingJumpBlockId: String?,
  onConsumeJumpBlockId: () -> Unit,
  onToggleWritingMode: (WritingMode) -> Unit,
  onSelectChapter: (String) -> Unit,
  onCreateBlankChapter: (volumeTitle: String, title: String) -> Unit,
  onDocumentChanged: (String) -> Unit,
  onUpdateSingleBlockDirect: (blockId: String, newMarkdown: String) -> Unit,
  onSetBlockVote: (blockId: String, vote: FeedbackVote) -> Unit,
  onSetBlockAnnotation: (blockId: String, note: String) -> Unit,
  onAttachForeshadowToBlock: (foreshadowId: String?, newTitle: String, stage: ForeshadowStage, blockId: String, excerpt: String) -> Unit,
  onRequestSingleBlockRewrite: (blockId: String, instruction: String) -> Unit,
  onCycleInlineCandidate: (Int) -> Unit,
  onUpdateInlineCandidateText: (String) -> Unit,
  onAcceptInlineCandidate: () -> Unit,
  onRejectInlineCandidate: () -> Unit,
  onRequestBatchRewrite: (blockIds: List<String>, instruction: String) -> Unit,
  onAcceptFullScreenDiff: (editedText: String) -> Unit,
  onRejectFullScreenDiff: () -> Unit,
  onUndoLastRevision: () -> Unit,
  onTriggerChapterDraftPipeline: (summary: String, simulateReviewFailure: Boolean) -> Unit,
  onResumeFailedPipeline: (runId: String) -> Unit,
  onFinalizeChapterAndGenerateHandoff: () -> Unit,
  onSaveHandoffBrief: (HandoffBrief) -> Unit,
  onCreateNextChapterFromDirection: (direction: HandoffDirection, confirmedSummary: String) -> Unit,
  onGenerateSummaryForDirection: (HandoffDirection, (String) -> Unit) -> Unit,
  onUpdateContextConfig: (ContextWindowConfig) -> Unit,
  onGitCommit: (message: String) -> Unit,
  onGitCreateBranch: (branchName: String, startCommitHash: String?) -> Unit,
  onGitSwitchBranch: (branchName: String) -> Unit,
  onGitInspectCommit: (commitHash: String) -> Unit,
  onGitLoadCommit: (commitHash: String) -> Unit,
  onCreateManualRestorePoint: (label: String) -> Unit,
  onRollbackRestorePoint: (zipPath: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val muyaController = remember { MuyaEditorController() }
  var sourceMode by remember { mutableStateOf(false) }
  var multiSelectMode by remember { mutableStateOf(false) }
  var selectedBlockIds by remember { mutableStateOf<List<String>>(emptyList()) }
  var activeBlockId by remember(activeChapter.chapterId) { mutableStateOf<String?>(null) }

  // Interactive modals & sheets
  var doubleTapRewriteBlockId by remember { mutableStateOf<String?>(null) }
  var longPressEditBlockId by remember { mutableStateOf<String?>(null) }
  var annotateBlockId by remember { mutableStateOf<String?>(null) }
  var foreshadowBlockId by remember { mutableStateOf<String?>(null) }
  var relatedLoreBlockId by remember { mutableStateOf<String?>(null) }
  var showBatchRewriteDialog by remember { mutableStateOf(false) }
  var showChapterListSheet by remember { mutableStateOf(false) }
  var showHandoffSheet by remember { mutableStateOf(false) }
  var showContextSheet by remember { mutableStateOf(false) }
  var showGitSheet by remember { mutableStateOf(false) }
  var showPipelineDialog by remember { mutableStateOf(false) }
  var showNewChapterDialog by remember { mutableStateOf(false) }

  val blocks = remember(activeChapter.markdownContent, activeChapter.chapterId) {
    activeChapter.toBlocks()
  }
  val activeBlock = blocks.firstOrNull { it.id == activeBlockId }
  val foreshadowBlockIds = remember(foreshadows, activeChapter.chapterId) {
    foreshadows.flatMap { it.anchors }
      .filter { it.chapterId == activeChapter.chapterId }
      .map { it.blockId }
      .toSet()
  }
  val feedbackStringMap = remember(activeChapter.blockFeedback) {
    activeChapter.blockFeedback.mapValues { it.value.name }
  }

  LaunchedEffect(pendingJumpBlockId, activeChapter.chapterId) {
    if (!pendingJumpBlockId.isNullOrBlank()) {
      activeBlockId = pendingJumpBlockId
      muyaController.scrollToAndHighlight(pendingJumpBlockId)
      onConsumeJumpBlockId()
    }
  }

  val wordCount = activeChapter.wordCount()
  val wordProgress = (wordCount.toFloat() / wordCountGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

  Row(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Tablet / Foldable (>= 840dp): Persistent left Volume/Chapter tree
    if (isTabletWide) {
      ChapterTreeSidebar(
        bookTitle = bookTitle,
        currentBranch = gitSnapshot.currentBranch,
        chapters = chapters,
        activeChapterId = activeChapter.chapterId,
        onSelectChapter = onSelectChapter,
        onCreateChapter = { showNewChapterDialog = true },
        modifier = Modifier.width(248.dp).fillMaxHeight(),
      )
      VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    // Center Muya Writing Canvas + Inline Review Cards
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
      // Compact Editorial Header
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f),
        ) {
          if (!isTabletWide) {
            IconButton(onClick = { showChapterListSheet = true }) {
              Icon(Icons.Outlined.MenuBook, contentDescription = "卷章目录")
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = activeChapter.title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${activeChapter.volumeTitle} · ${activeChapter.status.label} · $wordCount/$wordCountGoal 字 · 分支 ${gitSnapshot.currentBranch}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Primary Mode Switch & Key Actions
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(
            onClick = {
              val next = if (writingMode == WritingMode.HUMAN_LED) WritingMode.AI_DRIVEN else WritingMode.HUMAN_LED
              onToggleWritingMode(next)
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
          ) {
            Text(writingMode.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          }
          IconButton(onClick = { showContextSheet = true }) {
            Icon(Icons.Outlined.Tune, contentDescription = "上下文装配")
          }
          IconButton(onClick = { showGitSheet = true }) {
            Icon(Icons.Outlined.AccountTree, contentDescription = "Git 分支与还原点")
          }
        }
      }

      LinearProgressIndicator(
        progress = { wordProgress },
        modifier = Modifier.fillMaxWidth().height(2.dp),
        trackColor = MaterialTheme.colorScheme.outlineVariant,
      )

      // Editorial Action Strip (Source toggle, Scene Break, Multi-select, Undo, AI Chapter Draft, Finalize Handoff)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ToolbarActionChip(
          label = if (sourceMode) "预览模式" else "源码模式",
          icon = Icons.Outlined.Code,
          active = sourceMode,
          onClick = { sourceMode = !sourceMode },
        )
        ToolbarActionChip(
          label = "场景线",
          icon = Icons.Outlined.HorizontalRule,
          active = false,
          onClick = { muyaController.insertSceneBreak() },
        )
        ToolbarActionChip(
          label = if (multiSelectMode) "退出多选 (${selectedBlockIds.size})" else "多选改稿",
          icon = Icons.Outlined.CheckBox,
          active = multiSelectMode,
          onClick = {
            multiSelectMode = !multiSelectMode
            if (!multiSelectMode) selectedBlockIds = emptyList()
          },
        )
        if (canUndo) {
          ToolbarActionChip(
            label = "撤销落稿",
            icon = Icons.Outlined.Undo,
            active = false,
            onClick = onUndoLastRevision,
          )
        }
        ToolbarActionChip(
          label = "整章流水线",
          icon = Icons.Outlined.AutoFixHigh,
          active = activePipelineRun?.status == PipelineStatus.REVIEW_FAILED.name,
          onClick = { showPipelineDialog = true },
        )
        ToolbarActionChip(
          label = if (activeHandoff != null) "定稿交接稿" else "定稿并交接",
          icon = Icons.Outlined.CheckCircleOutline,
          active = activeChapter.status == ChapterStatus.FINALIZED,
          onClick = {
            if (activeHandoff == null) {
              onFinalizeChapterAndGenerateHandoff()
            }
            showHandoffSheet = true
          },
        )
      }

      // Resumable Pipeline Failure Recovery Banner (Pillar 1)
      if (activePipelineRun != null && activePipelineRun.status == PipelineStatus.REVIEW_FAILED.name) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                "复审阶段中断 · 初稿已安全落盘",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              Text(
                activePipelineRun.errorMessage ?: "点击从失败处继续，将复用已存初稿，仅重跑复审阶段。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
            Button(
              onClick = { onResumeFailedPipeline(activePipelineRun.runId) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp),
            ) {
              Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("从失败处继续", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }

      // Multi-Select Batch Rewrite Action Bar (Adjacent & Non-adjacent paragraphs)
      if (selectedBlockIds.isNotEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              "已选中 ${selectedBlockIds.size} 个段落（支持不相邻联合改稿）",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              TextButton(onClick = {
                selectedBlockIds = emptyList()
                multiSelectMode = false
              }) { Text("清空") }
              Button(
                onClick = { showBatchRewriteDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
              ) { Text("联合改稿对比") }
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Embedded Mobile Muya WebView Editor Kernel
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        QingJianMuyaWebView(
          chapterId = activeChapter.chapterId,
          markdown = activeChapter.markdownContent,
          activeBlockId = activeBlockId,
          feedbackMap = feedbackStringMap,
          annotationMap = activeChapter.blockAnnotations,
          foreshadowBlockIds = foreshadowBlockIds,
          isDarkTheme = isDarkTheme,
          accentHex = accentHex,
          editorWidthDp = editorWidthDp,
          sourceMode = sourceMode,
          multiSelectMode = multiSelectMode,
          controller = muyaController,
          onBlockSingleTap = { blkId ->
            activeBlockId = if (activeBlockId == blkId) null else blkId
          },
          onBlockDoubleTap = { blkId ->
            activeBlockId = blkId
            doubleTapRewriteBlockId = blkId
          },
          onBlockLongPress = { blkId ->
            activeBlockId = blkId
            longPressEditBlockId = blkId
          },
          onSelectionChanged = { ids ->
            selectedBlockIds = ids
          },
          onDocumentChanged = onDocumentChanged,
          modifier = Modifier.fillMaxSize(),
        )
      }

      // Inline Candidate Card (Pillar 1: shown directly under active paragraph with Structure Guard)
      inlineCandidate?.let { candState ->
        InlineCandidateReviewCard(
          state = candState,
          onCycleCandidate = onCycleInlineCandidate,
          onRegenerate = { onRequestSingleBlockRewrite(candState.blockId, candState.instruction) },
          onEditCandidate = onUpdateInlineCandidateText,
          onAccept = onAcceptInlineCandidate,
          onReject = onRejectInlineCandidate,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }

      // Single-Tap Expanded Paragraph Action Toolbar (Zero Emoji!)
      if (activeBlock != null && inlineCandidate == null) {
        ParagraphActionToolbar(
          block = activeBlock,
          onEdit = { longPressEditBlockId = activeBlock.id },
          onRewritePrompt = { doubleTapRewriteBlockId = activeBlock.id },
          onThumbsUp = {
            val next = if (activeBlock.vote == FeedbackVote.UP) FeedbackVote.NONE else FeedbackVote.UP
            onSetBlockVote(activeBlock.id, next)
          },
          onThumbsDown = {
            val next = if (activeBlock.vote == FeedbackVote.DOWN) FeedbackVote.NONE else FeedbackVote.DOWN
            onSetBlockVote(activeBlock.id, next)
          },
          onAnnotate = { annotateBlockId = activeBlock.id },
          onForeshadow = { foreshadowBlockId = activeBlock.id },
          onRelatedLore = { relatedLoreBlockId = activeBlock.id },
        )
      }
    }
  }

  // --- Dialogs & Full-Screen Sheets ---

  // 1. Double-Tap Quick AI Rewrite Dialog
  doubleTapRewriteBlockId?.let { blkId ->
    val targetBlk = blocks.firstOrNull { it.id == blkId }
    if (targetBlk != null) {
      var instruction by remember(blkId) { mutableStateOf("加强冷峻动作细节，去除套话与总结句") }
      AlertDialog(
        onDismissRequest = { doubleTapRewriteBlockId = null },
        title = { Text("指定段落局部改稿") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
              Text(
                text = targetBlk.markdown,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
              )
            }
            OutlinedTextField(
              value = instruction,
              onValueChange = { instruction = it },
              label = { Text("告诉 AI 这一段怎么改") },
              minLines = 2,
              maxLines = 4,
              modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              listOf("更冷峻克制", "补动作细节", "精简句式").forEach { quick ->
                TextButton(onClick = { instruction = quick }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                  Text(quick, style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
        },
        confirmButton = {
          Button(onClick = {
            onRequestSingleBlockRewrite(blkId, instruction.trim())
            doubleTapRewriteBlockId = null
          }) { Text("生成行内候选") }
        },
        dismissButton = { TextButton(onClick = { doubleTapRewriteBlockId = null }) { Text("取消") } },
      )
    }
  }

  // 2. 650ms Long-Press Full Paragraph Editor Modal (Preserves original text with 1-tap restore)
  longPressEditBlockId?.let { blkId ->
    val targetBlk = blocks.firstOrNull { it.id == blkId }
    if (targetBlk != null) {
      val originalText = remember(blkId) { targetBlk.markdown }
      var draftText by remember(blkId) { mutableStateOf(targetBlk.markdown) }
      AlertDialog(
        onDismissRequest = { longPressEditBlockId = null },
        title = { Text("完整段落编辑（保留原文可一键还原）") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("原文快照", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(originalText, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
              }
            }
            OutlinedTextField(
              value = draftText,
              onValueChange = { draftText = it },
              label = { Text("编辑段落正文") },
              minLines = 5,
              maxLines = 10,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        },
        confirmButton = {
          Button(onClick = {
            onUpdateSingleBlockDirect(blkId, draftText.trim())
            longPressEditBlockId = null
          }) { Text("保存修改") }
        },
        dismissButton = {
          Row {
            TextButton(onClick = { draftText = originalText }) { Text("一键还原原文") }
            TextButton(onClick = { longPressEditBlockId = null }) { Text("取消") }
          }
        },
      )
    }
  }

  // 3. Batch Multi-Select Rewrite Dialog -> opens Full-Screen Diff
  if (showBatchRewriteDialog) {
    var instruction by remember { mutableStateOf("统一这几段的叙述节奏，强化前后线索呼应") }
    AlertDialog(
      onDismissRequest = { showBatchRewriteDialog = false },
      title = { Text("联合改稿 (${selectedBlockIds.size} 个段落)") },
      text = {
        OutlinedTextField(
          value = instruction,
          onValueChange = { instruction = it },
          label = { Text("联合改稿指令") },
          minLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        Button(onClick = {
          onRequestBatchRewrite(selectedBlockIds, instruction.trim())
          showBatchRewriteDialog = false
          multiSelectMode = false
          selectedBlockIds = emptyList()
        }) { Text("生成大屏 Diff 对比") }
      },
      dismissButton = { TextButton(onClick = { showBatchRewriteDialog = false }) { Text("取消") } },
    )
  }

  // 4. Paragraph Annotation Dialog
  annotateBlockId?.let { blkId ->
    var note by remember(blkId) { mutableStateOf(activeChapter.blockAnnotations[blkId].orEmpty()) }
    AlertDialog(
      onDismissRequest = { annotateBlockId = null },
      title = { Text("段落批注") },
      text = {
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text("记录改稿要点、人物动机或事实备注") },
          minLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        Button(onClick = {
          onSetBlockAnnotation(blkId, note.trim())
          annotateBlockId = null
        }) { Text("保存批注") }
      },
      dismissButton = { TextButton(onClick = { annotateBlockId = null }) { Text("取消") } },
    )
  }

  // 5. Attach 5-Stage Foreshadow Anchor Dialog
  foreshadowBlockId?.let { blkId ->
    val blk = blocks.firstOrNull { it.id == blkId }
    var selectedThreadId by remember { mutableStateOf(foreshadows.firstOrNull()?.id) }
    var newTitle by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(ForeshadowStage.PLANT) }

    AlertDialog(
      onDismissRequest = { foreshadowBlockId = null },
      title = { Text("绑定 5 级伏笔锚点") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("锚点类型（选「回收」将自动结案）：", style = MaterialTheme.typography.labelSmall)
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ForeshadowStage.entries.forEach { st ->
              TextButton(onClick = { stage = st }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(st.label, fontWeight = if (stage == st) FontWeight.Bold else FontWeight.Normal)
              }
            }
          }
          if (foreshadows.isNotEmpty()) {
            Text("关联已有伏笔：", style = MaterialTheme.typography.labelSmall)
            foreshadows.take(4).forEach { thread ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { selectedThreadId = thread.id }.padding(vertical = 4.dp),
              ) {
                Text(
                  text = "${if (selectedThreadId == thread.id) "[已选] " else ""}${thread.title} (${if (thread.isResolved) "已回收" else "未回收"})",
                  style = MaterialTheme.typography.bodySmall,
                  color = if (selectedThreadId == thread.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
          OutlinedTextField(
            value = newTitle,
            onValueChange = {
              newTitle = it
              if (it.isNotBlank()) selectedThreadId = null
            },
            label = { Text("或直接新建伏笔标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = {
        Button(onClick = {
          onAttachForeshadowToBlock(
            selectedThreadId,
            newTitle.trim(),
            stage,
            blkId,
            blk?.markdown?.take(42).orEmpty(),
          )
          foreshadowBlockId = null
        }) { Text("绑定锚点") }
      },
      dismissButton = { TextButton(onClick = { foreshadowBlockId = null }) { Text("取消") } },
    )
  }

  // 6. Related Lore Preview Dialog for Active Paragraph
  relatedLoreBlockId?.let { blkId ->
    val blkText = blocks.firstOrNull { it.id == blkId }?.markdown.orEmpty()
    val matchedLore = loreEntries.filter { blkText.contains(it.title) }.ifEmpty { loreEntries.take(3) }
    AlertDialog(
      onDismissRequest = { relatedLoreBlockId = null },
      title = { Text("相关资料预览") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          matchedLore.forEach { lore ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${lore.category.label} · ${lore.title}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(lore.content, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { relatedLoreBlockId = null }) { Text("关闭") } },
    )
  }

  // 7. Two-Stage Resumable Pipeline Dialog (STAGE_DRAFT -> STAGE_REVIEW)
  if (showPipelineDialog) {
    var summaryPrompt by remember { mutableStateOf(activeChapter.summary.ifBlank { "承接白塔雪夜对峙，展开三层暗阁旧档核验" }) }
    AlertDialog(
      onDismissRequest = { showPipelineDialog = false },
      title = { Text("两阶段整章生成流水线（支持断点续跑）") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            "阶段一（STAGE_DRAFT）先生成整章初稿并立即落盘；阶段二（STAGE_REVIEW）执行结构守卫与自动复审。若复审失败，重试时将严格复用已存初稿。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = summaryPrompt,
            onValueChange = { summaryPrompt = it },
            label = { Text("本章大纲 / 摘要指令") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedButton(
            onClick = {
              onTriggerChapterDraftPipeline(summaryPrompt.trim(), true)
              showPipelineDialog = false
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("演练：初稿落盘后模拟复审失败（验证断点续跑）")
          }
        }
      },
      confirmButton = {
        Button(onClick = {
          onTriggerChapterDraftPipeline(summaryPrompt.trim(), false)
          showPipelineDialog = false
        }) { Text("启动生成与复审") }
      },
      dismissButton = { TextButton(onClick = { showPipelineDialog = false }) { Text("取消") } },
    )
  }

  // 8. Phone Chapter Drawer Dialog
  if (showChapterListSheet) {
    AlertDialog(
      onDismissRequest = { showChapterListSheet = false },
      title = { Text("《$bookTitle》卷章目录") },
      text = {
        LazyColumn(modifier = Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          items(chapters, key = { it.chapterId }) { ch ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (ch.chapterId == activeChapter.chapterId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth().clickable {
                onSelectChapter(ch.chapterId)
                showChapterListSheet = false
              },
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text(ch.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                  "${ch.volumeTitle} · ${if (ch.status == ChapterStatus.FINALIZED.name) "已定稿" else "草稿"} · ${ch.wordCount} 字${if (ch.hasHandoff) " · 已交接" else ""}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      },
      confirmButton = {
        Button(onClick = {
          showChapterListSheet = false
          showNewChapterDialog = true
        }) { Text("新建章节") }
      },
      dismissButton = { TextButton(onClick = { showChapterListSheet = false }) { Text("关闭") } },
    )
  }

  if (showNewChapterDialog) {
    var vol by remember { mutableStateOf(activeChapter.volumeTitle.ifBlank { "第一卷 雪夜白塔" }) }
    var chTitle by remember { mutableStateOf("第${chapters.size + 1}章 ") }
    AlertDialog(
      onDismissRequest = { showNewChapterDialog = false },
      title = { Text("新建空白章节") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = vol, onValueChange = { vol = it }, label = { Text("所属卷名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = chTitle, onValueChange = { chTitle = it }, label = { Text("章节标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        Button(onClick = {
          if (chTitle.isNotBlank()) {
            onCreateBlankChapter(vol.trim(), chTitle.trim())
            showNewChapterDialog = false
          }
        }) { Text("创建章节") }
      },
      dismissButton = { TextButton(onClick = { showNewChapterDialog = false }) { Text("取消") } },
    )
  }

  // 9. Full-Screen Diff Modal (Batch rewrite / Chapter summary / Full chapter draft)
  fullScreenDiff?.let { proposal ->
    FullScreenDiffDialog(
      proposal = proposal,
      onAccept = onAcceptFullScreenDiff,
      onReject = onRejectFullScreenDiff,
    )
  }

  // 10. Handoff Brief & Next-Chapter Direction Wizard Sheet
  if (showHandoffSheet) {
    HandoffBriefDialog(
      chapterTitle = activeChapter.title,
      brief = activeHandoff,
      onGenerateOrBackfill = onFinalizeChapterAndGenerateHandoff,
      onSaveBrief = onSaveHandoffBrief,
      onGenerateSummaryForDirection = onGenerateSummaryForDirection,
      onCreateNextChapter = { dir, confirmedSummary ->
        onCreateNextChapterFromDirection(dir, confirmedSummary)
        showHandoffSheet = false
      },
      onDismiss = { showHandoffSheet = false },
    )
  }

  // 11. Context Assembly Drawer Sheet
  if (showContextSheet) {
    ContextAssemblyDialog(
      config = contextConfig,
      worldbookEntries = worldbookEntries,
      sampleText = activeChapter.markdownContent,
      onSaveConfig = {
        onUpdateContextConfig(it)
        showContextSheet = false
      },
      onDismiss = { showContextSheet = false },
    )
  }

  // 12. Git Branches, File-by-File Diff & Safety Restore Point Sheet
  if (showGitSheet) {
    GitVersionControlDialog(
      snapshot = gitSnapshot,
      onCommit = onGitCommit,
      onCreateBranch = onGitCreateBranch,
      onSwitchBranch = onGitSwitchBranch,
      onInspectCommit = onGitInspectCommit,
      onLoadCommit = onGitLoadCommit,
      onCreateRestorePoint = onCreateManualRestorePoint,
      onRollbackRestorePoint = onRollbackRestorePoint,
      onDismiss = { showGitSheet = false },
    )
  }
}

@Composable
private fun ChapterTreeSidebar(
  bookTitle: String,
  currentBranch: String,
  chapters: List<ChapterIndexEntity>,
  activeChapterId: String,
  onSelectChapter: (String) -> Unit,
  onCreateChapter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(bookTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("分支 $currentBranch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
      }
      IconButton(onClick = onCreateChapter, modifier = Modifier.size(30.dp)) {
        Icon(Icons.Outlined.Add, contentDescription = "新建章节", modifier = Modifier.size(18.dp))
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      items(chapters, key = { it.chapterId }) { ch ->
        val selected = ch.chapterId == activeChapterId
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
          border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          ),
          modifier = Modifier.fillMaxWidth().clickable { onSelectChapter(ch.chapterId) },
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ch.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
              "${if (ch.status == ChapterStatus.FINALIZED.name) "已定稿" else "草稿"} · ${ch.wordCount} 字${if (ch.hasHandoff) " · 已交接" else ""}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ToolbarActionChip(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  active: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    border = BorderStroke(
      1.dp,
      if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ),
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(15.dp),
        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun ParagraphActionToolbar(
  block: com.qingjian.core.storage.ParagraphBlock,
  onEdit: () -> Unit,
  onRewritePrompt: () -> Unit,
  onThumbsUp: () -> Unit,
  onThumbsDown: () -> Unit,
  onAnnotate: () -> Unit,
  onForeshadow: () -> Unit,
  onRelatedLore: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ParagraphBarButton("编辑", Icons.Outlined.Edit, false, onEdit)
      ParagraphBarButton("改稿", Icons.Outlined.AutoFixHigh, false, onRewritePrompt)
      ParagraphBarButton("赞", Icons.Outlined.ThumbUp, block.vote == FeedbackVote.UP, onThumbsUp)
      ParagraphBarButton("踩", Icons.Outlined.ThumbDown, block.vote == FeedbackVote.DOWN, onThumbsDown)
      ParagraphBarButton("批注", Icons.Outlined.Comment, block.annotation.isNotBlank(), onAnnotate)
      ParagraphBarButton("伏笔", Icons.Outlined.BookmarkAdd, false, onForeshadow)
      ParagraphBarButton("相关资料", Icons.Outlined.MenuBook, false, onRelatedLore)
    }
  }
}

@Composable
private fun ParagraphBarButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  highlighted: Boolean,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      modifier = Modifier.size(16.dp),
      tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.width(4.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
    )
  }
}

@Composable
private fun InlineCandidateReviewCard(
  state: InlineRevisionCandidateState,
  onCycleCandidate: (Int) -> Unit,
  onRegenerate: () -> Unit,
  onEditCandidate: (String) -> Unit,
  onAccept: () -> Unit,
  onReject: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val guardResult = remember(state.originalMarkdown, state.activeCandidate) {
    MarkdownStructureGuard.validate(state.originalMarkdown, state.activeCandidate)
  }
  var manualEditMode by remember(state.blockId, state.activeIndex) { mutableStateOf(false) }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "行内改稿候选 · 版本 ${state.activeIndex + 1}/${state.candidates.size}",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            enabled = state.candidates.size > 1,
            onClick = { onCycleCandidate((state.activeIndex - 1 + state.candidates.size) % state.candidates.size) },
            modifier = Modifier.size(28.dp),
          ) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一候选", modifier = Modifier.size(18.dp))
          }
          IconButton(
            enabled = state.candidates.size > 1,
            onClick = { onCycleCandidate((state.activeIndex + 1) % state.candidates.size) },
            modifier = Modifier.size(28.dp),
          ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下一候选", modifier = Modifier.size(18.dp))
          }
          IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = "重新生成新候选", modifier = Modifier.size(16.dp))
          }
        }
      }

      if (manualEditMode) {
        OutlinedTextField(
          value = state.activeCandidate,
          onValueChange = onEditCandidate,
          label = { Text("手动调整候选正文") },
          minLines = 3,
          maxLines = 6,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
          Text(
            text = state.activeCandidate,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      // Markdown Structure Guard feedback banner
      when (guardResult) {
        is StructureGuardResult.NoTextChange -> {
          Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
              text = "结构守卫提示：${guardResult.message}",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(8.dp),
            )
          }
        }
        is StructureGuardResult.StructureBroken -> {
          Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                "结构守卫拦截（禁止直接落稿，请重新生成或手动修复）：",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              guardResult.issues.forEach { issue ->
                Text("- $issue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
              }
            }
          }
        }
        StructureGuardResult.Valid -> Unit
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = { manualEditMode = !manualEditMode }) {
          Text(if (manualEditMode) "完成手改" else "手改候选")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onReject, shape = RoundedCornerShape(8.dp)) {
            Text("拒绝")
          }
          Button(
            enabled = guardResult !is StructureGuardResult.StructureBroken && !state.isGenerating,
            onClick = onAccept,
            shape = RoundedCornerShape(8.dp),
          ) {
            Text("接受落稿")
          }
        }
      }
    }
  }
}
