package com.qingjian.core.eta

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

enum class AiProviderType(
  val label: String,
  val defaultEndpoint: String,
  val defaultModels: List<String>,
) {
  OPENAI(
    label = "OpenAI 兼容 API",
    defaultEndpoint = "https://api.openai.com/v1/chat/completions",
    defaultModels = listOf("gpt-4.1", "gpt-4o", "o3-mini"),
  ),
  DEEPSEEK(
    label = "DeepSeek API",
    defaultEndpoint = "https://api.deepseek.com/v1/chat/completions",
    defaultModels = listOf("deepseek-chat", "deepseek-reasoner"),
  ),
  CHATGPT_CODEX_OAUTH(
    label = "ChatGPT Codex OAuth 套餐",
    defaultEndpoint = EtaCodexOAuth.CODEX_RESPONSES_ENDPOINT,
    defaultModels = listOf("gpt-5.3-codex", "gpt-5-codex", "o4-mini", "gpt-4.1"),
  ),
}

enum class ThinkingLevel(val label: String, val apiValue: String) {
  LOW("低", "low"),
  MEDIUM("中", "medium"),
  HIGH("高", "high"),
  XHIGH("极高", "xhigh"),
}

data class AiAccountProfile(
  val id: String = "prof_${UUID.randomUUID().toString().take(8)}",
  val label: String,
  val provider: AiProviderType,
  val endpoint: String = provider.defaultEndpoint,
  val model: String = provider.defaultModels.first(),
  val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
  val authCredential: String = "",
  val oauthRefreshToken: String = "",
  val oauthExpiresAtMillis: Long = 0L,
  val oauthAccountId: String = "",
  val oauthAccountEmail: String = "",
  val oauthConnected: Boolean = false,
) {
  fun hasUsableCredential(): Boolean = when (provider) {
    AiProviderType.CHATGPT_CODEX_OAUTH -> oauthConnected && authCredential.isNotBlank()
    AiProviderType.OPENAI, AiProviderType.DEEPSEEK -> authCredential.isNotBlank()
  }
}

data class ChatMessage(
  val role: String,
  val content: String,
)

data class AiCompletionRequest(
  val systemPrompt: String,
  val messages: List<ChatMessage>,
  val maxOutputTokens: Int = 2048,
  val taskLabel: String = "文本生成",
)

data class AiCompletionResponse(
  val text: String,
  val promptTokens: Int,
  val completionTokens: Int,
  val latencyMs: Long,
  val modelUsed: String,
  val providerUsed: String,
)

data class ContextWindowConfig(
  val neighborParagraphsBefore: Int = 3,
  val neighborParagraphsAfter: Int = 2,
  val includeOutline: Boolean = true,
  val includeFinalizedHandoff: Boolean = true,
  val includeAuthorFeedback: Boolean = true,
  val worldbookCharBudget: Int = 2400,
  val totalContextCharBudget: Int = 12000,
)

/**
 * Context compression engine ported from Mangi-11/Eta runtime:
 * - Deterministic budget assembly (`compressAndAssemble`)
 * - LLM-backed archival compaction (`compactHistoryWithModel`)
 */
object ContextCompressionEngine {
  const val ARCHIVE_HEADER = "【青简上下文归档摘要】"

  data class ContextSegment(
    val title: String,
    val content: String,
    val priority: Int,
    val required: Boolean = false,
  )

  data class CompressedContext(
    val assembledPrompt: String,
    val originalChars: Int,
    val finalChars: Int,
    val wasCompressed: Boolean,
    val compressedSegments: List<String>,
  )

  fun compressAndAssemble(
    segments: List<ContextSegment>,
    charBudget: Int,
  ): CompressedContext {
    val nonEmpty = segments.filter { it.content.isNotBlank() }
    val originalChars = nonEmpty.sumOf { it.title.length + it.content.length + 8 }
    if (originalChars <= charBudget) {
      val prompt = nonEmpty.joinToString("\n\n") { "【${it.title}】\n${it.content.trim()}" }
      return CompressedContext(
        assembledPrompt = prompt,
        originalChars = originalChars,
        finalChars = prompt.length,
        wasCompressed = false,
        compressedSegments = emptyList(),
      )
    }

    val sorted = nonEmpty.sortedWith(
      compareByDescending<ContextSegment> { it.required }.thenByDescending { it.priority },
    )
    var remaining = charBudget
    val kept = mutableListOf<String>()
    val compressedNames = mutableListOf<String>()

    for (seg in sorted) {
      val header = "【${seg.title}】\n"
      val fullLen = header.length + seg.content.length + 2
      if (fullLen <= remaining) {
        kept.add(header + seg.content.trim())
        remaining -= fullLen
      } else if (remaining > header.length + 120 || seg.required) {
        val allowedBody = (remaining - header.length - 28).coerceAtLeast(100)
        val condensed = condenseParagraphs(seg.content, allowedBody)
        kept.add("$header$condensed\n（已按上下文预算压缩）")
        compressedNames.add(seg.title)
        remaining = (remaining - header.length - condensed.length - 16).coerceAtLeast(0)
      } else {
        compressedNames.add("${seg.title}(省略)")
      }
    }

    val finalText = kept.joinToString("\n\n")
    return CompressedContext(
      assembledPrompt = finalText,
      originalChars = originalChars,
      finalChars = finalText.length,
      wasCompressed = true,
      compressedSegments = compressedNames,
    )
  }

