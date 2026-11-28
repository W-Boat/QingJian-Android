package com.qingjian.core.eta

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * SSE framing reader ported from Mangi-11/Eta (`ProviderSseReader.kt`).
 * Dispatches complete SSE frames without waiting for server EOF when [onEvent] returns false.
 */
fun readEtaProviderSse(
  stream: InputStream,
  dispatchPartialFrameAtEof: Boolean = true,
  isCancelled: () -> Boolean = { false },
  onEvent: (event: String, data: String) -> Boolean,
) {
  var event = ""
  val dataLines = mutableListOf<String>()
  fun dispatch(): Boolean {
    val name = event
    val payload = dataLines.joinToString("\n")
    event = ""
    dataLines.clear()
    if (payload.isBlank()) return true
    if (isCancelled()) return false
    return onEvent(name, payload)
  }

  BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
    while (true) {
      if (isCancelled()) break
      val line = reader.readLine()
      if (line == null) {
        if (dispatchPartialFrameAtEof) dispatch()
        break
      }
      when {
        line.isEmpty() -> if (!dispatch()) break
        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
        line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
      }
    }
  }
}

/**
 * Ported from Mangi-11/Eta (`SubscriptionResponsesRequest.kt` & `ProviderRequestHeaders.kt`).
 * Enforces ChatGPT Codex Responses protocol invariants.
 */
object EtaSubscriptionResponsesRequest {
  private val CODEX_UNSUPPORTED_FIELDS = setOf(
    "max_output_tokens",
    "max_tokens",
    "temperature",
    "top_p",
    "background",
    "conversation",
  )

  fun configureCodexRequest(request: JSONObject) {
    val unsupported = CODEX_UNSUPPORTED_FIELDS.filter(request::has)
    require(unsupported.isEmpty()) {
      "Codex Responses 协议不支持参数：${unsupported.joinToString()}"
    }
    request.put("store", false)
    request.put("stream", true)
    val include = request.optJSONArray("include") ?: JSONArray()
    val hasEncrypted = (0 until include.length()).any { include.optString(it) == "reasoning.encrypted_content" }
    if (!hasEncrypted) {
      include.put("reasoning.encrypted_content")
    }
    request.put("include", include)
  }

  fun buildHeaders(
    provider: AiProviderType,
    bearerToken: String,
    accountId: String = "",
    sessionId: String = UUID.randomUUID().toString(),
  ): Headers {
    val builder = Headers.Builder()
      .set("User-Agent", "Eta")
      .set("Authorization", "Bearer $bearerToken")
      .set("Content-Type", "application/json")

    if (provider == AiProviderType.CHATGPT_CODEX_OAUTH) {
      builder
        .set("Accept", "text/event-stream")
        .set("originator", EtaCodexOAuth.ORIGINATOR)
        .set("OpenAI-Beta", "responses=experimental")
        .set("session-id", sessionId)
      if (accountId.isNotBlank()) {
        builder.set("ChatGPT-Account-Id", accountId)
      }
    }
    return builder.build()
  }
}

/**
 * Real OpenAI/Codex Responses SSE client ported from Mangi-11/Eta (`OpenAiResponsesProvider.kt`).
 */
