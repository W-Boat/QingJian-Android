package com.qingjian.feature.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qingjian.core.sandbox.BookSkillsState
import com.qingjian.core.sandbox.BuiltInSkillType
import com.qingjian.core.sandbox.ImportedSkillPackage
import com.qingjian.core.sandbox.SandboxJsScript
import com.qingjian.core.sandbox.ScriptExecutionReport
import com.qingjian.core.sandbox.SkillMarkdownParser
import kotlin.math.roundToInt

@Composable
fun SkillsAndSandboxScreen(
  bookTitle: String,
  skillsState: BookSkillsState,
  aiWizardCompleted: Boolean,
  lastScriptReport: ScriptExecutionReport?,
  sandboxError: String?,
  onToggleBuiltInSkill: (BuiltInSkillType, Boolean) -> Unit,
  onRunStyleDistillation: (sampleTitle: String, sampleText: String, correctionRules: List<String>) -> Unit,
  onImportSkillPackage: (ImportedSkillPackage) -> Unit,
  onToggleImportedPackage: (String, Boolean) -> Unit,
  onAddJsScript: (name: String, description: String, code: String) -> Unit,
  onApproveJsScript: (String) -> Unit,
  onRevokeJsScript: (String) -> Unit,
  onRunJsScriptOnActiveChapter: (String) -> Unit,
  onSendReportToAiRevision: (ScriptExecutionReport) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showWizardDialog by remember { mutableStateOf(false) }
  var showImportSkillDialog by remember { mutableStateOf(false) }
  var showAddScriptDialog by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Header + AI-driven first-time configuration wizard
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("写作技能与隔离沙箱", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            "按《$bookTitle》独立启停 · 沙箱脚本须经作者显式批准方可执行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Button(
          onClick = { showWizardDialog = true },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Text(if (aiWizardCompleted) "调校文风向导" else "首次 AI 配置向导", maxLines = 1)
        }
      }
    }

    // Section 1: 3 Built-in Core Skills
    item {
      Text("内置核心技能", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }

    items(BuiltInSkillType.entries) { skill ->
      val enabled = skill in skillsState.enabledBuiltIn
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skill.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              skill.summary,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = enabled, onCheckedChange = { onToggleBuiltInSkill(skill, it) })
        }
      }
    }

    // Style Distillation Profile Card
    item {
      val profile = skillsState.styleProfile
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
            Text("当前文风蒸馏档案", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { showWizardDialog = true }) {
              Text(if (profile == null) "导入样稿提炼" else "重新提炼")
            }
          }
          if (profile == null) {
            Text(
              "尚未注入参考样稿。通过配置向导粘贴参考章，即可自动分析句长分布、叙述距离与对话节奏。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(
              "样稿：${profile.sampleTitle} · 平均句长 ${profile.avgSentenceChars.roundToInt()} 字 · 短句 ${(profile.shortSentenceRatio * 100).roundToInt()}% · 对话 ${(profile.dialogueRatio * 100).roundToInt()}%",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              "叙述距离：${profile.narrativeDistance} · 节奏特征：${profile.pacingSignature}",
              style = MaterialTheme.typography.bodySmall,
            )
            profile.distilledRules.forEach { rule ->
              Text("- $rule", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          if (skillsState.customCorrectionRules.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("作者自定义纠偏规则", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            skillsState.customCorrectionRules.forEach { rule ->
              Text("- $rule", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
    }

    // Section 2: Open Skill & SKILL.md packages
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("开放技能包 (SKILL.md)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
          onClick = { showImportSkillDialog = true },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("导入技能包")
        }
      }
    }

    if (skillsState.importedPackages.isEmpty()) {
      item {
        Text(
          "支持导入单文件或包含 SKILL.md 与参考资料的技能包，启用后自动注入写作与复审系统提示。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      items(skillsState.importedPackages, key = { it.id }) { pkg ->
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(pkg.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              Text(pkg.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                "参考附件：${pkg.referenceFiles.size} 个 · 规则长度 ${pkg.promptGuidelines.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(checked = pkg.enabled, onCheckedChange = { onToggleImportedPackage(pkg.id, it) })
          }
        }
      }
    }

    // Section 3: Isolated JS Sandbox Scripts
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text("隔离 JS 检查脚本沙箱", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            "无网络 · 无文件写入 · 2000ms 超时熔断 · SHA-256 批准门禁",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(
          onClick = { showAddScriptDialog = true },
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("新增脚本")
        }
      }
    }

    sandboxError?.let { err ->
      item {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
          )
        }
      }
    }

    items(skillsState.scripts, key = { it.id }) { script ->
      val approved = script.isApprovedForCurrentSource()
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
          1.dp,
          if (approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(script.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              text = if (approved) "已批准执行" else "待作者审批",
              style = MaterialTheme.typography.labelSmall,
              color = if (approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
              fontWeight = FontWeight.Medium,
            )
          }
          Text(script.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(
            text = "摘要 SHA-256: ${script.currentSha256().take(16)} · 超时上限 ${script.timeoutMs}ms · 权限 READ_TEXT_ONLY",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!approved) {
              Button(
                onClick = { onApproveJsScript(script.id) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
              ) {
                Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("审核并批准")
              }
            } else {
              OutlinedButton(
                onClick = { onRevokeJsScript(script.id) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
              ) {
                Text("撤销批准")
              }
            }
            OutlinedButton(
              onClick = { onRunJsScriptOnActiveChapter(script.id) },
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              shape = RoundedCornerShape(8.dp),
            ) {
              Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("检查当前章")
            }
          }
        }
      }
    }

    lastScriptReport?.let { report ->
      item {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("最近沙箱检查报告 · ${report.scriptName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              Text("${report.durationMs}ms", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            Text("执行环境：${report.engineName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report.issues.isEmpty()) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.CheckCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("正文检查通过，未发现规则冲突。", style = MaterialTheme.typography.bodySmall)
              }
            } else {
              report.issues.forEach { issue ->
                Text(
                  "[${issue.rule}] 「${issue.excerpt}」 -> ${issue.suggestion}",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
              Button(
                onClick = { onSendReportToAiRevision(report) },
                shape = RoundedCornerShape(8.dp),
              ) {
                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("将报告回喂 AI 辅助改稿")
              }
            }
          }
        }
      }
    }
  }

  if (showWizardDialog) {
    var sampleTitle by remember { mutableStateOf("参考样稿") }
    var sampleText by remember {
      mutableStateOf(
        "风雪拍在白塔窗棂上，细碎如盐。沈砚没有先开口，只将冻红的指尖按在青铜灯柄上。「人呢？」顾衡立在阴影里，声音压得极低，像怕惊动廊下结冰的檐铃。"
      )
    }
    var rulesText by remember { mutableStateOf(skillsState.customCorrectionRules.joinToString("\n")) }

    AlertDialog(
      onDismissRequest = { showWizardDialog = false },
      title = { Text("AI 驱动文风与纠偏配置向导") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            "粘贴代表本书气质的参考章节片段，并列出需规避的笔病，系统将立即蒸馏结构化风格档案。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = sampleTitle,
            onValueChange = { sampleTitle = it },
            label = { Text("样稿名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = sampleText,
            onValueChange = { sampleText = it },
            label = { Text("参考章节片段") },
            minLines = 4,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = rulesText,
            onValueChange = { rulesText = it },
            label = { Text("具体纠偏要求（每行一条）") },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val rules = rulesText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            onRunStyleDistillation(sampleTitle, sampleText, rules)
            showWizardDialog = false
          },
        ) {
          Text("蒸馏并保存")
        }
      },
      dismissButton = { TextButton(onClick = { showWizardDialog = false }) { Text("取消") } },
    )
  }

  if (showImportSkillDialog) {
    var skillMd by remember {
      mutableStateOf(
        """---
name: 权谋对白潜台词
description: 强化人物对话中的弦外之音与信息差博弈
---
1. 重要秘密禁止由人物一次性坦白，须通过试探、停顿和岔开话题分步透露。
2. 对话之间保留具体的器物操作（拨灯、斟茶、摩挲印钮）作为情绪缓冲。"""
      )
    }
    AlertDialog(
      onDismissRequest = { showImportSkillDialog = false },
      title = { Text("导入 SKILL.md 技能包") },
      text = {
        OutlinedTextField(
          value = skillMd,
          onValueChange = { skillMd = it },
          label = { Text("SKILL.md 内容（支持 YAML frontmatter）") },
          minLines = 6,
          maxLines = 10,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onImportSkillPackage(SkillMarkdownParser.parse(skillMd))
            showImportSkillDialog = false
          },
        ) {
          Text("导入技能")
        }
      },
      dismissButton = { TextButton(onClick = { showImportSkillDialog = false }) { Text("取消") } },
    )
  }

  if (showAddScriptDialog) {
    var scriptName by remember { mutableStateOf("自定义高频词检查") }
    var scriptDesc by remember { mutableStateOf("检出指定口癖或禁用词，交由 AI 在复审阶段替换。") }
    var scriptCode by remember {
      mutableStateOf(
        """function checkManuscript(text) {
  var rules = [
    { pattern: "竟然", rule: "禁用副词", suggestion: "用细节反差替代“竟然”" }
  ];
  var issues = [];
  for (var i = 0; i < rules.length; i++) {
    var idx = text.indexOf(rules[i].pattern);
    if (idx !== -1) {
      issues.push({ rule: rules[i].rule, severity: "WARN", excerpt: text.substring(Math.max(0, idx - 6), idx + 10), suggestion: rules[i].suggestion });
    }
  }
  return JSON.stringify(issues);
}"""
      )
    }

    AlertDialog(
      onDismissRequest = { showAddScriptDialog = false },
      title = { Text("导入隔离 JS 检查脚本") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(value = scriptName, onValueChange = { scriptName = it }, label = { Text("脚本名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = scriptDesc, onValueChange = { scriptDesc = it }, label = { Text("检查说明") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = scriptCode, onValueChange = { scriptCode = it }, label = { Text("JS 脚本源码（需提供 checkManuscript(text)）") }, minLines = 5, maxLines = 8, modifier = Modifier.fillMaxWidth())
          Text("新脚本默认处于未批准状态，须经人工核对后方可启用执行。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onAddJsScript(scriptName.trim(), scriptDesc.trim(), scriptCode)
            showAddScriptDialog = false
          },
        ) {
          Text("添加（待审批）")
        }
      },
      dismissButton = { TextButton(onClick = { showAddScriptDialog = false }) { Text("取消") } },
    )
  }
}
