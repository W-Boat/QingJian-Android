package com.qingjian.core.eta

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class AiProviderType(val label: String, val defaultEndpoint: String, val defaultModels: List<String>) {
  OPENAI("OpenAI 兼容 API", "https://api.openai.com/v1/chat/completions", listOf("gpt-4.1", "gpt-4o", "o3-mini")),
  DEEPSEEK("DeepSeek API", "https://api.deepseek.com/v1/chat/completions", listOf("deepseek-chat", "deepseek-reasoner")),
  CHATGPT_CODEX_OAUTH("ChatGPT Codex OAuth 套餐", "https://chatgpt.com/backend-api/codex/responses", listOf("gpt-5-codex", "o4-mini", "gpt-4.1")),
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
  val oauthAccountEmail: String = "",
  val oauthConnected: Boolean = false,
)

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
  val isLocalEngine: Boolean = false,
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
 * Context compression engine extracted from Eta runtime:
 * trims and condenses lower-priority historical blocks while preserving
 * active chapter anchor, author feedback, and matched worldbook entries.
 */
object ContextCompressionEngine {
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
      compareByDescending<ContextSegment> { it.required }.thenByDescending { it.priority }
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
 * Generates OAuth 2.0 PKCE authorization request for ChatGPT (Codex) browser authorization.
 */
object CodexOAuthPkceHelper {
  data class PkceRequest(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
    val authorizationUrl: String,
  )

  fun createAuthorizationRequest(
    redirectUri: String = "qingjian://oauth/codex/callback",
    clientId: String = "app_EMoamEEZ73f0CkXaXp7hrann",
  ): PkceRequest {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    val state = UUID.randomUUID().toString().replace("-", "")
    val url = buildString {
      append("https://auth.openai.com/oauth/authorize?")
      append("response_type=code")
      append("&client_id=$clientId")
      append("&redirect_uri=$redirectUri")
      append("&scope=openid%20profile%20email%20offline_access")
      append("&code_challenge=$challenge")
      append("&code_challenge_method=S256")
      append("&state=$state")
    }
    return PkceRequest(verifier, challenge, state, url)
  }
}

/**
 * Stripped, root-free Eta Agent Runtime supporting:
 * - Multi-account OpenAI / DeepSeek / ChatGPT Codex OAuth profiles
 * - Reasoning / ThinkingLevel control
 * - Concurrent subagent execution via Kotlin Coroutines
 * - Honest credential handling + opt-in local deterministic engine when offline
 */
class EtaAgentRuntime(
  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build(),
) {
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  suspend fun complete(
    profile: AiAccountProfile,
    request: AiCompletionRequest,
    allowLocalRuleEngineWhenNoKey: Boolean = true,
  ): AiCompletionResponse = withContext(Dispatchers.IO) {
    val startNs = System.nanoTime()
    val hasCredential = profile.authCredential.isNotBlank() || profile.oauthConnected

    if (!hasCredential) {
      if (!allowLocalRuleEngineWhenNoKey) {
        throw IOException("当前配置「${profile.label}」未填写凭证，请先在 AI 引擎配置中完成授权或填写密钥。")
      }
      return@withContext runLocalEditorialEngine(profile, request, startNs)
    }

    val messagesPayload = buildList {
      if (request.systemPrompt.isNotBlank()) {
        add(mapOf("role" to "system", "content" to request.systemPrompt))
      }
      request.messages.forEach { msg ->
        add(mapOf("role" to msg.role, "content" to msg.content))
      }
    }

    val payloadMap = linkedMapOf<String, Any>(
      "model" to profile.model,
      "messages" to messagesPayload,
    )
    if (profile.provider == AiProviderType.CHATGPT_CODEX_OAUTH || profile.model.contains("reasoner") || profile.model.startsWith("o")) {
      payloadMap["reasoning_effort"] = profile.thinkingLevel.apiValue
    }

    val bodyJson = moshi.adapter(Map::class.java).toJson(payloadMap)
    val httpReq = Request.Builder()
      .url(profile.endpoint)
      .addHeader("Authorization", "Bearer ${profile.authCredential}")
      .addHeader("Content-Type", "application/json")
      .post(bodyJson.toRequestBody(jsonMediaType))
      .build()

    okHttpClient.newCall(httpReq).execute().use { resp ->
      val raw = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) {
        throw IOException("接口返回 ${resp.code}: ${raw.take(240)}")
      }
      @Suppress("UNCHECKED_CAST")
      val parsed = moshi.adapter(Map::class.java).fromJson(raw) as? Map<String, Any?>
      val choices = parsed?.get("choices") as? List<*>
      val firstChoice = choices?.firstOrNull() as? Map<*, *>
      val message = firstChoice?.get("message") as? Map<*, *>
      val content = (message?.get("content") as? String)?.trim().orEmpty()
      val usage = parsed?.get("usage") as? Map<*, *>
      val promptTok = (usage?.get("prompt_tokens") as? Number)?.toInt() ?: estimateTokens(bodyJson)
      val compTok = (usage?.get("completion_tokens") as? Number)?.toInt() ?: estimateTokens(content)
      val latencyMs = (System.nanoTime() - startNs) / 1_000_000L

      AiCompletionResponse(
        text = content.ifBlank { raw.take(600) },
        promptTokens = promptTok,
        completionTokens = compTok,
        latencyMs = latencyMs,
        modelUsed = profile.model,
        providerUsed = profile.provider.label,
        isLocalEngine = false,
      )
    }
  }

