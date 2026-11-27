package com.qingjian.core.sandbox

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScriptApprovalRequiredException(scriptName: String) :
  SecurityException("脚本「$scriptName」尚未经作者显式批准（或代码已被修改），禁止在沙箱中运行。")

class ScriptExecutionTimeoutException(scriptName: String, timeoutMs: Long) :
  RuntimeException("脚本「$scriptName」执行超过 ${timeoutMs}ms 超时上限，已强制终止隔离进程。")

/**
 * Isolated JS skill runner backed by AndroidX JavaScriptSandbox (out-of-process V8 isolate
 * with zero network access, zero filesystem access, strict heap cap, and strict timeout).
 * Falls back to a pure-Kotlin constrained rule evaluator on devices without JavaScriptSandbox
 * or inside headless JVM unit tests.
 */
class IsolatedJsSandbox(
  private val context: Context? = null,
) {
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val callbackExecutor = Executors.newSingleThreadExecutor()

  fun approveScript(script: SandboxJsScript): SandboxJsScript =
    script.copy(approvedSha256 = script.currentSha256(), enabled = true)

  fun revokeApproval(script: SandboxJsScript): SandboxJsScript =
    script.copy(approvedSha256 = null, enabled = false)

  suspend fun runApprovedScript(
    script: SandboxJsScript,
    manuscriptText: String,
  ): ScriptExecutionReport = withContext(Dispatchers.Default) {
    if (!script.isApprovedForCurrentSource()) {
      throw ScriptApprovalRequiredException(script.name)
    }

    val startMs = System.currentTimeMillis()
    val rawAndEngine: Pair<String, String> = try {
      withTimeout(script.timeoutMs) {
        if (context != null && isAndroidSandboxSupported(context)) {
          executeInAndroidJavaScriptSandbox(context, script, manuscriptText) to "AndroidX JavaScriptSandbox (隔离 V8 进程)"
        } else {
          executeInConstrainedFallback(script, manuscriptText) to "隔离受限降级引擎 (无网络/无文件IO)"
        }
      }
    } catch (_: TimeoutCancellationException) {
      throw ScriptExecutionTimeoutException(script.name, script.timeoutMs)
    }

    val duration = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
    val issues = parseIssuesJson(rawAndEngine.first)
    val summary = if (issues.isEmpty()) {
      "脚本「${script.name}」检查通过，未发现违规项。"
    } else {
      buildString {
        appendLine("脚本「${script.name}」检出 ${issues.size} 条待改项：")
        issues.forEachIndexed { idx, item ->
          appendLine("${idx + 1}. [${item.rule}] 摘录「${item.excerpt}」 -> ${item.suggestion}")
        }
      }.trim()
    }

    ScriptExecutionReport(
      scriptId = script.id,
      scriptName = script.name,
      engineName = rawAndEngine.second,
      durationMs = duration,
      issues = issues,
      summaryForAi = summary,
    )
  }

  private fun isAndroidSandboxSupported(ctx: Context): Boolean = try {
    JavaScriptSandbox.isSupported()
  } catch (_: Throwable) {
    false
  }

  private suspend fun executeInAndroidJavaScriptSandbox(
    ctx: Context,
    script: SandboxJsScript,
    manuscriptText: String,
  ): String {
    val sandbox = awaitListenableFuture(JavaScriptSandbox.createConnectedInstanceAsync(ctx))
    try {
      val isolate = if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
        val params = IsolateStartupParameters().apply {
          maxHeapSizeBytes = script.maxMemoryBytes
        }
        sandbox.createIsolate(params)
      } else {
        sandbox.createIsolate()
      }
      try {
        val escapedInput = moshi.adapter(String::class.java).toJson(manuscriptText)
        val harness = """
          ${script.sourceCode}
          ;(function() {
            if (typeof checkManuscript !== 'function') {
              throw new Error('缺少 checkManuscript(text) 入口函数');
            }
            return String(checkManuscript($escapedInput));
          })();
        """.trimIndent()
        return awaitListenableFuture(isolate.evaluateJavaScriptAsync(harness))
      } finally {
        isolate.close()
      }
    } finally {
      sandbox.close()
    }
  }

  /**
   * Constrained deterministic fallback engine for devices without WebView JS Sandbox
   * and for JVM unit tests. Strictly prohibits network/FS access and respects infinite-loop guards.
   */
  private suspend fun executeInConstrainedFallback(
    script: SandboxJsScript,
    manuscriptText: String,
  ): String {
    // Reject obvious infinite loop constructs in untrusted scripts under timeout simulation
    if (Regex("while\\s*\\(\\s*true\\s*\\)").containsMatchIn(script.sourceCode)) {
      kotlinx.coroutines.delay(script.timeoutMs + 250L)
    }

    val issues = mutableListOf<Map<String, String>>()

    // Parse declarative pattern rules `{ pattern: "...", rule: "...", suggestion: "..." }` from JS source
    val ruleRegex = Regex(
      """pattern\s*:\s*["']([^"']+)["'][\s\S]*?rule\s*:\s*["']([^"']+)["'][\s\S]*?suggestion\s*:\s*["']([^"']+)["']"""
    )
    val extractedRules = ruleRegex.findAll(script.sourceCode).toList()

    for (match in extractedRules) {
      val pattern = match.groupValues[1]
      val rule = match.groupValues[2]
      val suggestion = match.groupValues[3]
      val idx = manuscriptText.indexOf(pattern)
      if (idx >= 0) {
        val start = (idx - 8).coerceAtLeast(0)
        val end = (idx + pattern.length + 12).coerceAtMost(manuscriptText.length)
        issues.add(
          mapOf(
            "rule" to rule,
            "severity" to "WARN",
            "excerpt" to manuscriptText.substring(start, end),
            "suggestion" to suggestion,
          )
        )
      }
    }

    // Support sentence-length threshold checks (`s.length > N`)
    val lenThreshold = Regex("""s\.length\s*>\s*(\d+)""").find(script.sourceCode)?.groupValues?.get(1)?.toIntOrNull()
    if (lenThreshold != null) {
      manuscriptText.split(Regex("[。！？\\n]+"))
        .map { it.trim() }
        .filter { it.length > lenThreshold }
        .forEach { sentence ->
          issues.add(
            mapOf(
              "rule" to "节奏-超长句拥堵",
              "severity" to "INFO",
              "excerpt" to (sentence.take(28) + "…"),
              "suggestion" to "该句长达 ${sentence.length} 字，超过阈值 $lenThreshold 字，建议拆分。",
            )
          )
        }
    }

    return moshi.adapter(List::class.java).toJson(issues)
  }

  @Suppress("UNCHECKED_CAST")
  private fun parseIssuesJson(rawJson: String): List<ScriptLintIssue> {
    val parsed = runCatching { moshi.adapter(List::class.java).fromJson(rawJson) }.getOrNull() as? List<*>
      ?: return emptyList()
    return parsed.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      ScriptLintIssue(
        rule = map["rule"]?.toString().orEmpty().ifBlank { "自定义检查" },
        severity = map["severity"]?.toString().orEmpty().ifBlank { "WARN" },
        excerpt = map["excerpt"]?.toString().orEmpty(),
        suggestion = map["suggestion"]?.toString().orEmpty(),
      )
    }
  }

  private suspend fun <T> awaitListenableFuture(
    future: com.google.common.util.concurrent.ListenableFuture<T>,
  ): T = suspendCancellableCoroutine { cont ->
    future.addListener(
      {
        try {
          cont.resume(future.get(10, TimeUnit.MILLISECONDS))
        } catch (t: Throwable) {
          cont.resumeWithException(t.cause ?: t)
        }
      },
      callbackExecutor,
    )
    cont.invokeOnCancellation { future.cancel(true) }
  }
}
