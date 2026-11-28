package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.QingJianAccent
import com.qingjian.core.eta.AiAccountProfile
import com.qingjian.core.eta.AiProviderType
import com.qingjian.core.eta.ThinkingLevel
import com.qingjian.core.storage.AiUsageEntity
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AiAndAppearanceSettingsDialog(
  isDarkTheme: Boolean,
  selectedAccent: QingJianAccent,
  profiles: List<AiAccountProfile>,
  activeProfileId: String,
  usageLogs: List<AiUsageEntity>,
  isOAuthInProgress: Boolean,
  oauthStatusMessage: String?,
  isTestingConnection: Boolean,
  connectionTestResult: String?,
  onToggleDarkTheme: (Boolean) -> Unit,
  onSelectAccent: (QingJianAccent) -> Unit,
  onSelectProfile: (String) -> Unit,
  onUpdateProfile: (AiAccountProfile) -> Unit,
  onAddProfile: (AiAccountProfile) -> Unit,
  onDeleteProfile: (String) -> Unit,
  onStartCodexOAuthLogin: (profileId: String, openBrowser: (String) -> Unit) -> Unit,
  onCancelCodexOAuthLogin: () -> Unit,
  onSignOutCodexOAuth: (profileId: String) -> Unit,
  onTestActiveConnection: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

  val labelState = rememberTextFieldState(activeProfile.label)
  val modelState = rememberTextFieldState(activeProfile.model)
  val endpointState = rememberTextFieldState(activeProfile.endpoint)
  val credentialState = rememberTextFieldState(activeProfile.authCredential)

  LaunchedEffect(activeProfile.id, activeProfile.model, activeProfile.endpoint, activeProfile.label) {
    if (labelState.text.toString() != activeProfile.label) {
      labelState.edit { replace(0, length, activeProfile.label) }
    }
    if (modelState.text.toString() != activeProfile.model) {
      modelState.edit { replace(0, length, activeProfile.model) }
    }
    if (endpointState.text.toString() != activeProfile.endpoint) {
      endpointState.edit { replace(0, length, activeProfile.endpoint) }
    }
    if (activeProfile.provider != AiProviderType.CHATGPT_CODEX_OAUTH &&
      credentialState.text.toString() != activeProfile.authCredential
    ) {
      credentialState.edit { replace(0, length, activeProfile.authCredential) }
    }
  }

  var showAddMenu by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(MiuixTheme.colorScheme.background),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Eta 引擎基座与 Miuix 外观",
              style = MiuixTheme.textStyles.title3,
              color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
              text = "真实 Miuix 组件 · Codex OAuth 1455 本地回环授权 · OpenAI / DeepSeek SSE",
              style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
          }
          TextButton(
            text = "完成",
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            minHeight = 36.dp,
          )
        }
        HorizontalDivider()

        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          contentPadding = PaddingValues(bottom = 28.dp),
        ) {
          // 1. Miuix Appearance
          item(key = "sec_appearance") {
            SmallTitle(text = "Miuix 界面外观与 Muya 编辑器同步")
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            ) {
              SwitchPreference(
                title = "深色模式",
                summary = "切换 Miuix Monet 深色方案并实时同步注入 Muya 编辑器样式",
                checked = isDarkTheme,
                onCheckedChange = onToggleDarkTheme,
              )
              HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = "自定义主题色（Spec2025 动态调色板）",
                  style = MiuixTheme.textStyles.body2,
                  color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  QingJianAccent.entries.forEach { acc ->
                    val chosen = acc == selectedAccent
                    Box(
                      modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDarkTheme) acc.darkPrimary else acc.lightPrimary)
                        .then(
                          if (chosen) {
                            Modifier.border(
                              BorderStroke(2.dp, MiuixTheme.colorScheme.onBackground),
                              RoundedCornerShape(10.dp),
                            )
                          } else {
                            Modifier
                          },
                        )
                        .clickable { onSelectAccent(acc) },
                      contentAlignment = Alignment.Center,
                    ) {
                      Text(
                        text = acc.label,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onPrimary,
                      )
                    }
                  }
                }
              }
            }
          }

          // 2. Multi-Account Profiles
          item(key = "sec_profiles_header") {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 16.dp, end = 12.dp, bottom = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Eta 多账号 Profile 切换（DataStore 持久化）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
              )
              TextButton(
                text = if (showAddMenu) "收起" else "新增账号",
                onClick = { showAddMenu = !showAddMenu },
                colors = ButtonDefaults.textButtonColors(),
                minHeight = 32.dp,
              )
            }
            if (showAddMenu) {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 4.dp),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  TextButton(
                    text = "+ Codex OAuth",
                    onClick = {
                      onAddProfile(
                        AiAccountProfile(
                          label = "Codex 账号 ${profiles.size + 1}",
                          provider = AiProviderType.CHATGPT_CODEX_OAUTH,
                          thinkingLevel = ThinkingLevel.HIGH,
                        ),
                      )
                      showAddMenu = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 36.dp,
                  )
                  TextButton(
                    text = "+ DeepSeek",
                    onClick = {
                      onAddProfile(
                        AiAccountProfile(
                          label = "DeepSeek 账号 ${profiles.size + 1}",
                          provider = AiProviderType.DEEPSEEK,
                          thinkingLevel = ThinkingLevel.MEDIUM,
                        ),
                      )
                      showAddMenu = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                    minHeight = 36.dp,
                  )
                  TextButton(
                    text = "+ OpenAI 兼容",
                    onClick = {
                      onAddProfile(
                        AiAccountProfile(
                          label = "OpenAI 接口 ${profiles.size + 1}",
                          provider = AiProviderType.OPENAI,
                          thinkingLevel = ThinkingLevel.MEDIUM,
                        ),
                      )
                      showAddMenu = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                    minHeight = 36.dp,
                  )
                }
              }
            }
          }

          item(key = "sec_profiles_list") {
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            ) {
              profiles.forEachIndexed { idx, prof ->
                val selected = prof.id == activeProfile.id
                val credStatus = when {
                  prof.provider == AiProviderType.CHATGPT_CODEX_OAUTH && prof.oauthConnected ->
                    "已授权 (${prof.oauthAccountEmail.ifBlank { prof.oauthAccountId.take(12) }})"
                  prof.provider != AiProviderType.CHATGPT_CODEX_OAUTH && prof.authCredential.isNotBlank() ->
                    "已配置 API Key"
                  else -> "未授权/未填密钥"
                }
                BasicComponent(
                  title = if (selected) "${prof.label}（当前激活）" else prof.label,
                  summary = "${prof.provider.label} · ${prof.model} · 思考 ${prof.thinkingLevel.label} · $credStatus",
                  onClick = { onSelectProfile(prof.id) },
                )
                if (idx < profiles.lastIndex) {
                  HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
              }
            }
          }

          // 3. Active Profile Configuration
          item(key = "sec_active_profile") {
            SmallTitle(text = "当前 Profile 详细配置 · ${activeProfile.label}")
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                TextField(
                  state = labelState,
                  lineLimits = TextFieldLineLimits.SingleLine,
                  label = "Profile 备注名",
                  modifier = Modifier.fillMaxWidth(),
                )

                Text(
                  text = "预设模型快捷切换",
                  style = MiuixTheme.textStyles.footnote1,
                  color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  activeProfile.provider.defaultModels.forEach { m ->
                    val chosen = activeProfile.model == m
                    TextButton(
                      text = m,
                      onClick = {
                        modelState.edit { replace(0, length, m) }
                        onUpdateProfile(activeProfile.copy(model = m))
                      },
                      colors = if (chosen) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                      minHeight = 32.dp,
                      textStyle = MiuixTheme.textStyles.footnote1,
                    )
                  }
                }

                Text(
                  text = "思考等级 (Reasoning Effort)",
                  style = MiuixTheme.textStyles.footnote1,
                  color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  ThinkingLevel.entries.forEach { lvl ->
                    val chosen = activeProfile.thinkingLevel == lvl
                    TextButton(
                      text = lvl.label,
                      onClick = { onUpdateProfile(activeProfile.copy(thinkingLevel = lvl)) },
                      modifier = Modifier.weight(1f),
                      colors = if (chosen) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                      minHeight = 34.dp,
                      textStyle = MiuixTheme.textStyles.footnote1,
                    )
                  }
                }

                TextField(
                  state = modelState,
                  lineLimits = TextFieldLineLimits.SingleLine,
                  label = "模型 ID",
                  modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                  state = endpointState,
                  lineLimits = TextFieldLineLimits.SingleLine,
                  label = "API 接口地址",
                  modifier = Modifier.fillMaxWidth(),
                )

                if (activeProfile.provider == AiProviderType.CHATGPT_CODEX_OAUTH) {
                  Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
                  ) {
                    Column(
                      modifier = Modifier.padding(12.dp),
                      verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Text(
                        text = if (activeProfile.oauthConnected) {
                          "ChatGPT Codex 套餐已连接 · 工作区 ${activeProfile.oauthAccountId.take(16)}"
                        } else {
                          "尚未授权 ChatGPT Codex 套餐"
                        },
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                      )
                      if (activeProfile.oauthAccountEmail.isNotBlank()) {
                        Text(
                          text = "账号邮箱：${activeProfile.oauthAccountEmail}",
                          style = MiuixTheme.textStyles.footnote1,
                          color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                      }
                      Text(
                        text = "采用 Mangi-11/Eta 同源 PKCE 协议：启动 127.0.0.1:1455 本地回环监听，唤起浏览器登录 auth.openai.com 并自动换取刷新令牌。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                      )

                      if (isOAuthInProgress) {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                          InfiniteProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MiuixTheme.colorScheme.primary,
                          )
                          Text(
                            text = oauthStatusMessage ?: "正在等待浏览器完成 OAuth 回调…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                          )
                          TextButton(
                            text = "取消",
                            onClick = onCancelCodexOAuthLogin,
                            colors = ButtonDefaults.textButtonColors(),
                            minHeight = 32.dp,
                          )
                        }
                      } else {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                          TextButton(
                            text = if (activeProfile.oauthConnected) "重新授权登录" else "浏览器授权登录 ChatGPT Codex",
                            onClick = {
                              onStartCodexOAuthLogin(activeProfile.id) { url ->
                                runCatching {
                                  context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                  )
                                }
                              }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 40.dp,
                          )
                          if (activeProfile.oauthConnected) {
                            TextButton(
                              text = "登出",
                              onClick = { onSignOutCodexOAuth(activeProfile.id) },
                              colors = ButtonDefaults.textButtonColors(),
                              minHeight = 40.dp,
                            )
                          }
                        }
                      }

                      if (!oauthStatusMessage.isNullOrBlank() && !isOAuthInProgress) {
                        Text(
                          text = oauthStatusMessage,
                          style = MiuixTheme.textStyles.footnote1,
                          color = MiuixTheme.colorScheme.primary,
                        )
                      }
                    }
                  }
                } else {
                  TextField(
                    state = credentialState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    label = "API Key（必填，仅保存在本机 DataStore）",
                    modifier = Modifier.fillMaxWidth(),
                  )
                }

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  TextButton(
                    text = "保存配置",
                    onClick = {
                      val updatedCred = if (activeProfile.provider == AiProviderType.CHATGPT_CODEX_OAUTH) {
                        activeProfile.authCredential
                      } else {
                        credentialState.text.toString().trim()
                      }
                      onUpdateProfile(
                        activeProfile.copy(
                          label = labelState.text.toString().trim().ifBlank { activeProfile.label },
                          model = modelState.text.toString().trim().ifBlank { activeProfile.model },
                          endpoint = endpointState.text.toString().trim().ifBlank { activeProfile.endpoint },
                          authCredential = updatedCred,
                        ),
                      )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    minHeight = 40.dp,
                  )
                  TextButton(
                    text = if (isTestingConnection) "检测中…" else "实时连通性测试",
                    enabled = !isTestingConnection,
                    onClick = onTestActiveConnection,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                    minHeight = 40.dp,
                  )
                }

                if (!connectionTestResult.isNullOrBlank()) {
                  Text(
                    text = connectionTestResult,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                  )
                }

                if (profiles.size > 1) {
                  TextButton(
                    text = "删除此账号 Profile",
                    onClick = { onDeleteProfile(activeProfile.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(),
                    minHeight = 36.dp,
                  )
                }
              }
            }
          }

          // 4. Real Usage Statistics from Room
          item(key = "sec_usage_header") {
            val totalPrompt = usageLogs.sumOf { it.promptTokens }
            val totalComp = usageLogs.sumOf { it.completionTokens }
            SmallTitle(text = "AI 真实调用统计（${usageLogs.size} 次 · 输入 $totalPrompt / 输出 $totalComp tok）")
          }

          if (usageLogs.isEmpty()) {
            item(key = "sec_usage_empty") {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp),
              ) {
                Text(
                  text = "尚无真实模型调用记录。配置 API Key 或完成 ChatGPT Codex OAuth 授权后，所有改稿、整章生成、交接提炼与试戏用量将写入 Room 数据库。",
                  style = MiuixTheme.textStyles.body2,
                  color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          } else {
            item(key = "sec_usage_list") {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp),
              ) {
                usageLogs.take(20).forEachIndexed { idx, log ->
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = "${log.profileLabel} · ${log.modelName}",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                      )
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                        text = "思考等级 ${log.thinkingLevel} · 耗时 ${log.latencyMs}ms",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                      )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = "${log.promptTokens} / ${log.completionTokens} tok",
                      style = MiuixTheme.textStyles.footnote1,
                      fontFamily = FontFamily.Monospace,
                      color = MiuixTheme.colorScheme.primary,
                    )
                  }
                  if (idx < minOf(usageLogs.size, 20) - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
