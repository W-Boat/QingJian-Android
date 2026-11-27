package com.qingjian.feature.rp

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingjian.core.eta.TavernCardCodec
import com.qingjian.core.eta.TavernCharacterCard
import com.qingjian.core.eta.WorldbookEngine
import com.qingjian.core.eta.WorldbookEntry
import com.qingjian.core.storage.LoreCategory
import com.qingjian.core.storage.LoreEntry

data class RpTurnMessage(
  val id: String,
  val isAuthor: Boolean,
  val speaker: String,
  val content: String,
  val triggeredWorldbookTitles: List<String> = emptyList(),
)

@Composable
fun RoleplayRehearsalScreen(
  bookTitle: String,
  cards: List<TavernCharacterCard>,
  selectedCardId: String?,
  messages: List<RpTurnMessage>,
  worldbookEntries: List<WorldbookEntry>,
  novelCharacters: List<LoreEntry>,
  isGenerating: Boolean,
  onSelectCard: (String) -> Unit,
  onSendMessage: (cardId: String, authorPrompt: String) -> Unit,
  onImportCard: (TavernCharacterCard) -> Unit,
  onProjectLoreCharacterToCard: (LoreEntry) -> Unit,
  onInsertDialogueToDraft: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeCard = cards.firstOrNull { it.id == selectedCardId } ?: cards.firstOrNull()
  var inputText by remember { mutableStateOf("") }
  var showImportDialog by remember { mutableStateOf(false) }
  var showProjectDialog by remember { mutableStateOf(false) }

  val livePreviewCorpus = remember(inputText, messages) {
    (messages.takeLast(4).joinToString("\n") { it.content } + "\n" + inputText).trim()
  }
  val liveWorldbookHit = remember(livePreviewCorpus, worldbookEntries) {
    WorldbookEngine.evaluate(worldbookEntries, livePreviewCorpus, charBudget = 1800)
  }

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Top bar
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("角色扮演试戏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "共用《$bookTitle》世界书引擎 · 实时命中 ${liveWorldbookHit.matchedTraces.count { it.includedInBudget }} 条设定",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
          onClick = { showProjectDialog = true },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("投射人物", maxLines = 1)
        }
        OutlinedButton(
          onClick = { showImportDialog = true },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("导入酒馆卡", maxLines = 1)
        }
      }
    }

    // Character card horizontal selector
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(cards, key = { it.id }) { card ->
        val selected = card.id == activeCard?.id
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
          border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          ),
          modifier = Modifier.clickable { onSelectCard(card.id) },
        ) {
          Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(card.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
              "${card.specVersion} · ${card.personality.take(12).ifBlank { "本书投射" }}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
      }
    }

    // Shared Worldbook live hit trace bar
    if (liveWorldbookHit.matchedTraces.isNotEmpty()) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text(
          text = "当前命中世界书：" + liveWorldbookHit.matchedTraces
            .filter { it.includedInBudget }
            .joinToString(" · ") { "${it.entry.title}(${it.triggerReason})" },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    if (activeCard == null) {
      Column(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("暂无试戏角色卡", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text(
          "可从本书人物设定一键投射，或导入酒馆 V1/V2/V3 JSON 与 PNG 元数据角色卡。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("角色设定 · ${activeCard.name}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
              Text(activeCard.description, style = MaterialTheme.typography.bodySmall)
              if (activeCard.scenario.isNotBlank()) {
                Text(
                  "试戏情境：${activeCard.scenario}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        items(messages, key = { it.id }) { msg ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (msg.isAuthor) {
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
              MaterialTheme.colorScheme.surface
            },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = msg.speaker,
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary,
                )
                if (!msg.isAuthor) {
                  TextButton(
                    onClick = { onInsertDialogueToDraft(msg.content) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  ) {
                    Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("回写草稿", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
              Text(msg.content, style = MaterialTheme.typography.bodyMedium)
              if (msg.triggeredWorldbookTitles.isNotEmpty()) {
                Text(
                  text = "注入设定：${msg.triggeredWorldbookTitles.joinToString(" · ")}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = inputText,
          onValueChange = { inputText = it },
          placeholder = { Text("输入对白或场景动作（例如：提起白塔禁令试探口风）") },
          modifier = Modifier.weight(1f),
          maxLines = 3,
        )
        Button(
          enabled = inputText.isNotBlank() && !isGenerating,
          onClick = {
            val prompt = inputText.trim()
            inputText = ""
            onSendMessage(activeCard.id, prompt)
          },
          shape = RoundedCornerShape(10.dp),
        ) {
          Icon(Icons.Outlined.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
        }
      }
    }
  }

  if (showProjectDialog) {
    val chars = novelCharacters.filter { it.category == LoreCategory.CHARACTER }
    AlertDialog(
      onDismissRequest = { showProjectDialog = false },
      title = { Text("将本书人物投射为试戏角色卡") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (chars.isEmpty()) {
            Text("剧情工作台尚无已确认的人物资料，请先在剧情工作台审阅入库。")
          } else {
            chars.forEach { lore ->
              OutlinedButton(
                onClick = {
                  onProjectLoreCharacterToCard(lore)
                  showProjectDialog = false
                },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text("${lore.title} · ${lore.content.take(18)}")
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showProjectDialog = false }) { Text("关闭") } },
    )
  }

  if (showImportDialog) {
    var rawJson by remember { mutableStateOf("") }
    var importErr by remember { mutableStateOf<String?>(null) }

    AlertDialog(
      onDismissRequest = { showImportDialog = false },
      title = { Text("导入酒馆 V1/V2/V3 角色卡") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "粘贴酒馆 JSON 角色卡内容，或点击下方「测试 PNG 元数据解析」验证二进制 chara 块无损导入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = rawJson,
            onValueChange = { rawJson = it },
            label = { Text("酒馆角色卡 JSON") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedButton(
            onClick = {
              val sampleCard = TavernCharacterCard(
                name = "谢晚晴",
                description = "司礼监女史，熟稔先帝笔迹，暗中保存遗诏副录。",
                personality = "温婉警觉、话藏机锋",
                scenario = "雪夜药铺后堂对灯核验墨迹",
                firstMessage = "这封折子的朱批少了一笔，你可曾看清？",
                tags = listOf("PNG导入", "白塔"),
              )
              val pngBytes = TavernCardCodec.embedIntoMinimalPng(sampleCard)
              val decoded = TavernCardCodec.parsePngBytes(pngBytes)
              onImportCard(decoded)
              showImportDialog = false
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("验证并导入示例 PNG 角色卡 (tEXt chara)")
          }
          importErr?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            runCatching { TavernCardCodec.parseJson(rawJson) }
              .onSuccess {
                onImportCard(it)
                showImportDialog = false
              }
              .onFailure { importErr = it.message ?: "解析失败" }
          },
        ) {
          Text("解析并导入")
        }
      },
      dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } },
    )
  }
}
