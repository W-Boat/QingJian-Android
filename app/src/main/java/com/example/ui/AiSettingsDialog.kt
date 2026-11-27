package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.QingJianAccent
import com.qingjian.core.eta.AiAccountProfile
import com.qingjian.core.eta.AiProviderType
import com.qingjian.core.eta.CodexOAuthPkceHelper
import com.qingjian.core.eta.ThinkingLevel
import com.qingjian.core.storage.AiUsageEntity

@Composable
fun AiAndAppearanceSettingsDialog(
  isDarkTheme: Boolean,
  selectedAccent: QingJianAccent,
  profiles: List<AiAccountProfile>,
  activeProfileId: String,
  usageLogs: List<AiUsageEntity>,
  onToggleDarkTheme: (Boolean) -> Unit,
  onSelectAccent: (QingJianAccent) -> Unit,
  onSelectProfile: (String) -> Unit,
  onUpdateProfile: (AiAccountProfile) -> Unit,
  onAddProfile: (AiAccountProfile) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
  var editModel by remember(activeProfile.id) { mutableStateOf(activeProfile.model) }
  var editEndpoint by remember(activeProfile.id) { mutableStateOf(activeProfile.endpoint) }
  var editCredential by remember(activeProfile.id) { mutableStateOf(activeProfile.authCredential) }
  var oauthStatusNote by remember { mutableStateOf<String?>(null) }

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
            Text("AI 引擎基座与视觉主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Miuix 主题联动 Muya 编辑器 · 多账号 Profile / 思考等级 / 用量统计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          TextButton(onClick = onDismiss) { Text("完成") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          // Appearance
          item {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("界面外观与编辑器主题同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text("深色模式（同步注入 Muya CSS）", style = MaterialTheme.typography.bodySmall)
                  Switch(checked = isDarkTheme, onCheckedChange = onToggleDarkTheme)
                }
                Text("自定义主题色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  QingJianAccent.entries.forEach { acc ->
                    val chosen = acc == selectedAccent
                    Surface(
                      shape = RoundedCornerShape(8.dp),
                      color = if (isDarkTheme) acc.darkPrimary else acc.lightPrimary,
                      border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                      modifier = Modifier.size(width = 54.dp, height = 32.dp).clickable { onSelectAccent(acc) },
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text(acc.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                      }
                    }
                  }
                }
              }
            }
          }

          // Multi-Account Provider Profiles
          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("AI 多账号 Profile 切换", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
              OutlinedButton(
                onClick = {
                  onAddProfile(
                    AiAccountProfile(
                      label = "Codex 备用账号 ${profiles.size + 1}",
                      provider = AiProviderType.CHATGPT_CODEX_OAUTH,
                      thinkingLevel = ThinkingLevel.HIGH,
                    )
                  )
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
              ) { Text("新增 Codex Profile", style = MaterialTheme.typography.labelSmall) }
            }
          }

          items(profiles, key = { it.id }) { prof ->
            val selected = prof.id == activeProfile.id
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
              border = BorderStroke(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
              ),
              modifier = Modifier.fillMaxWidth().clickable { onSelectProfile(prof.id) },
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(prof.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                  Text(
                    "${prof.provider.label} · ${prof.model} · 思考等级 ${prof.thinkingLevel.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text(
                  text = if (selected) "当前启用" else "切换",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }

          // Active Profile Detail Configuration
          item {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前 Profile 详细配置 · ${activeProfile.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                Text("模型快捷切换", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  activeProfile.provider.defaultModels.forEach { m ->
                    OutlinedButton(
                      onClick = {
                        editModel = m
                        onUpdateProfile(activeProfile.copy(model = m))
                      },
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                      shape = RoundedCornerShape(8.dp),
                    ) {
                      Text(m, style = MaterialTheme.typography.labelSmall)
                    }
                  }
                }

                Text("思考等级调节 (Thinking Level)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  ThinkingLevel.entries.forEach { lvl ->
                    val chosen = activeProfile.thinkingLevel == lvl
                    OutlinedButton(
                      onClick = { onUpdateProfile(activeProfile.copy(thinkingLevel = lvl)) },
                      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                      shape = RoundedCornerShape(8.dp),
                      border = BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                      Text(
                        lvl.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                      )
                    }
                  }
                }

                OutlinedTextField(
                  value = editModel,
                  onValueChange = { editModel = it },
                  label = { Text("模型名称") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                  value = editEndpoint,
                  onValueChange = { editEndpoint = it },
                  label = { Text("接口地址") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
                )

                if (activeProfile.provider == AiProviderType.CHATGPT_CODEX_OAUTH) {
                  OutlinedButton(
                    onClick = {
                      val pkce = CodexOAuthPkceHelper.createAuthorizationRequest()
                      oauthStatusNote = "已生成 PKCE 授权挑战 (${pkce.codeChallenge.take(12)}…)，正在唤起系统浏览器完成 ChatGPT Codex OAuth 授权。"
                      runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pkce.authorizationUrl)))
                      }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                  ) {
                    Text("启动浏览器 ChatGPT (Codex) OAuth 授权")
                  }
                  oauthStatusNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  }
                } else {
                  OutlinedTextField(
                    value = editCredential,
                    onValueChange = { editCredential = it },
                    label = { Text("接口访问令牌（留空则使用本地规则演练引擎）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                  )
                }

                Button(
                  onClick = {
                    onUpdateProfile(
                      activeProfile.copy(
                        model = editModel.trim(),
                        endpoint = editEndpoint.trim(),
                        authCredential = editCredential.trim(),
                      )
                    )
                  },
                  shape = RoundedCornerShape(8.dp),
                ) { Text("保存 Profile 配置") }
              }
            }
          }

          // AI Usage Statistics (from Room AiUsageDao)
          item {
            val totalPrompt = usageLogs.sumOf { it.promptTokens }
            val totalComp = usageLogs.sumOf { it.completionTokens }
            Text(
              "AI 用量统计（累计 ${usageLogs.size} 次 · 输入 $totalPrompt / 输出 $totalComp tokens）",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
          }

          if (usageLogs.isEmpty()) {
            item {
              Text("尚无调用记录，在编辑器执行改稿、整章生成或角色试戏后将自动写入 Room 统计。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          } else {
            items(usageLogs.take(15), key = { it.id }) { log ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(10.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text("${log.profileLabel} · ${log.modelName}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text("思考等级 ${log.thinkingLevel} · 耗时 ${log.latencyMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  Text(
                    "${log.promptTokens} / ${log.completionTokens} tok",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
