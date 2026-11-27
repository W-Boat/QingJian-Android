package com.qingjian.feature.lore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingjian.core.eta.SecondaryKeyLogic
import com.qingjian.core.eta.WorldbookEngine
import com.qingjian.core.eta.WorldbookEntry
import com.qingjian.core.storage.CausalGraphData
import com.qingjian.core.storage.ForeshadowAnchor
import com.qingjian.core.storage.ForeshadowStage
import com.qingjian.core.storage.ForeshadowThread
import com.qingjian.core.storage.HandoffBrief
import com.qingjian.core.storage.LoreCategory
import com.qingjian.core.storage.LoreEntry
import com.qingjian.core.storage.LoreExtractionCandidate
import com.qingjian.core.storage.PlotPlanIdea
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val WorkbenchTabs = listOf("定稿与提取", "伏笔档案", "剧情因果图", "剧情规划", "高级世界书")

@Composable
fun PlotWorkbenchScreen(
  bookTitle: String,
  handoffs: List<HandoffBrief>,
  loreEntries: List<LoreEntry>,
  loreCandidates: List<LoreExtractionCandidate>,
  foreshadows: List<ForeshadowThread>,
  causalGraph: CausalGraphData,
  plotPlans: List<PlotPlanIdea>,
  worldbookEntries: List<WorldbookEntry>,
  worldbookCharBudget: Int,
  activeChapterId: String,
  activeChapterTitle: String,
  activeChapterSampleText: String,
  onExtractLoreCandidates: () -> Unit,
  onApproveLoreCandidate: (String) -> Unit,
  onRejectLoreCandidate: (String) -> Unit,
  onJumpToAnchor: (chapterId: String, blockId: String) -> Unit,
  onCreateForeshadow: (title: String, desc: String, stage: ForeshadowStage, excerpt: String) -> Unit,
  onAddForeshadowAnchor: (foreshadowId: String, stage: ForeshadowStage, excerpt: String, note: String) -> Unit,
  onConfirmCausalEdge: (edgeId: String) -> Unit,
  onRejectCausalEdge: (edgeId: String) -> Unit,
  onAddPlotPlan: (title: String, premise: String, beats: List<String>) -> Unit,
  onCreateNextChapterFromPlan: (PlotPlanIdea) -> Unit,
  onUpsertWorldbookEntry: (WorldbookEntry) -> Unit,
  onToggleWorldbookEntry: (String, Boolean) -> Unit,
  onUpdateWorldbookBudget: (Int) -> Unit,
  onImportTavernWorldbookJson: (String) -> Result<Int>,
  modifier: Modifier = Modifier,
) {
  var selectedTab by remember { mutableIntStateOf(0) }

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text("剧情工作台 · 《$bookTitle》", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        "严格隔离「已发生定稿事实」与「待定剧情规划」 · 伏笔与资料均锚定原文段落",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    ScrollableTabRow(
      selectedTabIndex = selectedTab,
      edgePadding = 12.dp,
      containerColor = MaterialTheme.colorScheme.background,
    ) {
      WorkbenchTabs.forEachIndexed { idx, label ->
        Tab(
          selected = selectedTab == idx,
          onClick = { selectedTab = idx },
          text = { Text(label, maxLines = 1) },
        )
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    when (selectedTab) {
      0 -> ConfirmedFactsAndExtractionTab(
        handoffs = handoffs,
        loreEntries = loreEntries,
        candidates = loreCandidates,
        onExtractCandidates = onExtractLoreCandidates,
        onApproveCandidate = onApproveLoreCandidate,
        onRejectCandidate = onRejectLoreCandidate,
        onJumpToAnchor = onJumpToAnchor,
      )
      1 -> ForeshadowArchiveTab(
        foreshadows = foreshadows,
        activeChapterTitle = activeChapterTitle,
        onJumpToAnchor = onJumpToAnchor,
        onCreateForeshadow = onCreateForeshadow,
        onAddAnchor = onAddForeshadowAnchor,
      )
      2 -> CausalGraphCanvasTab(
        graph = causalGraph,
        onConfirmEdge = onConfirmCausalEdge,
        onRejectEdge = onRejectCausalEdge,
      )
      3 -> PlotPlanningTab(
        plans = plotPlans,
        onAddPlan = onAddPlotPlan,
        onCreateNextChapterFromPlan = onCreateNextChapterFromPlan,
      )
      4 -> AdvancedWorldbookTab(
        entries = worldbookEntries,
        charBudget = worldbookCharBudget,
        defaultTestCorpus = activeChapterSampleText,
        onUpsertEntry = onUpsertWorldbookEntry,
        onToggleEntry = onToggleWorldbookEntry,
        onUpdateBudget = onUpdateWorldbookBudget,
        onImportJson = onImportTavernWorldbookJson,
      )
    }
  }
}

@Composable
private fun ConfirmedFactsAndExtractionTab(
  handoffs: List<HandoffBrief>,
  loreEntries: List<LoreEntry>,
  candidates: List<LoreExtractionCandidate>,
  onExtractCandidates: () -> Unit,
  onApproveCandidate: (String) -> Unit,
  onRejectCandidate: (String) -> Unit,
  onJumpToAnchor: (String, String) -> Unit,
) {
  var categoryFilter by remember { mutableStateOf<LoreCategory?>(null) }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("AI 待审资料提取候选 (${candidates.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Button(
          onClick = onExtractCandidates,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("扫描当前章提取")
        }
      }
    }

    if (candidates.isEmpty()) {
      item {
        Text(
          "当前没有待审阅的资料候选。每条 AI 提取的人物、世界规则、场景、事件或批注都必须附带原文摘录与段落锚点，经作者确认后才写入 lore/。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      items(candidates, key = { it.id }) { cand ->
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              "${cand.category.label} · ${cand.title}",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Text(cand.summary, style = MaterialTheme.typography.bodySmall)
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "原文摘录（${cand.chapterTitle}）：「${cand.verbatimExcerpt}」",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = { onApproveCandidate(cand.id) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
              ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("确认入库")
              }
              OutlinedButton(
                onClick = { onRejectCandidate(cand.id) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
              ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("驳回")
              }
              TextButton(onClick = { onJumpToAnchor(cand.chapterId, cand.blockId) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("回跳原文")
              }
            }
          }
        }
      }
    }

    item {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(4.dp))
      Text("已定稿事实档案 (${handoffs.size} 章已交接)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }

    items(handoffs, key = { it.chapterId }) { brief ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(brief.chapterTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          brief.happenedFacts.forEach { fact ->
            Text("- $fact", style = MaterialTheme.typography.bodySmall)
          }
          if (brief.nextChapterHooks.isNotEmpty()) {
            Text(
              "待承接铺垫：${brief.nextChapterHooks.joinToString(" / ")}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }

    item {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("已入库资料 (${loreEntries.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = { categoryFilter = null }) {
            Text("全部", fontWeight = if (categoryFilter == null) FontWeight.Bold else FontWeight.Normal)
          }
          LoreCategory.entries.take(3).forEach { cat ->
            TextButton(onClick = { categoryFilter = cat }) {
              Text(cat.label, fontWeight = if (categoryFilter == cat) FontWeight.Bold else FontWeight.Normal)
            }
          }
        }
      }
    }

    val filteredLore = if (categoryFilter == null) loreEntries else loreEntries.filter { it.category == categoryFilter }
    items(filteredLore, key = { it.id }) { item ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("${item.category.label} · ${item.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (item.sourceChapterId.isNotBlank() && item.sourceBlockId.isNotBlank()) {
              TextButton(onClick = { onJumpToAnchor(item.sourceChapterId, item.sourceBlockId) }) {
                Text("原文锚点", style = MaterialTheme.typography.labelSmall)
              }
            }
          }
          Text(item.content, style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

@Composable
private fun ForeshadowArchiveTab(
  foreshadows: List<ForeshadowThread>,
  activeChapterTitle: String,
  onJumpToAnchor: (String, String) -> Unit,
  onCreateForeshadow: (String, String, ForeshadowStage, String) -> Unit,
  onAddAnchor: (String, ForeshadowStage, String, String) -> Unit,
) {
  var filterMode by remember { mutableIntStateOf(0) } // 0=全部, 1=未回收, 2=已回收
  var showCreateDialog by remember { mutableStateOf(false) }
  var anchorTargetThread by remember { mutableStateOf<ForeshadowThread?>(null) }

  val filtered = when (filterMode) {
    1 -> foreshadows.filter { !it.isResolved }
    2 -> foreshadows.filter { it.isResolved }
    else -> foreshadows
  }
  val unresolvedCount = foreshadows.count { !it.isResolved }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("全部 (${foreshadows.size})", "未回收 ($unresolvedCount)", "已回收 (${foreshadows.size - unresolvedCount})")
            .forEachIndexed { idx, label ->
              OutlinedButton(
                onClick = { filterMode = idx },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                  1.dp,
                  if (filterMode == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
              ) {
                Text(
                  label,
                  style = MaterialTheme.typography.labelSmall,
                  color = if (filterMode == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
            }
        }
        Button(
          onClick = { showCreateDialog = true },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(Modifier.width(4.dp))
          Text("新建伏笔")
        }
      }
    }

    items(filtered, key = { it.id }) { thread ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(thread.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              text = if (thread.isResolved) "已回收" else "未回收",
              style = MaterialTheme.typography.labelSmall,
              color = if (thread.isResolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
              fontWeight = FontWeight.SemiBold,
            )
          }
          Text(thread.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

          thread.anchors.forEach { anchor ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onJumpToAnchor(anchor.chapterId, anchor.blockId) },
            ) {
              Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                  text = "${anchor.stage.label} · ${anchor.chapterTitle} · 点击跳回高亮",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Medium,
                )
                Text("「${anchor.excerpt}」", style = MaterialTheme.typography.bodySmall)
                if (anchor.note.isNotBlank()) {
                  Text(anchor.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }

          Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
              onClick = { anchorTargetThread = thread },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              shape = RoundedCornerShape(8.dp),
            ) {
              Text("在「$activeChapterTitle」追加锚点（添加回收即自动结案）", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }

  if (showCreateDialog) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var excerpt by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(ForeshadowStage.PLANT) }

    AlertDialog(
      onDismissRequest = { showCreateDialog = false },
      title = { Text("新建跨章伏笔档案") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("伏笔名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("意图与关联线索") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = excerpt, onValueChange = { excerpt = it }, label = { Text("当前章原文摘录") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
          Text("初始锚点阶段", style = MaterialTheme.typography.labelSmall)
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ForeshadowStage.entries.forEach { st ->
              TextButton(onClick = { stage = st }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(st.label, fontWeight = if (stage == st) FontWeight.Bold else FontWeight.Normal)
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (title.isNotBlank()) {
              onCreateForeshadow(title.trim(), desc.trim(), stage, excerpt.trim().ifBlank { "当前活动段落" })
              showCreateDialog = false
            }
          },
        ) { Text("建立档案") }
      },
      dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
    )
  }

  anchorTargetThread?.let { thread ->
    var stage by remember { mutableStateOf(ForeshadowStage.ECHO) }
    var excerpt by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { anchorTargetThread = null },
      title = { Text("为「${thread.title}」追加锚点") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("选择 5 级锚点类型（选择「回收」将自动把伏笔置为已回收）：", style = MaterialTheme.typography.bodySmall)
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ForeshadowStage.entries.forEach { st ->
              TextButton(onClick = { stage = st }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(
                  st.label,
                  fontWeight = if (stage == st) FontWeight.Bold else FontWeight.Normal,
                  color = if (stage == st) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
          OutlinedTextField(value = excerpt, onValueChange = { excerpt = it }, label = { Text("原文摘录") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注说明") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onAddAnchor(thread.id, stage, excerpt.trim().ifBlank { "正文对应段落" }, note.trim())
            anchorTargetThread = null
          },
        ) { Text("写入锚点") }
      },
      dismissButton = { TextButton(onClick = { anchorTargetThread = null }) { Text("取消") } },
    )
  }
}

@Composable
private fun CausalGraphCanvasTab(
  graph: CausalGraphData,
  onConfirmEdge: (String) -> Unit,
  onRejectEdge: (String) -> Unit,
) {
  var panOffset by remember { mutableStateOf(Offset.Zero) }
  var zoom by remember { mutableFloatStateOf(1f) }
  val primaryColor = MaterialTheme.colorScheme.primary
  val surfaceColor = MaterialTheme.colorScheme.surface
  val borderColor = MaterialTheme.colorScheme.outlineVariant
  val unconfirmedColor = MaterialTheme.colorScheme.tertiary

  Column(modifier = Modifier.fillMaxSize()) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      shape = RoundedCornerShape(8.dp),
    ) {
      Text(
        text = "仅表达已发生定稿剧情因果 · 双指缩放/单指拖拽画布 · 实线=作者已确认因果，虚线=AI 建议待确认",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(10.dp),
      )
    }

    // Interactive Compose Canvas with pan & zoom
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(270.dp)
        .padding(horizontal = 12.dp)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, gestureZoom, _ ->
            zoom = (zoom * gestureZoom).coerceIn(0.6f, 2.2f)
            panOffset += pan
          }
        },
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val nodeMap = graph.nodes.associateBy { it.id }

        // Draw causal edges first
        graph.edges.forEach { edge ->
          val from = nodeMap[edge.fromNodeId] ?: return@forEach
          val to = nodeMap[edge.toNodeId] ?: return@forEach
          val start = Offset(from.x * zoom + panOffset.x + 84f * zoom, from.y * zoom + panOffset.y + 30f * zoom)
          val end = Offset(to.x * zoom + panOffset.x + 84f * zoom, to.y * zoom + panOffset.y + 30f * zoom)
          val strokeColor = if (edge.confirmed) primaryColor else unconfirmedColor
          val pathEffect = if (edge.confirmed) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)

          drawLine(
            color = strokeColor,
            start = start,
            end = end,
            strokeWidth = if (edge.confirmed) 4f else 3f,
            pathEffect = pathEffect,
          )
          // Arrow head
          val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
          val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
          val headLen = 14f
          drawLine(
            color = strokeColor,
            start = mid,
            end = Offset(
              mid.x - (headLen * cos(angle - 0.5)).toFloat(),
              mid.y - (headLen * sin(angle - 0.5)).toFloat(),
            ),
            strokeWidth = 3.5f,
          )
          drawLine(
            color = strokeColor,
            start = mid,
            end = Offset(
              mid.x - (headLen * cos(angle + 0.5)).toFloat(),
              mid.y - (headLen * sin(angle + 0.5)).toFloat(),
            ),
            strokeWidth = 3.5f,
          )
        }

        // Draw confirmed story event nodes
        graph.nodes.forEach { node ->
          val topLeft = Offset(node.x * zoom + panOffset.x, node.y * zoom + panOffset.y)
          val boxSize = Size(168f * zoom, 60f * zoom)
          drawRoundRect(
            color = surfaceColor,
            topLeft = topLeft,
            size = boxSize,
            cornerRadius = CornerRadius(14f * zoom, 14f * zoom),
          )
          drawRoundRect(
            color = borderColor,
            topLeft = topLeft,
            size = boxSize,
            cornerRadius = CornerRadius(14f * zoom, 14f * zoom),
            style = Stroke(width = 2.5f),
          )
          drawCircle(
            color = primaryColor,
            radius = 5f * zoom,
            center = Offset(topLeft.x + 14f * zoom, topLeft.y + 30f * zoom),
          )
        }
      }
      // Subtle legend overlay inside canvas corner
      Text(
        text = "节点 ${graph.nodes.size} · 已确认 ${graph.edges.count { it.confirmed }} · 待确认 ${graph.edges.count { !it.confirmed }}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
      )
    }

    // Edge confirmation review list below canvas
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.weight(1f),
    ) {
      item {
        Text("因果关系审阅列表（虚线箭头须人工确认转实线）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      }
      items(graph.edges, key = { it.id }) { edge ->
        val fromTitle = graph.nodes.firstOrNull { it.id == edge.fromNodeId }?.title ?: edge.fromNodeId
        val toTitle = graph.nodes.firstOrNull { it.id == edge.toNodeId }?.title ?: edge.toNodeId
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(
            1.dp,
            if (edge.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          ),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text("$fromTitle -> $toTitle", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
              Text(
                "${edge.relationLabel} · ${if (edge.confirmed) "实线（已人工确认）" else "虚线（AI 建议）"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (edge.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (!edge.confirmed) {
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                  onClick = { onConfirmEdge(edge.id) },
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                  shape = RoundedCornerShape(8.dp),
                ) {
                  Text("确认转实线", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onRejectEdge(edge.id) }) {
                  Text("移除", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PlotPlanningTab(
  plans: List<PlotPlanIdea>,
  onAddPlan: (String, String, List<String>) -> Unit,
  onCreateNextChapterFromPlan: (PlotPlanIdea) -> Unit,
) {
  var showAddDialog by remember { mutableStateOf(false) }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("剧情规划（尚在构思 · 不混入定稿事实）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            "所有备选后续走向统一保存在此，点选任一走向即可先拟章节摘要再生成下一章。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Button(
          onClick = { showAddDialog = true },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("新增构思")
        }
      }
    }

    items(plans, key = { it.id }) { plan ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("${plan.branchTag} · ${plan.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(plan.premise, style = MaterialTheme.typography.bodySmall)
          plan.keyBeats.forEach { beat ->
            Text("- $beat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Button(
            onClick = { onCreateNextChapterFromPlan(plan) },
            shape = RoundedCornerShape(8.dp),
          ) {
            Text("按此走向新建下一章（先拟摘要）")
          }
        }
      }
    }
  }

  if (showAddDialog) {
    var title by remember { mutableStateOf("") }
    var premise by remember { mutableStateOf("") }
    var beats by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showAddDialog = false },
      title = { Text("新增备选剧情走向") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("走向标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = premise, onValueChange = { premise = it }, label = { Text("核心冲突设想") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = beats, onValueChange = { beats = it }, label = { Text("关键节拍（每行一条）") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (title.isNotBlank()) {
              onAddPlan(title.trim(), premise.trim(), beats.lines().map { it.trim() }.filter { it.isNotEmpty() })
              showAddDialog = false
            }
          },
        ) { Text("保存至构思区") }
      },
      dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } },
    )
  }
}

@Composable
private fun AdvancedWorldbookTab(
  entries: List<WorldbookEntry>,
  charBudget: Int,
  defaultTestCorpus: String,
  onUpsertEntry: (WorldbookEntry) -> Unit,
  onToggleEntry: (String, Boolean) -> Unit,
  onUpdateBudget: (Int) -> Unit,
  onImportJson: (String) -> Result<Int>,
) {
  var previewText by remember(defaultTestCorpus) { mutableStateOf(defaultTestCorpus.ifBlank { "沈砚雪夜进入白塔，触发禁令。" }) }
  var showAddDialog by remember { mutableStateOf(false) }
  var showImportDialog by remember { mutableStateOf(false) }
  var importFeedback by remember { mutableStateOf<String?>(null) }

  val evalResult = remember(entries, previewText, charBudget) {
    WorldbookEngine.evaluate(entries, previewText, charBudget = charBudget)
  }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("酒馆级世界书 (${entries.size} 条)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(
            onClick = { showImportDialog = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp),
          ) {
            Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("导入酒馆 JSON")
          }
          Button(
            onClick = { showAddDialog = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp),
          ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("新建条目")
          }
        }
      }
    }

    // Transparent Hit Preview & Budget Control
    item {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("命中预览与字数预算控制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          OutlinedTextField(
            value = previewText,
            onValueChange = { previewText = it },
            label = { Text("测试扫描上下文（支持实时检验主词 / 次级 AND·OR·NOT / 递归触发）") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            "世界书注入预算：${evalResult.totalCharsUsed} / $charBudget 字 · 命中 ${evalResult.matchedTraces.size} 条（预算截断 ${evalResult.truncatedCount} 条）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Slider(
            value = charBudget.toFloat(),
            onValueChange = { onUpdateBudget((it.toInt() / 200) * 200) },
            valueRange = 600f..6000f,
          )
          evalResult.matchedTraces.forEach { trace ->
            Text(
              text = "${if (trace.includedInBudget) "[注入]" else "[超预算略过]"} ${trace.entry.title} · 优先级 ${trace.entry.priority} · ${trace.triggerReason}",
              style = MaterialTheme.typography.bodySmall,
              color = if (trace.includedInBudget) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    importFeedback?.let { msg ->
      item {
        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      }
    }

    items(entries, key = { it.id }) { entry ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              Text(
                buildString {
                  append(if (entry.alwaysActive) "常驻" else "主词: ${entry.primaryKeys.joinToString("/")}")
                  if (entry.secondaryLogic != SecondaryKeyLogic.NONE && entry.secondaryKeys.isNotEmpty()) {
                    append(" · ${entry.secondaryLogic.label}: ${entry.secondaryKeys.joinToString("/")}")
                  }
                  append(" · 优先级 ${entry.priority}")
                  if (entry.recursiveScan) append(" · 关联递归")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
            Switch(checked = entry.enabled, onCheckedChange = { onToggleEntry(entry.id, it) })
          }
          Text(entry.content, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }

  if (showAddDialog) {
    var title by remember { mutableStateOf("") }
    var primaryKeys by remember { mutableStateOf("白塔") }
    var secondaryKeys by remember { mutableStateOf("禁令") }
    var logic by remember { mutableStateOf(SecondaryKeyLogic.AND_ANY) }
    var alwaysActive by remember { mutableStateOf(false) }
    var recursive by remember { mutableStateOf(true) }
    var priority by remember { mutableIntStateOf(70) }
    var content by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showAddDialog = false },
      title = { Text("新建高级世界书条目") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("条目标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("常驻注入（无需触发词）", style = MaterialTheme.typography.bodySmall)
            Switch(checked = alwaysActive, onCheckedChange = { alwaysActive = it })
          }
          if (!alwaysActive) {
            OutlinedTextField(value = primaryKeys, onValueChange = { primaryKeys = it }, label = { Text("主触发词（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = secondaryKeys, onValueChange = { secondaryKeys = it }, label = { Text("次级条件词（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              listOf(SecondaryKeyLogic.NONE, SecondaryKeyLogic.AND_ANY, SecondaryKeyLogic.OR, SecondaryKeyLogic.NOT_ANY).forEach { opt ->
                TextButton(onClick = { logic = opt }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                  Text(
                    when (opt) {
                      SecondaryKeyLogic.NONE -> "无"
                      SecondaryKeyLogic.AND_ANY -> "AND"
                      SecondaryKeyLogic.OR -> "OR"
                      else -> "NOT"
                    },
                    fontWeight = if (logic == opt) FontWeight.Bold else FontWeight.Normal,
                  )
                }
              }
            }
          }
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("允许关联递归触发 · 优先级 $priority", style = MaterialTheme.typography.bodySmall)
            Switch(checked = recursive, onCheckedChange = { recursive = it })
          }
          OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("世界书设定正文") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (title.isNotBlank() && content.isNotBlank()) {
              onUpsertEntry(
                WorldbookEntry(
                  title = title.trim(),
                  content = content.trim(),
                  alwaysActive = alwaysActive,
                  primaryKeys = primaryKeys.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
                  secondaryKeys = secondaryKeys.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
                  secondaryLogic = if (alwaysActive) SecondaryKeyLogic.NONE else logic,
                  priority = priority,
                  recursiveScan = recursive,
                )
              )
              showAddDialog = false
            }
          },
        ) { Text("保存条目") }
      },
      dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } },
    )
  }

  if (showImportDialog) {
    var jsonInput by remember {
      mutableStateOf(
        """{"entries":{"0":{"comment":"司礼监暗印","key":["暗印","青铜印"],"keysecondary":["失窃"],"selective":true,"selectiveLogic":0,"content":"青铜旧印底部藏有三道回纹，缺一角即代表原主已遭除名。","order":82,"constant":false}}}"""
      )
    }
    AlertDialog(
      onDismissRequest = { showImportDialog = false },
      title = { Text("导入酒馆世界书 JSON") },
      text = {
        OutlinedTextField(
          value = jsonInput,
          onValueChange = { jsonInput = it },
          label = { Text("SillyTavern / Character Book JSON") },
          minLines = 5,
          maxLines = 9,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onImportJson(jsonInput)
              .onSuccess { count ->
                importFeedback = "已成功导入 $count 条酒馆世界书设定。"
                showImportDialog = false
              }
              .onFailure { err ->
                importFeedback = "导入失败：${err.message}"
              }
          },
        ) { Text("解析导入") }
      },
      dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } },
    )
  }
}
