package com.qingjian.core.eta

import com.nimbusds.jwt.SignedJWT
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Ported from Mangi-11/Eta (`agent/auth/CodexOAuth.kt`, `LoopbackAuthorizationReceiver.kt`,
 * `ProviderOAuthClient.kt`) with Root/Xposed/OEM hooks removed.
 */
open class EtaAuthException(
  val code: String,
  override val message: String,
  cause: Throwable? = null,
) : IOException(message, cause)

class EtaAuthRequiredException(
  message: String,
) : EtaAuthException("auth_required", message)

data class EtaOAuthTokenSet(
  val accessToken: String,
  val refreshToken: String,
  val idToken: String? = null,
  val expiresAtMillis: Long,
  val accountId: String,
  val email: String = "",
) {
  fun isExpiredOrNearExpiry(nowMillis: Long = System.currentTimeMillis(), skewMillis: Long = 120_000L): Boolean {
    return expiresAtMillis > 0L && nowMillis + skewMillis >= expiresAtMillis
  }
}

object EtaCodexOAuth {
  const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
  const val CALLBACK_PORT = 1455
  const val ORIGINATOR = "eta"
  const val SCOPE = "openid profile email offline_access"
  const val ISSUER = "https://auth.openai.com"
  const val AUTHORIZE_ENDPOINT = "https://auth.openai.com/oauth/authorize"
  const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
  const val CODEX_RESPONSES_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"

  val TERMINAL_REFRESH_ERRORS = setOf(
    "invalid_grant",
    "invalid_refresh_token",
    "token_expired",
    "refresh_token_expired",
    "refresh_token_invalidated",
    "refresh_token_reused",
  )

  /**
   * Extracts `chatgpt_account_id` from the OpenAI OAuth JWT claim
   * `"https://api.openai.com/auth"`, matching Mangi-11/Eta `CodexOAuth.accountId`.
   */
  fun extractAccountId(accessToken: String): String = try {
    require(accessToken.length in 1..65_536)
    val claims = SignedJWT.parse(accessToken).jwtClaimsSet
    val authClaim = claims.getJSONObjectClaim("https://api.openai.com/auth")
    (authClaim?.get("chatgpt_account_id") as? String)
      ?.takeIf { it.isNotBlank() && it.length <= 512 }
      ?: throw IllegalArgumentException("missing chatgpt_account_id")
  } catch (err: Exception) {
    throw EtaAuthException(
      code = "codex_account_missing",
      message = "Codex 凭据缺少工作区信息 (chatgpt_account_id)，请重新完成 ChatGPT OAuth 登录。",
      cause = err,
    )
  }

  fun extractEmail(token: String?): String {
    if (token.isNullOrBlank()) return ""
    return runCatching {
      val claims = SignedJWT.parse(token).jwtClaimsSet
      val direct = claims.getStringClaim("email")
      if (!direct.isNullOrBlank()) return@runCatching direct
      val profile = claims.getJSONObjectClaim("https://api.openai.com/profile")
      (profile?.get("email") as? String).orEmpty()
    }.getOrDefault("")
  }
}

/**
 * Localhost loopback callback receiver (`http://127.0.0.1:1455/auth/callback`)
 * ported from Mangi-11/Eta `LoopbackAuthorizationReceiver.kt`.
 */