internal class EtaCodexResponsesClient(
  private val okHttpClient: OkHttpClient,
) {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  fun execute(
    profile: AiAccountProfile,
    accessToken: String,
    accountId: String,
    request: AiCompletionRequest,
    onDelta: ((String) -> Unit)? = null,
    isCancelled: () -> Boolean = { false },
  ): AiCompletionResponse {
    val startNs = System.nanoTime()
    val inputArray = JSONArray()
    request.messages.forEach { msg ->
      val contentType = if (msg.role == "assistant") "output_text" else "input_text"
      inputArray.put(
        JSONObject()
          .put("role", msg.role)
          .put(
            "content",
            JSONArray().put(
              JSONObject()
                .put("type", contentType)
                .put("text", msg.content),
            ),
          ),
      )
    }

    val payload = JSONObject()
      .put("model", profile.model)
      .put("instructions", request.systemPrompt.ifBlank { "你是青简长篇中文小说创作助手，语言精准克制，严禁使用 emoji。" })
      .put("input", inputArray)
      .put("reasoning", JSONObject().put("effort", profile.thinkingLevel.apiValue))

    EtaSubscriptionResponsesRequest.configureCodexRequest(payload)

    val endpoint = profile.endpoint.ifBlank { EtaCodexOAuth.CODEX_RESPONSES_ENDPOINT }
    val headers = EtaSubscriptionResponsesRequest.buildHeaders(
      provider = AiProviderType.CHATGPT_CODEX_OAUTH,
      bearerToken = accessToken,
      accountId = accountId,
    )

    val httpReq = Request.Builder()
      .url(endpoint)
      .headers(headers)
      .post(payload.toString().toRequestBody(jsonMediaType))
      .build()

    okHttpClient.newCall(httpReq).execute().use { resp ->
      if (!resp.isSuccessful) {
        val errText = resp.body?.string().orEmpty()
        throw IOException("Codex Responses 接口返回 ${resp.code}: ${extractReadableError(errText)}")
      }
      val bodyStream = resp.body?.byteStream()
        ?: throw IOException("Codex Responses 接口未返回流内容")

      val streamedText = StringBuilder()
      var terminal: JSONObject? = null
      var terminalType: String? = null

      readEtaProviderSse(
        stream = bodyStream,
        isCancelled = isCancelled,
      ) { eventName, data ->
        if (data == "[DONE]") return@readEtaProviderSse false
        val eventObj = runCatching { JSONObject(data) }.getOrNull() ?: return@readEtaProviderSse true
        val type = eventObj.optString("type").ifBlank { eventName }

        if (type == "error") {
          val errMsg = eventObj.optJSONObject("error")?.optString("message")
            ?: eventObj.optString("message")
            ?: data.take(200)
          throw IOException("Codex 流错误：$errMsg")
        }

        when (type) {
          "response.output_text.delta" -> {
            val delta = eventObj.optString("delta")
            if (delta.isNotEmpty()) {
              streamedText.append(delta)
              onDelta?.invoke(delta)
            }
            true
          }

          "response.completed", "response.done", "response.incomplete", "response.failed" -> {
            val responseObj = eventObj.optJSONObject("response") ?: eventObj
            terminal = responseObj
            terminalType = if (type == "response.done") {
              when (responseObj.optString("status")) {
                "completed" -> "response.completed"
                "failed" -> "response.failed"
                "incomplete" -> "response.incomplete"
                else -> "response.completed"
              }
            } else {
              type
            }
            false
          }

          else -> true
        }
      }

      if (terminalType == "response.failed") {
        val errMsg = terminal?.optJSONObject("error")?.optString("message").orEmpty()
          .ifBlank { "模型响应失败" }
        throw IOException("Codex 响应失败：$errMsg")
      }

      val finalText = streamedText.toString().ifBlank { extractTextFromResponsesTerminal(terminal) }.trim()
      if (finalText.isBlank()) {
        throw IOException("Codex Responses 流未返回有效正文")
      }

      val usage = terminal?.optJSONObject("usage")
      val promptTokens = usage?.optInt("input_tokens", 0)?.takeIf { it > 0 }
        ?: estimateTokenCount(payload.toString())
      val completionTokens = usage?.optInt("output_tokens", 0)?.takeIf { it > 0 }
        ?: estimateTokenCount(finalText)
      val latencyMs = (System.nanoTime() - startNs) / 1_000_000L

      return AiCompletionResponse(
        text = finalText,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        latencyMs = latencyMs,
        modelUsed = profile.model,
        providerUsed = profile.provider.label,
      )
    }
  }

  private fun extractTextFromResponsesTerminal(terminal: JSONObject?): String {
    val output = terminal?.optJSONArray("output") ?: return ""
    val sb = StringBuilder()
    for (i in 0 until output.length()) {
      val item = output.optJSONObject(i) ?: continue
      val content = item.optJSONArray("content") ?: continue
      for (j in 0 until content.length()) {
        val part = content.optJSONObject(j) ?: continue
        val text = part.optString("text")
        if (text.isNotEmpty()) sb.append(text)
      }
    }
    return sb.toString()
  }
}

/**
 * Real OpenAI / DeepSeek Chat Completions SSE + JSON client ported from Mangi-11/Eta
 * (`OpenAiChatCompletionsProvider.kt`).
 */
