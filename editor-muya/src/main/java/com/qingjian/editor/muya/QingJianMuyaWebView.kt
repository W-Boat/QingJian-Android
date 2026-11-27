package com.qingjian.editor.muya

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Strongly-typed bridge contract between Android Compose host and embedded Muya WebView.
 */
class QingJianBridge(
  private val onReady: () -> Unit,
  private val onSingleTap: (String) -> Unit,
  private val onDoubleTap: (String) -> Unit,
  private val onLongPress: (String) -> Unit,
  private val onSelectionChange: (List<String>) -> Unit,
  private val onDocChanged: (String) -> Unit,
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

  @JavascriptInterface
  fun onEditorReady() {
    mainHandler.post { onReady() }
  }

  @JavascriptInterface
  fun onBlockSingleTap(blockId: String) {
    mainHandler.post { onSingleTap(blockId) }
  }

  @JavascriptInterface
  fun onBlockDoubleTap(blockId: String) {
    mainHandler.post { onDoubleTap(blockId) }
  }

  @JavascriptInterface
  fun onBlockLongPress(blockId: String) {
    mainHandler.post { onLongPress(blockId) }
  }

  @JavascriptInterface
  fun onSelectionChanged(jsonArray: String) {
    val list = runCatching {
      @Suppress("UNCHECKED_CAST")
      (moshi.adapter(List::class.java).fromJson(jsonArray) as? List<*>)
        ?.mapNotNull { it?.toString() }
        .orEmpty()
    }.getOrDefault(emptyList())
    mainHandler.post { onSelectionChange(list) }
  }

  @JavascriptInterface
  fun onDocumentChanged(markdown: String) {
    mainHandler.post { onDocChanged(markdown) }
  }
}

class MuyaEditorController {
  internal var webView: WebView? = null

  fun insertSceneBreak() {
    webView?.evaluateJavascript("window.QingJianEditor && window.QingJianEditor.insertSceneBreak();", null)
  }

  fun scrollToAndHighlight(blockId: String) {
    val safeId = blockId.replace("'", "\\'")
    webView?.evaluateJavascript(
      "window.QingJianEditor && window.QingJianEditor.scrollToAndHighlightBlock('$safeId');",
      null,
    )
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QingJianMuyaWebView(
  chapterId: String,
  markdown: String,
  activeBlockId: String?,
  feedbackMap: Map<String, String>,
  annotationMap: Map<String, String>,
  foreshadowBlockIds: Set<String>,
  isDarkTheme: Boolean,
  accentHex: String,
  editorWidthDp: Int,
  sourceMode: Boolean,
  multiSelectMode: Boolean,
  controller: MuyaEditorController,
  onBlockSingleTap: (String) -> Unit,
  onBlockDoubleTap: (String) -> Unit,
  onBlockLongPress: (String) -> Unit,
  onSelectionChanged: (List<String>) -> Unit,
  onDocumentChanged: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val moshi = remember { Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build() }
  var isReady by remember { mutableStateOf(false) }

  val latestSingleTap by rememberUpdatedState(onBlockSingleTap)
  val latestDoubleTap by rememberUpdatedState(onBlockDoubleTap)
  val latestLongPress by rememberUpdatedState(onBlockLongPress)
  val latestSelection by rememberUpdatedState(onSelectionChanged)
  val latestDocChanged by rememberUpdatedState(onDocumentChanged)

  val bridge = remember {
    QingJianBridge(
      onReady = { isReady = true },
      onSingleTap = { latestSingleTap(it) },
      onDoubleTap = { latestDoubleTap(it) },
      onLongPress = { latestLongPress(it) },
      onSelectionChange = { latestSelection(it) },
      onDocChanged = { latestDocChanged(it) },
    )
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .build()

      WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isVerticalScrollBarEnabled = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addJavascriptInterface(bridge, "QingJianBridge")
        webViewClient = object : WebViewClient() {
          override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
          ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }
        loadUrl("https://appassets.androidplatform.net/assets/muya/index.html")
        controller.webView = this
      }
    },
    update = { wv ->
      controller.webView = wv
    },
  )

  LaunchedEffect(isReady, isDarkTheme, accentHex, editorWidthDp) {
    if (!isReady) return@LaunchedEffect
    val safeAccent = accentHex.replace("'", "")
    controller.webView?.evaluateJavascript(
      "window.QingJianEditor && window.QingJianEditor.setTheme($isDarkTheme, '$safeAccent', $editorWidthDp);",
      null,
    )
  }

  LaunchedEffect(isReady, sourceMode) {
    if (!isReady) return@LaunchedEffect
    controller.webView?.evaluateJavascript(
      "window.QingJianEditor && window.QingJianEditor.setSourceMode($sourceMode);",
      null,
    )
  }

  LaunchedEffect(isReady, multiSelectMode) {
    if (!isReady) return@LaunchedEffect
    controller.webView?.evaluateJavascript(
      "window.QingJianEditor && window.QingJianEditor.setMultiSelectMode($multiSelectMode);",
      null,
    )
  }

  LaunchedEffect(isReady, chapterId, markdown, activeBlockId, feedbackMap, annotationMap, foreshadowBlockIds) {
    if (!isReady) return@LaunchedEffect
    val payload = linkedMapOf<String, Any?>(
      "chapterId" to chapterId,
      "markdown" to markdown,
      "activeBlockId" to activeBlockId,
      "feedback" to feedbackMap,
      "annotations" to annotationMap,
      "foreshadowBlockIds" to foreshadowBlockIds.toList(),
    )
    val payloadJson = moshi.adapter(Map::class.java).toJson(payload)
    val jsEscaped = moshi.adapter(String::class.java).toJson(payloadJson)
    controller.webView?.evaluateJavascript(
      "window.QingJianEditor && window.QingJianEditor.loadDocument($jsEscaped);",
      null,
    )
  }

  DisposableEffect(Unit) {
    onDispose {
      controller.webView?.destroy()
      controller.webView = null
    }
  }
}