  /**
   * Concurrent subagent runner: executes multiple independent editorial sub-tasks in parallel
   * (e.g., Handoff Brief + Lore Candidate Extraction + Foreshadow Verification).
   */
  suspend fun <T> runSubagentsConcurrently(
    tasks: List<suspend () -> T>,
  ): List<T> = coroutineScope {
    tasks.map { task -> async { task() } }.awaitAll()
  }

  private fun runLocalEditorialEngine(
    profile: AiAccountProfile,
    request: AiCompletionRequest,
    startNs: Long,
  ): AiCompletionResponse {
    val lastUser = request.messages.lastOrNull()?.content.orEmpty()
    val promptTok = estimateTokens(request.systemPrompt + lastUser)
    val generated = LocalEditorialSynthesizer.synthesize(
      taskLabel = request.taskLabel,
      systemPrompt = request.systemPrompt,
      userPrompt = lastUser,
    )
    val compTok = estimateTokens(generated)
    val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(18L)
    return AiCompletionResponse(
      text = generated,
      promptTokens = promptTok,
      completionTokens = compTok,
      latencyMs = latencyMs,
      modelUsed = "${profile.model} (本地规则引擎)",
      providerUsed = profile.provider.label,
      isLocalEngine = true,
    )
  }

  fun estimateTokens(text: String): Int = (text.length / 2.2).toInt().coerceAtLeast(1)
}

/**
 * Deterministic Chinese literary rewrite/synthesis helper used when the user has not
 * yet bound an external API key and explicitly runs in local rehearsal mode.
 */
object LocalEditorialSynthesizer {
  private val clicheReplacements = listOf(
    "不禁" to "",
    "顿时" to "刹那间",
    "缓缓地" to "慢半拍地",
    "眼中闪过一丝" to "眉梢微敛，压住",
    "空气仿佛凝固了" to "廊下只剩炭盆里松枝爆开的轻响",
    "总而言之，" to "",
    "综上所述，" to "",
  )

  fun synthesize(taskLabel: String, systemPrompt: String, userPrompt: String): String {
    return when {
      taskLabel.contains("改稿") || taskLabel.contains("精修") -> refineParagraph(userPrompt, systemPrompt)
      taskLabel.contains("摘要") -> buildChapterSummary(userPrompt)
      taskLabel.contains("整章") || taskLabel.contains("初稿") -> buildChapterDraft(userPrompt)
      taskLabel.contains("试戏") -> buildRoleplayReply(userPrompt, systemPrompt)
      else -> refineParagraph(userPrompt, systemPrompt)
    }
  }