class EtaLoopbackAuthorizationReceiver(
  port: Int = EtaCodexOAuth.CALLBACK_PORT,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val server: ServerSocket = try {
    ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
  } catch (_: IOException) {
    // Fall back to an ephemeral port only if 1455 is busy
    ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
  }

  val localPort: Int get() = server.localPort
  val redirectUri: String = "http://localhost:${server.localPort}/auth/callback"

  fun awaitCode(
    expectedState: String,
    timeoutMillis: Long = 600_000L,
    isCancelled: () -> Boolean = { false },
  ): String {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    try {
      while (System.nanoTime() < deadline) {
        if (isCancelled() || closed.get()) {
          throw CancellationException("OAuth 登录已取消")
        }
        val socket = try {
          server.accept()
        } catch (_: SocketTimeoutException) {
          continue
        }
        socket.use {
          try {
            val target = readHttpGetTarget(it)
            val code = validateCallback(target, expectedState)
            runCatching {
              respondHtml(it, "200 OK", "授权已返回，请回到青简 Android 完成登录。")
            }
            return code
          } catch (error: EtaAuthException) {
            runCatching {
              respondHtml(it, "400 Bad Request", "授权未完成（${error.message}），请回到青简重试。")
            }
            if (error.code != "oauth_callback_invalid") throw error
          } catch (_: IOException) {
            if (isCancelled() || closed.get()) throw CancellationException("OAuth 登录已取消")
          }
        }
      }
      throw EtaAuthException("oauth_login_expired", "浏览器 OAuth 授权已超时，请重试。")
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (auth: EtaAuthException) {
      throw auth
    } catch (io: IOException) {
      if (isCancelled() || closed.get()) throw CancellationException("OAuth 登录已取消")
      throw EtaAuthException("oauth_callback_unavailable", "本地回环端口监听中断：${io.message}", io)
    } finally {
      close()
    }
  }

  internal fun validateCallback(target: String, expectedState: String): String {
    fun invalid(): Nothing = throw EtaAuthException("oauth_callback_invalid", "登录回调参数无效")
    if (!target.startsWith("/auth/callback?")) invalid()
    val url = ("http://127.0.0.1:${server.localPort}$target").toHttpUrlOrNull() ?: invalid()
    if (url.encodedPath != "/auth/callback" || url.fragment != null) invalid()

    fun singleParam(name: String): String? {
      val values = url.queryParameterValues(name)
      if (values.size > 1) invalid()
      return values.singleOrNull()
    }

    val receivedState = singleParam("state") ?: invalid()
    if (!MessageDigest.isEqual(expectedState.toByteArray(Charsets.UTF_8), receivedState.toByteArray(Charsets.UTF_8))) {
      invalid()
    }
    singleParam("iss")?.let { if (it != EtaCodexOAuth.ISSUER) invalid() }
    singleParam("error")?.let { err ->
      val desc = singleParam("error_description").orEmpty()
      throw EtaAuthException(
        "oauth_access_denied",
        if (desc.isNotBlank()) "登录未获授权：$err ($desc)" else "登录未获授权：$err",
      )
    }
    return singleParam("code")?.takeIf { it.isNotBlank() && it.length <= 8_192 } ?: invalid()
  }

  private fun readHttpGetTarget(socket: Socket): String {
    socket.soTimeout = 1_500
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    val bytes = ByteArrayOutputStream()
    val input = socket.getInputStream()
    var ending = 0
    while (bytes.size() < 16_384 && System.nanoTime() < deadline) {
      val next = input.read()
      if (next < 0) break
      bytes.write(next)
      ending = (ending shl 8) or next
      if (ending == 0x0d0a0d0a) {
        val lines = bytes.toString(Charsets.US_ASCII.name()).split("\r\n")
        val request = lines.first().split(' ')
        val hosts = lines.drop(1).filter { it.startsWith("Host:", ignoreCase = true) }
        val hostValue = hosts.singleOrNull()?.substringAfter(':')?.trim().orEmpty()
        val allowedHosts = setOf("127.0.0.1:${server.localPort}", "localhost:${server.localPort}")
        if (request.size == 3 &&
          request[0] == "GET" &&
          request[2] in setOf("HTTP/1.1", "HTTP/1.0") &&
          hostValue in allowedHosts
        ) {
          return request[1]
        }
        break
      }
    }
    throw EtaAuthException("oauth_callback_invalid", "登录回调请求头无效")
  }

  private fun respondHtml(socket: Socket, status: String, message: String) {
    val body = "<!doctype html><meta charset=utf-8><title>青简 Android</title><body style='font-family:sans-serif;padding:32px;background:#111;color:#eee'><h3>青简 Android</h3><p>$message</p></body>".toByteArray(Charsets.UTF_8)
    val headers = "HTTP/1.1 $status\r\n" +
      "Content-Type: text/html; charset=utf-8\r\n" +
      "Content-Length: ${body.size}\r\n" +
      "Cache-Control: no-store\r\n" +
      "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
      "Connection: close\r\n\r\n"
    socket.getOutputStream().apply {
      write(headers.toByteArray(Charsets.US_ASCII))
      write(body)
      flush()
    }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      runCatching { server.close() }
    }
  }
}