internal class EtaOpenAiChatCompletionsClient(
  private val okHttpClient: OkHttpClient,
) {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  fun execute(
    profile: AiAccountProfile,
    request: AiCompletionRequest,
    onDelta: ((String) -> Unit)? = null,
    isCancelled: () -> Boolean = { false },
  ): AiCompletionResponse {
    val startNs = System.nanoTime()
    val messagesArray = JSONArray()
    if (request.systemPrompt.isNotBlank()) {
      messagesArray.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
    }
    request.messages.forEach { msg ->
      messagesArray.put(JSONObject().put("role", msg.role).put("content", msg.content))
    }

    val payload = JSONObject()
      .put("model", profile.model)
      .put("messages", messagesArray)
      .put("stream", true)
      .put("stream_options", JSONObject().put("include_usage", true))

    val modelLower = profile.model.lowercase()
    if (modelLower.startsWith("o1") || modelLower.startsWith("o3") || modelLower.startsWith("o4") || modelLower.contains("gpt-5")) {
      payload.put("reasoning_effort", profile.thinkingLevel.apiValue)
    }

    val headers = EtaSubscriptionResponsesRequest.buildHeaders(
      provider = profile.provider,
      bearerToken = profile.authCredential.trim(),
    )

    val httpReq = Request.Builder()
      .url(profile.endpoint.ifBlank { profile.provider.defaultEndpoint })
      .headers(headers)
      .post(payload.toString().toRequestBody(jsonMediaType))
      .build()

    okHttpClient.newCall(httpReq).execute().use { resp ->
      if (!resp.isSuccessful) {
        val errText = resp.body?.string().orEmpty()
        throw IOException("${profile.provider.label} 返回 ${resp.code}: ${extractReadableError(errText)}")
      }

      val contentType = resp.header("Content-Type").orEmpty().lowercase()
      val isSse = contentType.contains("text/event-stream")

      var promptTokens = 0
      var completionTokens = 0
      val textBuilder = StringBuilder()

      if (isSse) {
        val stream = resp.body?.byteStream() ?: throw IOException("接口未返回响应流")
        readEtaProviderSse(stream = stream, isCancelled = isCancelled) { _, data ->
          if (data == "[DONE]") return@readEtaProviderSse false
          val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return@readEtaProviderSse true
          chunk.optJSONObject("error")?.let { errObj ->
            throw IOException(errObj.optString("message").ifBlank { data.take(200) })
          }
          chunk.optJSONObject("usage")?.let { u ->
            if (u.optInt("prompt_tokens", 0) > 0) promptTokens = u.optInt("prompt_tokens")
            if (u.optInt("completion_tokens", 0) > 0) completionTokens = u.optInt("completion_tokens")
          }
          val choices = chunk.optJSONArray("choices")
          val deltaObj = choices?.optJSONObject(0)?.optJSONObject("delta")
          val deltaText = deltaObj?.optString("content").orEmpty()
          if (deltaText.isNotEmpty()) {
            textBuilder.append(deltaText)
            onDelta?.invoke(deltaText)
          }
          true
        }
      } else {
        val raw = resp.body?.string().orEmpty()
        val parsed = JSONObject(raw)
        val choices = parsed.optJSONArray("choices")
        val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        textBuilder.append(content)
        parsed.optJSONObject("usage")?.let { u ->
          promptTokens = u.optInt("prompt_tokens", 0)
          completionTokens = u.optInt("completion_tokens", 0)
        }
      }

      val finalText = textBuilder.toString().trim()
      if (finalText.isBlank()) {
        throw IOException("${profile.provider.label} 未返回有效文本内容")
      }

      val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
      return AiCompletionResponse(
        text = finalText,
        promptTokens = if (promptTokens > 0) promptTokens else estimateTokenCount(payload.toString()),
        completionTokens = if (completionTokens > 0) completionTokens else estimateTokenCount(finalText),
        latencyMs = latencyMs,
        modelUsed = profile.model,
        providerUsed = profile.provider.label,
      )
    }
  }
}

internal fun extractReadableError(raw: String): String {
  val parsed = runCatching { JSONObject(raw) }.getOrNull()
  val nested = parsed?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
  val top = parsed?.optString("message")?.takeIf { it.isNotBlank() }
  val detail = parsed?.optString("detail")?.takeIf { it.isNotBlank() }
  return (nested ?: top ?: detail ?: raw).take(260)
}

internal fun estimateTokenCount(text: String): Int = (text.length / 2.2).toInt().coerceAtLeast(1)
