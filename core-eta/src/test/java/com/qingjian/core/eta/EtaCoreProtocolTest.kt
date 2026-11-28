package com.qingjian.core.eta

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaCoreProtocolTest {

  @Test
  fun codexSubscriptionRequestRejectsUnsupportedFieldsAndAddsEncryptedReasoning() {
    val valid = JSONObject().put("model", "gpt-5-codex")
    EtaSubscriptionResponsesRequest.configureCodexRequest(valid)
    assertEquals(false, valid.getBoolean("store"))
    assertEquals(true, valid.getBoolean("stream"))
    assertEquals("reasoning.encrypted_content", valid.getJSONArray("include").getString(0))

    val invalid = JSONObject().put("model", "gpt-5-codex").put("temperature", 0.7)
    var rejected = false
    try {
      EtaSubscriptionResponsesRequest.configureCodexRequest(invalid)
    } catch (_: IllegalArgumentException) {
      rejected = true
    }
    assertTrue("Codex protocol must reject temperature", rejected)
  }

  @Test
  fun sseReaderParsesResponsesDeltaAndStopsOnTerminal() {
    val ssePayload = """
      event: response.output_text.delta
      data: {"type":"response.output_text.delta","delta":"雪落"}

      event: response.output_text.delta
      data: {"type":"response.output_text.delta","delta":"白塔。"}

      event: response.completed
      data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":12,"output_tokens":6}}}

      event: response.output_text.delta
      data: {"type":"response.output_text.delta","delta":"不应读取"}
    """.trimIndent()

    val collected = StringBuilder()
    var sawCompleted = false
    readEtaProviderSse(ByteArrayInputStream(ssePayload.toByteArray(Charsets.UTF_8))) { event, data ->
      val obj = JSONObject(data)
      when (obj.optString("type").ifBlank { event }) {
        "response.output_text.delta" -> {
          collected.append(obj.optString("delta"))
          true
        }
        "response.completed" -> {
          sawCompleted = true
          false
        }
        else -> true
      }
    }

    assertEquals("雪落白塔。", collected.toString())
    assertTrue(sawCompleted)
  }

  @Test
  fun codexOAuthExtractsAccountIdAndBuildsEtaAuthorizeUrl() {
    val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
      """{"email":"author@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct_real_9981"}}""".toByteArray(),
    )
    val fakeSig = Base64.getUrlEncoder().withoutPadding().encodeToString("signature".toByteArray())
    val jwt = "$header.$payload.$fakeSig"

    assertEquals("acct_real_9981", EtaCodexOAuth.extractAccountId(jwt))
    assertEquals("author@example.com", EtaCodexOAuth.extractEmail(jwt))

    val client = EtaOAuthClient()
    val session = client.createPkceSession()
    assertTrue(session.authorizationUrl.contains("client_id=${EtaCodexOAuth.CLIENT_ID}"))
    assertTrue(session.authorizationUrl.contains("originator=eta"))
    assertTrue(session.authorizationUrl.contains("codex_cli_simplified_flow=true"))
    assertTrue(session.authorizationUrl.contains("code_challenge_method=S256"))
  }

  @Test
  fun runtimeThrowsAuthRequiredInsteadOfFakingOutput() = runTest {
    val runtime = EtaAgentRuntime()
    val unconfigured = AiAccountProfile(
      label = "未配置账号",
      provider = AiProviderType.DEEPSEEK,
      authCredential = "",
    )
    var threwAuthRequired = false
    try {
      runtime.complete(
        profile = unconfigured,
        request = AiCompletionRequest(
          systemPrompt = "test",
          messages = listOf(ChatMessage("user", "hello")),
        ),
      )
    } catch (_: EtaAuthRequiredException) {
      threwAuthRequired = true
    }
    assertTrue("Runtime must throw EtaAuthRequiredException when unconfigured", threwAuthRequired)
  }
}