data class EtaPkceSession(
  val codeVerifier: String,
  val codeChallenge: String,
  val state: String,
  val nonce: String,
  val redirectUri: String,
  val authorizationUrl: String,
)

/**
 * Real OpenAI / ChatGPT Codex OAuth 2.0 PKCE + token exchange + refresh client,
 * ported from Mangi-11/Eta `ProviderOAuthClient.kt`.
 */
class EtaOAuthClient(
  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build(),
) {
  private val random = SecureRandom()

  fun createPkceSession(redirectUri: String = "http://localhost:${EtaCodexOAuth.CALLBACK_PORT}/auth/callback"): EtaPkceSession {
    val verifier = randomBase64Url(32)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    val state = randomBase64Url(24)
    val nonce = randomBase64Url(24)
    val url = buildAuthorizationUrl(
      redirectUri = redirectUri,
      state = state,
      nonce = nonce,
      codeChallenge = challenge,
    )
    return EtaPkceSession(
      codeVerifier = verifier,
      codeChallenge = challenge,
      state = state,
      nonce = nonce,
      redirectUri = redirectUri,
      authorizationUrl = url,
    )
  }

  fun buildAuthorizationUrl(
    redirectUri: String,
    state: String,
    nonce: String,
    codeChallenge: String,
  ): String {
    return EtaCodexOAuth.AUTHORIZE_ENDPOINT.toHttpUrl().newBuilder()
      .addQueryParameter("client_id", EtaCodexOAuth.CLIENT_ID)
      .addQueryParameter("response_type", "code")
      .addQueryParameter("redirect_uri", redirectUri)
      .addQueryParameter("scope", EtaCodexOAuth.SCOPE)
      .addQueryParameter("state", state)
      .addQueryParameter("nonce", nonce)
      .addQueryParameter("code_challenge_method", "S256")
      .addQueryParameter("code_challenge", codeChallenge)
      .addQueryParameter("id_token_add_organizations", "true")
      .addQueryParameter("codex_cli_simplified_flow", "true")
      .addQueryParameter("originator", EtaCodexOAuth.ORIGINATOR)
      .build()
      .toString()
  }

  /**
   * Executes the full real loopback browser OAuth flow:
   * 1. Binds `127.0.0.1:1455` (`EtaLoopbackAuthorizationReceiver`)
   * 2. Invokes [onOpenBrowser] with the real `https://auth.openai.com/oauth/authorize` URL
   * 3. Awaits the real callback `code` and verifies `state`
   * 4. Exchanges the code at `https://auth.openai.com/oauth/token`
   * 5. Extracts `chatgpt_account_id` and email from the returned JWT
   */
  suspend fun signInWithBrowser(
    onOpenBrowser: (authorizeUrl: String) -> Unit,
  ): EtaOAuthTokenSet = withContext(Dispatchers.IO) {
    val ctx = currentCoroutineContext()
    EtaLoopbackAuthorizationReceiver(EtaCodexOAuth.CALLBACK_PORT).use { receiver ->
      val session = createPkceSession(redirectUri = receiver.redirectUri)
      onOpenBrowser(session.authorizationUrl)
      val code = receiver.awaitCode(
        expectedState = session.state,
        isCancelled = { !ctx.isActive },
      )
      exchangeAuthorizationCode(
        code = code,
        codeVerifier = session.codeVerifier,
        redirectUri = session.redirectUri,
      )
    }
  }

  suspend fun exchangeAuthorizationCode(
    code: String,
    codeVerifier: String,
    redirectUri: String,
  ): EtaOAuthTokenSet = withContext(Dispatchers.IO) {
    val form = FormBody.Builder()
      .add("grant_type", "authorization_code")
      .add("client_id", EtaCodexOAuth.CLIENT_ID)
      .add("code", code)
      .add("code_verifier", codeVerifier)
      .add("redirect_uri", redirectUri)
      .build()
    val json = postTokenForm(form)
    parseTokenResponse(json, fallbackRefreshToken = "")
  }

  suspend fun refreshToken(refreshToken: String): EtaOAuthTokenSet = withContext(Dispatchers.IO) {
    if (refreshToken.isBlank()) {
      throw EtaAuthRequiredException("Codex 刷新令牌为空，请重新登录 ChatGPT 账号。")
    }
    val form = FormBody.Builder()
      .add("grant_type", "refresh_token")
      .add("client_id", EtaCodexOAuth.CLIENT_ID)
      .add("refresh_token", refreshToken)
      .build()
    val json = postTokenForm(form)
    parseTokenResponse(json, fallbackRefreshToken = refreshToken)
  }

  private fun postTokenForm(form: FormBody): JSONObject {
    val req = Request.Builder()
      .url(EtaCodexOAuth.TOKEN_ENDPOINT)
      .header("User-Agent", "Eta")
      .header("Accept", "application/json")
      .post(form)
      .build()
    okHttpClient.newCall(req).execute().use { resp ->
      val bodyText = resp.body?.string().orEmpty()
      val json = runCatching { JSONObject(bodyText) }.getOrNull()
      if (!resp.isSuccessful) {
        val errCode = json?.optString("error").orEmpty().ifBlank { "http_${resp.code}" }
        val errDesc = json?.optString("error_description").orEmpty().ifBlank { bodyText.take(200) }
        val userMessage = if (errCode in EtaCodexOAuth.TERMINAL_REFRESH_ERRORS) {
          "ChatGPT OAuth 登录态已过期（$errCode），请重新完成浏览器授权登录。"
        } else {
          "OAuth 令牌接口返回 ${resp.code} ($errCode): $errDesc"
        }
        throw EtaAuthException(errCode, userMessage)
      }
      return json ?: throw EtaAuthException("oauth_invalid_json", "OAuth 令牌接口返回了无效 JSON")
    }
  }

  internal fun parseTokenResponse(json: JSONObject, fallbackRefreshToken: String): EtaOAuthTokenSet {
    val accessToken = json.optString("access_token").trim()
    if (accessToken.isBlank()) {
      throw EtaAuthException("oauth_missing_access_token", "OAuth 响应缺少 access_token")
    }
    val tokenType = json.optString("token_type").trim()
    if (tokenType.isNotEmpty() && !tokenType.equals("Bearer", ignoreCase = true)) {
      throw EtaAuthException("oauth_invalid_token_type", "不支持的令牌类型：$tokenType")
    }
    val refresh = json.optString("refresh_token")
      .takeIf { it.isNotBlank() && it != "null" }
      ?: fallbackRefreshToken
    val idToken = json.optString("id_token").takeIf { it.isNotBlank() && it != "null" }
    val expiresInSec = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
    val expiresAtMillis = System.currentTimeMillis() + expiresInSec * 1000L
    val accountId = EtaCodexOAuth.extractAccountId(accessToken)
    val email = EtaCodexOAuth.extractEmail(idToken).ifBlank { EtaCodexOAuth.extractEmail(accessToken) }

    return EtaOAuthTokenSet(
      accessToken = accessToken,
      refreshToken = refresh,
      idToken = idToken,
      expiresAtMillis = expiresAtMillis,
      accountId = accountId,
      email = email,
    )
  }

  private fun randomBase64Url(numBytes: Int): String {
    val bytes = ByteArray(numBytes).also { random.nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