  /**
   * LLM-driven context compaction inspired by Mangi-11/Eta `AgentContextCompactor.kt`.
   */
  suspend fun compactHistoryWithModel(
    runtime: EtaAgentRuntime,
    profile: AiAccountProfile,
    historyMessages: List<ChatMessage>,
    existingArchive: String = "",
    onProfileRefreshed: (AiAccountProfile) -> Unit = {},
  ): String {
    if (historyMessages.isEmpty()) return existingArchive
    val transcript = historyMessages.joinToString("\n\n") { "[${it.role}] ${it.content}" }
    val prompt = buildString {
      if (existingArchive.isNotBlank()) {
        appendLine("已有历史归档：")
        appendLine(existingArchive)
        appendLine()
      }
      appendLine("请将以下长篇创作或试戏历史压缩为高密度事实交接摘要，保留：")
      appendLine("1. 已确立的剧情事实与时间线；2. 人物状态、位置与知情边界；3. 未回收伏笔与未完约束。")
      appendLine("不要使用 emoji，不要编造未出现的设定。")
      appendLine()
      appendLine(transcript)
    }
    val response = runtime.complete(
      profile = profile,
      request = AiCompletionRequest(
        systemPrompt = "你是青简上下文压缩引擎（基于 Eta AgentContextCompactor），只输出结构化精炼摘要。",
        messages = listOf(ChatMessage("user", prompt)),
        taskLabel = "上下文归档压缩",
      ),
      onProfileRefreshed = onProfileRefreshed,
    )
    return "$ARCHIVE_HEADER\n${response.text.trim()}"
  }

  private fun condenseParagraphs(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text.trim()
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size <= 2) return text.take(maxChars).trimEnd() + "…"
    val headQuota = (maxChars * 0.6).toInt()
    val tailQuota = (maxChars * 0.35).toInt()
    val head = text.take(headQuota).trimEnd()
    val tail = text.takeLast(tailQuota).trimStart()
    return "$head\n……\n$tail"
  }
}

/**
 * Root-free Mangi-11/Eta Agent Runtime for QingJian Android:
 * - Real OpenAI / DeepSeek Chat Completions SSE streaming
 * - Real ChatGPT Codex OAuth 2.0 PKCE + automatic refresh + Codex Responses SSE streaming
 * - Concurrent subagent execution via Kotlin Coroutines
 * - Zero fake/mock local text generation fallbacks
 */
class EtaAgentRuntime(
  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build(),
) {
  val oauthClient = EtaOAuthClient(okHttpClient)
  private val codexResponsesClient = EtaCodexResponsesClient(okHttpClient)
  private val chatCompletionsClient = EtaOpenAiChatCompletionsClient(okHttpClient)

  suspend fun complete(
    profile: AiAccountProfile,
    request: AiCompletionRequest,
    onDelta: ((String) -> Unit)? = null,
    onProfileRefreshed: (AiAccountProfile) -> Unit = {},
  ): AiCompletionResponse = withContext(Dispatchers.IO) {
    val ctx = currentCoroutineContext()

    when (profile.provider) {
      AiProviderType.CHATGPT_CODEX_OAUTH -> {
        if (!profile.oauthConnected || profile.authCredential.isBlank()) {
          throw EtaAuthRequiredException(
            "当前配置「${profile.label}」尚未完成 ChatGPT (Codex) OAuth 授权，请前往「设置 -> AI 引擎」点击浏览器授权登录。",
          )
        }
        var activeProfile = profile
        if (activeProfile.oauthExpiresAtMillis > 0L &&
          System.currentTimeMillis() + 120_000L >= activeProfile.oauthExpiresAtMillis &&
          activeProfile.oauthRefreshToken.isNotBlank()
        ) {
          val refreshed = oauthClient.refreshToken(activeProfile.oauthRefreshToken)
          activeProfile = activeProfile.copy(
            authCredential = refreshed.accessToken,
            oauthRefreshToken = refreshed.refreshToken,
            oauthExpiresAtMillis = refreshed.expiresAtMillis,
            oauthAccountId = refreshed.accountId,
            oauthAccountEmail = refreshed.email.ifBlank { activeProfile.oauthAccountEmail },
            oauthConnected = true,
          )
          onProfileRefreshed(activeProfile)
        }

        val accountId = activeProfile.oauthAccountId.ifBlank {
          EtaCodexOAuth.extractAccountId(activeProfile.authCredential)
        }
        codexResponsesClient.execute(
          profile = activeProfile,
          accessToken = activeProfile.authCredential,
          accountId = accountId,
          request = request,
          onDelta = onDelta,
          isCancelled = { !ctx.isActive },
        )
      }

      AiProviderType.OPENAI, AiProviderType.DEEPSEEK -> {
        if (profile.authCredential.isBlank()) {
          throw EtaAuthRequiredException(
            "当前配置「${profile.label}」尚未填写 API Key，请前往「设置 -> AI 引擎」填写后重试。",
          )
        }
        chatCompletionsClient.execute(
          profile = profile,
          request = request,
          onDelta = onDelta,
          isCancelled = { !ctx.isActive },
        )
      }
    }
  }

  /**
   * Concurrent subagent runner: executes multiple independent editorial sub-tasks in parallel.
   */
  suspend fun <T> runSubagentsConcurrently(
    tasks: List<suspend () -> T>,
  ): List<T> = coroutineScope {
    tasks.map { task -> async { task() } }.awaitAll()
  }

  fun estimateTokens(text: String): Int = estimateTokenCount(text)
}