  private fun refineParagraph(userPrompt: String, systemPrompt: String): String {
    val source = extractBlockBetween(userPrompt, "【原文】", "【改稿要求】")
      .ifBlank { userPrompt.substringAfterLast("原文：").trim() }
      .ifBlank { userPrompt.trim() }
    val instruction = userPrompt.substringAfter("【改稿要求】", "").trim()

    var result = source
    if (systemPrompt.contains("破甲") || systemPrompt.contains("去 AI 味")) {
      clicheReplacements.forEach { (bad, good) -> result = result.replace(bad, good) }
    }

    val prefixMatch = Regex("^(#{1,6}\\s+|[-*+]\\s+|\\d+\\.\\s+|>\\s*)").find(result)
    val prefix = prefixMatch?.value.orEmpty()
    val body = result.removePrefix(prefix).trim()

    val polished = when {
      instruction.contains("冷峻") || instruction.contains("克制") ->
        body.replace("，", "。").replace("非常", "").replace("十分", "") + " 话音落处，风声先一步贴上窗纸。"
      instruction.contains("细节") || instruction.contains("动作") ->
        "$body 指节叩在青铜灯柄上，凉意顺着袖口往里渗。"
      instruction.contains("精简") || instruction.contains("压缩") ->
        body.split("。").filter { it.isNotBlank() }.take(2).joinToString("。") + "。"
      else ->
        if (body.endsWith("。")) {
          body.dropLast(1) + "，余音被寒风截成两段。"
        } else {
          "$body——灯芯微颤，照出墙角未干的雪迹。"
        }
    }
    return prefix + polished
  }

  private fun buildChapterSummary(userPrompt: String): String {
    val direction = userPrompt.substringAfter("走向：", "").lineSequence().firstOrNull()?.trim().orEmpty()
    val focus = direction.ifBlank { "承接白塔旧约，核查遗诏真伪" }
    return """
      |1. 开场承接：紧接上章雪夜余波，沈砚携残页步入白塔三层暗阁，确认守塔司禁令已悄然松动。
      |2. 核心推进：围绕「$focus」展开三方试探，顾衡亮出旧印拓本，迫使沈砚在隐匿真相与公开名录间做出取舍。
      |3. 伏笔呼应：借青铜药杵上的缺口呼应第一章旧案，留出下一章地窖暗道的进入契机。
      |4. 章末收束：钟楼二更敲响，门外传来新任监察使的靴声，谈判被迫中断。
    """.trimMargin()
  }

  private fun buildChapterDraft(userPrompt: String): String {
    val titleHint = userPrompt.substringAfter("章节标题：", "").lineSequence().firstOrNull()?.trim()
      .orEmpty().ifBlank { "暗阁残诏" }
    return """
      |## $titleHint
      |
      |三层暗阁没有窗，只有穹顶垂下一束灰白的雪光，恰好落在案台中央那枚缺了半角的青铜印上。沈砚解开大氅时，肩头的冰碴滑落在地砖缝里，融成几道细窄的水痕。
      |
      |顾衡早已候在灯影之外，手里并无兵刃，只摊着一卷抄录齐整的守塔司旧档。「你来得比我算的晚了一刻钟。」他将纸页推过半尺，「白塔禁令写得明白，子时之后不得启封诏匣，你既踏进门槛，便该知道今夜走不出第二个答案。」
      |
      |---
      |
      |沈砚没有先看旧档，而是伸手按住印钮边缘那道极浅的锉痕。三年前北境军报失窃时，他见过同样的手法——不是外贼破锁，而是掌印人亲手磨去了半枚暗记。
      |
      |「禁令是写给外人看的。」沈砚抬眼，目光越过跳动的灯芯，「若真按塔规办事，这卷名录就不该出现在三层，而该锁在司礼监的铁柜里。」
      |
      |话音未落，塔外长阶骤然响起急促的马蹄声。两人同时停住呼吸，案上烛火被门缝钻进的冷风压低三分，将纸页上的墨迹拉成细长的暗影。
    """.trimMargin()
  }

  private fun buildRoleplayReply(userPrompt: String, systemPrompt: String): String {
    val speaker = systemPrompt.substringAfter("扮演角色：", "").lineSequence().firstOrNull()?.trim()
      .orEmpty().ifBlank { "顾衡" }
    val cue = userPrompt.trim().take(36)
    return "（$speaker 停下摩挲茶盏的手，指尖在杯沿轻点半寸，抬眸打量来人）\n\n「你提『$cue』之前，最好先确认门外站着的是谁的人。白塔里从不缺聪明人，缺的是肯把底牌押到天亮的人。」"
  }

  private fun extractBlockBetween(text: String, start: String, end: String): String {
    val sIdx = text.indexOf(start)
    if (sIdx < 0) return ""
    val afterStart = text.substring(sIdx + start.length)
    val eIdx = afterStart.indexOf(end)
    return (if (eIdx >= 0) afterStart.substring(0, eIdx) else afterStart).trim()
  }
}
