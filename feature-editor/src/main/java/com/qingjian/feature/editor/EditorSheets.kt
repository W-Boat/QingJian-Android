package com.qingjian.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qingjian.core.eta.ContextWindowConfig
import com.qingjian.core.eta.WorldbookEngine
import com.qingjian.core.eta.WorldbookEntry
import com.qingjian.core.storage.HandoffBrief
import com.qingjian.core.storage.HandoffDirection
import com.qingjian.editor.muya.DiffOp
import com.qingjian.editor.muya.MarkdownStructureGuard
import com.qingjian.editor.muya.StructureGuardResult
import com.qingjian.editor.muya.TextDiffEngine

/**
 * Full-Screen Diff Dialog for Batch Rewrites, Chapter Summaries, and Full Chapter Drafts.
 */
@Composable
fun FullScreenDiffDialog(
  proposal: FullScreenDiffProposal,
  onAccept: (String) -> Unit,
  onReject: () -> Unit,
) {
  var editableProposed by remember(proposal) { mutableStateOf(proposal.proposedText) }
  var editMode by remember { mutableStateOf(false) }

  val diffLines = remember(proposal.originalText, editableProposed) {
    TextDiffEngine.diffLines(proposal.originalText, editableProposed)
  }
  val guardResult = remember(proposal.originalText, editableProposed, proposal.kind) {
    if (proposal.kind == FullScreenDiffKind.BATCH_REWRITE) {
      MarkdownStructureGuard.validate(proposal.originalText, editableProposed)
    } else {
      StructureGuardResult.Valid
    }
  }

  Dialog(
    onDismissRequest = onReject,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(proposal.kind.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(proposal.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editMode = !editMode }) {
              Text(if (editMode) "返回 Diff 视图" else "手动调整稿件")
            }
            OutlinedButton(onClick = onReject, shape = RoundedCornerShape(8.dp)) { Text("拒绝") }
            Button(
              enabled = guardResult !is StructureGuardResult.StructureBroken,
              onClick = { onAccept(editableProposed) },
              shape = RoundedCornerShape(8.dp),
            ) { Text("确认落稿") }
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        proposal.reviewReport?.let { report ->
          Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(8.dp)) {
            Text(
              text = "自动复审报告：$report",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(10.dp),
            )
          }
        }

        if (guardResult is StructureGuardResult.StructureBroken) {
          Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
              Text("结构守卫拦截：请先手动修复以下问题后再落稿", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
              guardResult.issues.forEach { Text("- $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            }
          }
        }

        if (editMode) {
          OutlinedTextField(
            value = editableProposed,
            onValueChange = { editableProposed = it },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
          )
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(diffLines) { line ->
              val bg = when (line.op) {
                DiffOp.INSERT -> Color(0x2216A34A)
                DiffOp.DELETE -> Color(0x22DC2626)
                DiffOp.EQUAL -> Color.Transparent
              }
              val prefix = when (line.op) {
                DiffOp.INSERT -> "+ "
                DiffOp.DELETE -> "- "
                DiffOp.EQUAL -> "  "
              }
              Text(
                text = prefix + line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                  .fillMaxWidth()
                  .background(bg, RoundedCornerShape(4.dp))
                  .padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Pillar 2: Editable Handoff Brief + Create Next Chapter (Summary First -> Author Confirm -> Draft).
 */
@Composable
fun HandoffBriefDialog(
  chapterTitle: String,
  brief: HandoffBrief?,
  onGenerateOrBackfill: () -> Unit,
  onSaveBrief: (HandoffBrief) -> Unit,
  onGenerateSummaryForDirection: (HandoffDirection, (String) -> Unit) -> Unit,
  onCreateNextChapter: (HandoffDirection, String) -> Unit,
  onDismiss: () -> Unit,
) {
  var factsText by remember(brief) { mutableStateOf(brief?.happenedFacts?.joinToString("\n").orEmpty()) }
  var lessonsText by remember(brief) { mutableStateOf(brief?.writingLessons?.joinToString("\n").orEmpty()) }
  var hooksText by remember(brief) { mutableStateOf(brief?.nextChapterHooks?.joinToString("\n").orEmpty()) }
  var selectedDirForNext by remember { mutableStateOf<HandoffDirection?>(null) }
  var editableNextSummary by remember { mutableStateOf("") }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("定稿交接稿 · $chapterTitle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("将已发生事实、写作经验与铺垫结构化接力至下一章", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGenerateOrBackfill, shape = RoundedCornerShape(8.dp)) {
              Text(if (brief == null) "生成交接稿" else "重新补齐交接")
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (brief == null) {
          Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text("本章尚未生成定稿交接稿", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onGenerateOrBackfill) { Text("立即调用 Subagent 生成交接稿") }
          }
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item {
              OutlinedTextField(
                value = factsText,
                onValueChange = { factsText = it },
                label = { Text("1. 本章已发生事实（谁做了什么、揭示了何信息、谁在场知情）") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            item {
              OutlinedTextField(
                value = lessonsText,
                onValueChange = { lessonsText = it },
                label = { Text("2. 写作经验（结合本章赞/踩与历史经验，明确沿用或避免的写法）") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            item {
              OutlinedTextField(
                value = hooksText,
                onValueChange = { hooksText = it },
                label = { Text("3. 下一章必须承接的具体铺垫与开场疑问") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            item {
              Button(
                onClick = {
                  onSaveBrief(
                    brief.copy(
                      happenedFacts = factsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                      writingLessons = lessonsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                      nextChapterHooks = hooksText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                      updatedAt = System.currentTimeMillis(),
                    )
                  )
                },
                shape = RoundedCornerShape(8.dp),
              ) { Text("保存交接稿修改") }
            }

            item {
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              Spacer(Modifier.height(4.dp))
              Text("4. 备选后续走向（点选后先拟章节摘要，确认再写整章）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            items(brief.nextDirections, key = { it.id }) { dir ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                  1.dp,
                  if (selectedDirForNext?.id == dir.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(dir.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                  Text(dir.premise, style = MaterialTheme.typography.bodySmall)
                  Text("戏剧张力：${dir.tension}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  OutlinedButton(
                    onClick = {
                      selectedDirForNext = dir
                      onGenerateSummaryForDirection(dir) { generatedSummary ->
                        editableNextSummary = generatedSummary
                      }
                    },
                    shape = RoundedCornerShape(8.dp),
                  ) { Text("选此走向拟定下章摘要") }
                }
              }
            }

            selectedDirForNext?.let { chosen ->
              item {
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = MaterialTheme.colorScheme.surface,
                  border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("审阅并修改下章摘要 · ${chosen.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                      value = editableNextSummary,
                      onValueChange = { editableNextSummary = it },
                      label = { Text("确认后的章节摘要（确认后立即新建章节并启动整章生成）") },
                      minLines = 5,
                      modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                      enabled = editableNextSummary.isNotBlank(),
                      onClick = { onCreateNextChapter(chosen, editableNextSummary.trim()) },
                      shape = RoundedCornerShape(8.dp),
                    ) { Text("确认摘要并新建下一章初稿") }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Pillar 4: Transparent Context Assembly Drawer.
 */
@Composable
fun ContextAssemblyDialog(
  config: ContextWindowConfig,
  worldbookEntries: List<WorldbookEntry>,
  sampleText: String,
  onSaveConfig: (ContextWindowConfig) -> Unit,
  onDismiss: () -> Unit,
) {
  var beforeCount by remember(config) { mutableIntStateOf(config.neighborParagraphsBefore) }
  var afterCount by remember(config) { mutableIntStateOf(config.neighborParagraphsAfter) }
  var includeOutline by remember(config) { mutableStateOf(config.includeOutline) }
  var includeHandoff by remember(config) { mutableStateOf(config.includeFinalizedHandoff) }
  var includeFeedback by remember(config) { mutableStateOf(config.includeAuthorFeedback) }
  var wbBudget by remember(config) { mutableIntStateOf(config.worldbookCharBudget) }

  val hitPreview = remember(worldbookEntries, sampleText, wbBudget) {
    WorldbookEngine.evaluate(worldbookEntries, sampleText, charBudget = wbBudget)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("透明化上下文装配控制") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("前邻段带入数量：$beforeCount 段", style = MaterialTheme.typography.bodySmall)
        Slider(value = beforeCount.toFloat(), onValueChange = { beforeCount = it.toInt() }, valueRange = 0f..10f)
        Text("后邻段带入数量：$afterCount 段", style = MaterialTheme.typography.bodySmall)
        Slider(value = afterCount.toFloat(), onValueChange = { afterCount = it.toInt() }, valueRange = 0f..8f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text("纳入卷章大纲", style = MaterialTheme.typography.bodySmall)
          Switch(checked = includeOutline, onCheckedChange = { includeOutline = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text("纳入上章定稿交接记忆", style = MaterialTheme.typography.bodySmall)
          Switch(checked = includeHandoff, onCheckedChange = { includeHandoff = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
          Text("纳入作者赞/踩写作反馈", style = MaterialTheme.typography.bodySmall)
          Switch(checked = includeFeedback, onCheckedChange = { includeFeedback = it })
        }
        Text("世界书预算：$wbBudget 字（当前章命中 ${hitPreview.matchedTraces.count { it.includedInBudget }} 条）", style = MaterialTheme.typography.bodySmall)
        Slider(value = wbBudget.toFloat(), onValueChange = { wbBudget = (it.toInt() / 200) * 200 }, valueRange = 600f..5000f)
        if (hitPreview.matchedTraces.isNotEmpty()) {
          Text(
            "当前命中：" + hitPreview.matchedTraces.filter { it.includedInBudget }.joinToString(" · ") { it.entry.title },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        onSaveConfig(
          config.copy(
            neighborParagraphsBefore = beforeCount,
            neighborParagraphsAfter = afterCount,
            includeOutline = includeOutline,
            includeFinalizedHandoff = includeHandoff,
            includeAuthorFeedback = includeFeedback,
            worldbookCharBudget = wbBudget,
          )
        )
      }) { Text("应用上下文策略") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

/**
 * Pillar 6: Full Git Version Control, Multi-Route Branching, File-by-File Diff & Mandatory Safety Restore Points.
 */
@Composable
fun GitVersionControlDialog(
  snapshot: GitWorkspaceSnapshot,
  onCommit: (String) -> Unit,
  onCreateBranch: (String, String?) -> Unit,
  onSwitchBranch: (String) -> Unit,
  onInspectCommit: (String) -> Unit,
  onLoadCommit: (String) -> Unit,
  onCreateRestorePoint: (String) -> Unit,
  onRollbackRestorePoint: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var commitMsg by remember { mutableStateOf("") }
  var newBranchName by remember { mutableStateOf("") }
  var branchSourceHash by remember { mutableStateOf<String?>(null) }
  var tab by remember { mutableIntStateOf(0) } // 0=分支与提交, 1=逐文件Diff, 2=安全还原点

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Git 版本控制与多路线分支", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              "当前路线：${snapshot.currentBranch} · 切换分支或载入旧提交前强制自动建还原点",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          TextButton(onClick = onDismiss) { Text("关闭") }
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf("分支与提交 (${snapshot.commits.size})", "逐文件对比 (${snapshot.fileDiffs.size})", "安全还原点 (${snapshot.restorePoints.size})")
            .forEachIndexed { idx, label ->
              OutlinedButton(
                onClick = { tab = idx },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                  1.dp,
                  if (tab == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
              ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
              }
            }
        }

        snapshot.lastSafetyNotice?.let { notice ->
          Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(8.dp)) {
            Text(notice, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (tab) {
          0 -> LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                  value = commitMsg,
                  onValueChange = { commitMsg = it },
                  label = { Text("提交说明 (Commit Message)") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                Button(
                  onClick = {
                    onCommit(commitMsg.trim().ifBlank { "手动提交书稿变更" })
                    commitMsg = ""
                  },
                  shape = RoundedCornerShape(8.dp),
                ) { Text("提交") }
              }
            }

            item {
              Text("路线分支（点击切换将自动创建防丢还原点）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                  value = newBranchName,
                  onValueChange = { newBranchName = it },
                  label = { Text(if (branchSourceHash == null) "从当前提交新建路线分支" else "从 ${branchSourceHash!!.take(7)} 新建分支") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                Button(
                  enabled = newBranchName.isNotBlank(),
                  onClick = {
                    onCreateBranch(newBranchName.trim(), branchSourceHash)
                    newBranchName = ""
                    branchSourceHash = null
                  },
                  shape = RoundedCornerShape(8.dp),
                ) { Text("建分支") }
              }
            }

            items(snapshot.branches, key = { it.name }) { br ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (br.isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column {
                    Text(br.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("HEAD ${br.headCommitHash.take(7)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                  }
                  if (br.isCurrent) {
                    Text("当前路线", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  } else {
                    OutlinedButton(onClick = { onSwitchBranch(br.name) }, shape = RoundedCornerShape(8.dp)) {
                      Text("安全切换")
                    }
                  }
                }
              }
            }

            item {
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              Spacer(Modifier.height(4.dp))
              Text("提交历史树", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            items(snapshot.commits, key = { it.hash }) { commit ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                  1.dp,
                  if (snapshot.selectedCommitHash == commit.hash) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(commit.message, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(commit.shortHash, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                  }
                  if (commit.branchRefs.isNotEmpty()) {
                    Text("分支指向：${commit.branchRefs.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                      onClick = {
                        onInspectCommit(commit.hash)
                        tab = 1
                      },
                      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                      shape = RoundedCornerShape(8.dp),
                    ) { Text("逐文件 Diff", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                      onClick = { branchSourceHash = commit.hash },
                      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                      shape = RoundedCornerShape(8.dp),
                    ) { Text("从此建分支", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { onLoadCommit(commit.hash) }) {
                      Text("安全载入此稿", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                }
              }
            }
          }

          1 -> LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (snapshot.fileDiffs.isEmpty()) {
              item {
                Text("请在「分支与提交」中选择任意一条提交点击「逐文件 Diff」查看所有 .md / .json 文件的增删差异。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            } else {
              items(snapshot.fileDiffs, key = { it.path }) { diff ->
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = MaterialTheme.colorScheme.surface,
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                      Text(diff.path, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                      Text("+${diff.addedLines} / -${diff.deletedLines}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                      text = diff.unifiedPatch.take(1800),
                      style = MaterialTheme.typography.bodySmall,
                      fontFamily = FontFamily.Monospace,
                    )
                  }
                }
              }
            }
          }

          2 -> LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item {
              Button(
                onClick = { onCreateRestorePoint("手动全书安全还原点") },
                shape = RoundedCornerShape(8.dp),
              ) { Text("立即创建全书还原点") }
            }
            items(snapshot.restorePoints, key = { it.id }) { rp ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(rp.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("${rp.reason} · 分支 ${rp.branchName} · ${rp.commitHash.take(7)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  OutlinedButton(onClick = { onRollbackRestorePoint(rp.snapshotZipPath) }, shape = RoundedCornerShape(8.dp)) {
                    Text("还原")
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
