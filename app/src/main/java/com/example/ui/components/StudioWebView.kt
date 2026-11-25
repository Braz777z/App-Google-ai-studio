package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.StudioSection
import com.example.notifications.StudioNotificationHelper
import com.example.ui.WebCommand
import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

private const val DESKTOP_CHROME_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Safari/537.36"

private val ioExecutor = Executors.newCachedThreadPool()

private val AGENT_THINKING_OBSERVER_JS = """
(function() {
  if (window.__studioPortalObserverInstalled) return;
  window.__studioPortalObserverInstalled = true;

  var mainHandler = {
    isThinking: false,
    startedAt: 0,
    finishTimer: null,
    checkScheduled: false
  };

  function isElementVisible(el) {
    if (!el) return false;
    var style = window.getComputedStyle(el);
    if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
      return false;
    }
    var rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function hasStopButton() {
    var buttons = document.querySelectorAll('button, [role="button"]');
    for (var i = 0; i < buttons.length; i++) {
      var btn = buttons[i];
      if (!isElementVisible(btn)) continue;
      var label = (btn.getAttribute('aria-label') || '') + ' ' + (btn.getAttribute('mattooltip') || '');
      var lowerLabel = label.toLowerCase();
      if (lowerLabel.indexOf('stop') !== -1 ||
          lowerLabel.indexOf('arrêter') !== -1 ||
          lowerLabel.indexOf('cancel') !== -1) {
        return true;
      }
      var icon = btn.querySelector('mat-icon, .material-icons, .material-symbols-outlined, .material-symbols-rounded');
      if (icon) {
        var iconText = (icon.textContent || '').trim().toLowerCase();
        if (iconText === 'stop' || iconText === 'stop_circle') {
          return true;
        }
      }
      var text = (btn.innerText || '').trim().toLowerCase();
      if (text === 'stop' || text === 'stop generating' || text === 'arrêter') {
        return true;
      }
    }
    return false;
  }

  function hasActiveThinkingIndicator() {
    var selectors = [
      '[aria-busy="true"]',
      'ms-chat-turn .thinking-indicator',
      'ms-thought-chunk .thinking',
      '.run-button.running',
      '.generating-indicator',
      'ms-run-button mat-progress-spinner',
      'ms-chat-session mat-progress-bar'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el && isElementVisible(el)) return true;
    }
    return false;
  }

  function extractLatestPreview() {
    try {
      var candidates = document.querySelectorAll('ms-chat-turn, [data-turn-role="model"], .model-prompt-container');
      if (!candidates || candidates.length === 0) return '';
      var last = candidates[candidates.length - 1];
      var text = (last.innerText || '').replace(/\s+/g, ' ').trim();
      if (!text) return '';
      return text.length > 110 ? text.slice(0, 107) + '…' : text;
    } catch (e) {
      return '';
    }
  }

  function evaluateState() {
    mainHandler.checkScheduled = false;
    var busy = hasStopButton() || hasActiveThinkingIndicator();

    if (busy) {
      if (mainHandler.finishTimer) {
        clearTimeout(mainHandler.finishTimer);
        mainHandler.finishTimer = null;
      }
      if (!mainHandler.isThinking) {
        mainHandler.isThinking = true;
        mainHandler.startedAt = Date.now();
        if (window.StudioPortalBridge && window.StudioPortalBridge.onAgentThinkingStarted) {
          window.StudioPortalBridge.onAgentThinkingStarted();
        }
      }
    } else if (mainHandler.isThinking && !mainHandler.finishTimer) {
      mainHandler.finishTimer = setTimeout(function() {
        mainHandler.finishTimer = null;
        if (hasStopButton() || hasActiveThinkingIndicator()) {
          return;
        }
        var elapsedMs = Date.now() - mainHandler.startedAt;
        mainHandler.isThinking = false;
        if (elapsedMs >= 1000) {
          var seconds = Math.max(1, Math.round(elapsedMs / 1000));
          var preview = extractLatestPreview();
          if (window.StudioPortalBridge && window.StudioPortalBridge.onAgentThinkingFinished) {
            window.StudioPortalBridge.onAgentThinkingFinished(seconds, preview);
          }
        }
      }, 1200);
    }
  }

  function scheduleCheck() {
    if (mainHandler.checkScheduled) return;
    mainHandler.checkScheduled = true;
    setTimeout(evaluateState, 220);
  }

  var observer = new MutationObserver(scheduleCheck);
  if (document.body) {
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['aria-label', 'aria-busy', 'class', 'disabled']
    });
  }
  setInterval(evaluateState, 700);
})();
""".trimIndent()

private data class PendingSafExport(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String
)

@Keep
private class StudioPortalBridge(
    private val context: Context,
    private val onSaved: (String) -> Unit,
    private val onSafExportRequested: (PendingSafExport) -> Unit,
    private val onDownloadFailed: (String) -> Unit,
    private val onThinkingStarted: () -> Unit,
    private val onThinkingFinished: (Int, String?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Keep
    @JavascriptInterface
    fun saveBase64File(base64Data: String, mimeType: String?, fileName: String?) {
        ioExecutor.execute {
            try {
                val parsedMime = extractMimeFromDataUrl(base64Data)
                    ?: mimeType?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val cleanBase64 = base64Data.substringAfter("base64,", base64Data)
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                saveBytesToDownloads(
                    context = context,
                    bytes = bytes,
                    mimeType = parsedMime,
                    rawFileName = fileName,
                    mainHandler = mainHandler,
                    onSaved = onSaved,
                    onSafExportRequested = onSafExportRequested,
                    onFailed = { onDownloadFailed(context.getString(R.string.download_failed)) }
                )
            } catch (_: Exception) {
                mainHandler.post {
                    onDownloadFailed(context.getString(R.string.download_failed))
                }
            }
        }
    }

    @Keep
    @JavascriptInterface
    fun onDownloadError(reason: String?) {
        mainHandler.post {
            val msg = if (reason == "cross_origin") {
                context.getString(R.string.download_cross_origin_hint)
            } else {
                context.getString(R.string.download_failed)
            }
            onDownloadFailed(msg)
        }
    }

    @Keep
    @JavascriptInterface
    fun onAgentThinkingStarted() {
        mainHandler.post { onThinkingStarted() }
    }

    @Keep
    @JavascriptInterface
    fun onAgentThinkingFinished(durationSeconds: Int, preview: String?) {
        mainHandler.post { onThinkingFinished(durationSeconds, preview) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StudioWebView(
    initialUrl: String,
    isDesktopMode: Boolean,
    textZoomPercent: Int,
    isDarkTheme: Boolean,
    webCommands: SharedFlow<WebCommand>,
    onPageStarted: (String) -> Unit,
    onPageFinished: (url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onNavigationStateChanged: (canGoBack: Boolean, canGoForward: Boolean, url: String?) -> Unit,
    onMainFrameError: (String?) -> Unit,
    onAgentThinkingStarted: () -> Unit,
    onAgentThinkingFinished: (durationSeconds: Int, preview: String?) -> Unit,
    onFeedbackMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webViewRecoveryKey by remember { mutableIntStateOf(0) }
    var appliedDesktopMode by remember { mutableStateOf(isDesktopMode) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingSingleFileOnly by remember { mutableStateOf(false) }
    var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var pendingSafExport by remember { mutableStateOf<PendingSafExport?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var popupTitle by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback != null) {
            if (uris.isEmpty()) {
                callback.onReceiveValue(null)
            } else {
                val selected = if (pendingSingleFileOnly) arrayOf(uris.first()) else uris.toTypedArray()
                callback.onReceiveValue(selected)
            }
        }
    }

    val safCreateDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        val export = pendingSafExport
        pendingSafExport = null
        if (destUri != null && export != null) {
            ioExecutor.execute {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(export.bytes)
                    }
                    StudioNotificationHelper.showDownloadCompletedNotification(
                        context = context,
                        fileName = export.fileName,
                        contentUri = destUri,
                        mimeType = export.mimeType
                    )
                    mainHandler.post {
                        onFeedbackMessage(context.getString(R.string.download_saved, export.fileName))
                    }
                } catch (_: Exception) {
                    mainHandler.post {
                        onFeedbackMessage(context.getString(R.string.download_failed))
                    }
                }
            }
        }
    }

    val triggerSafExport: (PendingSafExport) -> Unit = remember(safCreateDocumentLauncher) {
        { export ->
            pendingSafExport = export
            safCreateDocumentLauncher.launch(export.fileName)
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val req = pendingPermissionRequest
        pendingPermissionRequest = null
        if (req != null) {
            val grantedResources = mutableListOf<String>()
            req.resources.forEach { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                        if (results[Manifest.permission.RECORD_AUDIO] == true) {
                            grantedResources.add(resource)
                        }
                    }
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                        if (results[Manifest.permission.CAMERA] == true) {
                            grantedResources.add(resource)
                        }
                    }
                }
            }
            if (grantedResources.isNotEmpty()) {
                req.grant(grantedResources.toTypedArray())
            } else {
                req.deny()
            }
        }
    }

    LaunchedEffect(webCommands) {
        webCommands.collect { command ->
            val webView = webViewRef ?: return@collect
            when (command) {
                is WebCommand.LoadUrl -> webView.loadUrl(command.url)
                WebCommand.Reload -> webView.reload()
                WebCommand.GoBack -> if (webView.canGoBack()) webView.goBack()
                WebCommand.GoForward -> if (webView.canGoForward()) webView.goForward()
                WebCommand.ClearCache -> {
                    webView.clearCache(true)
                    webView.clearHistory()
                    onFeedbackMessage(context.getString(R.string.cache_cleared))
                    webView.reload()
                }
                WebCommand.ResetGoogleSession -> {
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    webView.clearCache(true)
                    webView.clearFormData()
                    onFeedbackMessage(context.getString(R.string.session_reset))
                    webView.loadUrl(StudioSection.STUDIO.url)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    webViewRef?.onPause()
                    CookieManager.getInstance().flush()
                }
                Lifecycle.Event.ON_STOP -> CookieManager.getInstance().flush()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CookieManager.getInstance().flush()
            popupWebView?.destroy()
            webViewRef?.destroy()
        }
    }

    key(webViewRecoveryKey) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                    }
                    configureStudioWebSettings(
                        webView = this,
                        isDesktopMode = isDesktopMode,
                        textZoomPercent = textZoomPercent,
                        isDarkTheme = isDarkTheme
                    )
                appliedDesktopMode = isDesktopMode

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(
                    StudioPortalBridge(
                        context = ctx,
                        onSaved = { fileName ->
                            onFeedbackMessage(ctx.getString(R.string.download_saved, fileName))
                        },
                        onSafExportRequested = triggerSafExport,
                        onDownloadFailed = onFeedbackMessage,
                        onThinkingStarted = onAgentThinkingStarted,
                        onThinkingFinished = onAgentThinkingFinished
                    ),
                    "StudioPortalBridge"
                )

                setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    handleWebDownload(
                        context = ctx,
                        webView = this,
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimetype,
                        mainHandler = mainHandler,
                        onSafExportRequested = triggerSafExport,
                        onNotify = onFeedbackMessage
                    )
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url ?: return false
                        val scheme = uri.scheme?.lowercase() ?: return false
                        if (scheme == "http" || scheme == "https") {
                            return false
                        }
                        if (scheme == "intent") {
                            try {
                                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                val fallback = intent.getStringExtra("browser_fallback_url")
                                if (!fallback.isNullOrBlank()) {
                                    view.loadUrl(fallback)
                                    return true
                                }
                            } catch (_: Exception) {
                                return true
                            }
                        }
                        return try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onPageStarted(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        if (url.contains("aistudio.google.com")) {
                            view.evaluateJavascript(AGENT_THINKING_OBSERVER_JS, null)
                        }
                        onPageFinished(url, view.title, view.canGoBack(), view.canGoForward())
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        onNavigationStateChanged(view.canGoBack(), view.canGoForward(), url)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            onMainFrameError(error.description?.toString())
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail
                    ): Boolean {
                        if (view == webViewRef) {
                            webViewRef = null
                            runCatching { view.destroy() }
                            webViewRecoveryKey++
                        }
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onProgressChanged(newProgress)
                    }

                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams
                    ): Boolean {
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = filePathCallback
                        pendingSingleFileOnly =
                            fileChooserParams.mode != FileChooserParams.MODE_OPEN_MULTIPLE
                        val acceptTypes = fileChooserParams.acceptTypes
                            ?.filter { it.isNotBlank() }
                            ?.toTypedArray()
                        val mimeArgs = if (acceptTypes.isNullOrEmpty()) arrayOf("*/*") else acceptTypes
                        return try {
                            filePickerLauncher.launch(mimeArgs)
                            true
                        } catch (_: Exception) {
                            try {
                                filePickerLauncher.launch(arrayOf("*/*"))
                                true
                            } catch (_: Exception) {
                                pendingFileCallback = null
                                false
                            }
                        }
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        val needed = mutableListOf<String>()
                        val directGrant = mutableListOf<String>()

                        request.resources.forEach { res ->
                            when (res) {
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                    if (ContextCompat.checkSelfPermission(
                                            ctx,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        directGrant.add(res)
                                    } else {
                                        needed.add(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                    if (ContextCompat.checkSelfPermission(
                                            ctx,
                                            Manifest.permission.CAMERA
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        directGrant.add(res)
                                    } else {
                                        needed.add(Manifest.permission.CAMERA)
                                    }
                                }
                            }
                        }

                        if (needed.isEmpty()) {
                            if (directGrant.isNotEmpty()) {
                                request.grant(directGrant.toTypedArray())
                            } else {
                                request.deny()
                            }
                        } else {
                            pendingPermissionRequest?.deny()
                            pendingPermissionRequest = request
                            mediaPermissionLauncher.launch(needed.toTypedArray())
                        }
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val child = createPopupWebView(
                            context = ctx,
                            isDesktopMode = appliedDesktopMode,
                            isDarkTheme = isDarkTheme,
                            mainHandler = mainHandler,
                            onSafExportRequested = triggerSafExport,
                            onFeedbackMessage = onFeedbackMessage,
                            onShowFileChooserRequest = { callback, params ->
                                pendingFileCallback?.onReceiveValue(null)
                                pendingFileCallback = callback
                                pendingSingleFileOnly =
                                    params.mode != FileChooserParams.MODE_OPEN_MULTIPLE
                                val acceptTypes = params.acceptTypes
                                    ?.filter { it.isNotBlank() }
                                    ?.toTypedArray()
                                val mimeArgs = if (acceptTypes.isNullOrEmpty()) arrayOf("*/*") else acceptTypes
                                try {
                                    filePickerLauncher.launch(mimeArgs)
                                    true
                                } catch (_: Exception) {
                                    pendingFileCallback = null
                                    false
                                }
                            },
                            onTitleChange = { title -> popupTitle = title },
                            onClose = {
                                popupWebView?.destroy()
                                popupWebView = null
                            }
                        )
                        popupTitle = ctx.getString(R.string.oauth_dialog_title)
                        popupWebView = child
                        transport.webView = child
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onCloseWindow(window: WebView?) {
                        if (window == popupWebView) {
                            popupWebView?.destroy()
                            popupWebView = null
                        }
                    }
                }

                addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    val newHeight = bottom - top
                    val oldHeight = oldBottom - oldTop
                    if (oldHeight > 0 && newHeight in 1 until oldHeight) {
                        (v as? WebView)?.evaluateJavascript(
                            """
                            (function() {
                              var el = document.activeElement;
                              if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.isContentEditable)) {
                                setTimeout(function() {
                                  el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                                }, 80);
                              }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                loadUrl(initialUrl)
                webViewRef = this
            }
        },
        update = { webView ->
            webView.settings.textZoom = textZoomPercent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                webView.settings.isAlgorithmicDarkeningAllowed = isDarkTheme
            }
            if (appliedDesktopMode != isDesktopMode) {
                appliedDesktopMode = isDesktopMode
                webView.settings.userAgentString =
                    if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else MOBILE_CHROME_USER_AGENT
                webView.settings.loadWithOverviewMode = isDesktopMode
                webView.settings.useWideViewPort = true
                webView.reload()
            }
        }
        )
    }

    val activePopup = popupWebView
    if (activePopup != null) {
        Dialog(
            onDismissRequest = {
                activePopup.destroy()
                popupWebView = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = popupTitle.ifBlank { stringResource(R.string.oauth_dialog_title) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                activePopup.destroy()
                                popupWebView = null
                            },
                            modifier = Modifier.testTag("close_popup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        factory = { activePopup }
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureStudioWebSettings(
    webView: WebView,
    isDesktopMode: Boolean,
    textZoomPercent: Int,
    isDarkTheme: Boolean
) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        allowFileAccess = true
        allowContentAccess = true
        useWideViewPort = true
        loadWithOverviewMode = isDesktopMode
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        textZoom = textZoomPercent
        userAgentString =
            if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else MOBILE_CHROME_USER_AGENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isAlgorithmicDarkeningAllowed = isDarkTheme
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPopupWebView(
    context: Context,
    isDesktopMode: Boolean,
    isDarkTheme: Boolean,
    mainHandler: Handler,
    onSafExportRequested: (PendingSafExport) -> Unit,
    onFeedbackMessage: (String) -> Unit,
    onShowFileChooserRequest: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    onTitleChange: (String) -> Unit,
    onClose: () -> Unit
): WebView {
    return WebView(context).apply {
        configureStudioWebSettings(
            webView = this,
            isDesktopMode = isDesktopMode,
            textZoomPercent = 100,
            isDarkTheme = isDarkTheme
        )
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(
            StudioPortalBridge(
                context = context,
                onSaved = { fileName ->
                    onFeedbackMessage(context.getString(R.string.download_saved, fileName))
                },
                onSafExportRequested = onSafExportRequested,
                onDownloadFailed = onFeedbackMessage,
                onThinkingStarted = {},
                onThinkingFinished = { _, _ -> }
            ),
            "StudioPortalBridge"
        )

        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleWebDownload(
                context = context,
                webView = this,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimetype,
                mainHandler = mainHandler,
                onSafExportRequested = onSafExportRequested,
                onNotify = onFeedbackMessage
            )
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                view.title?.takeIf { it.isNotBlank() }?.let(onTitleChange)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                runCatching { view.destroy() }
                onClose()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean = onShowFileChooserRequest(filePathCallback, fileChooserParams)

            override fun onCloseWindow(window: WebView?) {
                onClose()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) onTitleChange(title)
            }
        }
    }
}

private fun handleWebDownload(
    context: Context,
    webView: WebView,
    url: String,
    userAgent: String,
    contentDisposition: String?,
    mimeType: String?,
    mainHandler: Handler,
    onSafExportRequested: (PendingSafExport) -> Unit,
    onNotify: (String) -> Unit
) {
    val guessedName = resolveFileName(url, contentDisposition, mimeType)

    if (url.startsWith("blob:")) {
        val safeJsName = guessedName.replace("\\", "").replace("'", "")
        val safeJsMime = (mimeType ?: "").replace("'", "")
        val script = """
            (function() {
              fetch('$url').then(function(r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.blob();
              }).then(function(blob) {
                var reader = new FileReader();
                reader.onloadend = function() {
                  if (window.StudioPortalBridge) {
                    window.StudioPortalBridge.saveBase64File(
                      reader.result,
                      blob.type || '$safeJsMime',
                      '$safeJsName'
                    );
                  }
                };
                reader.onerror = function() {
                  if (window.StudioPortalBridge) {
                    window.StudioPortalBridge.onDownloadError('read_error');
                  }
                };
                reader.readAsDataURL(blob);
              }).catch(function(err) {
                if (window.StudioPortalBridge) {
                  var msg = (err && err.message) ? err.message : '';
                  window.StudioPortalBridge.onDownloadError('cross_origin');
                }
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        return
    }

    if (url.startsWith("data:")) {
        ioExecutor.execute {
            try {
                val parsedMime = extractMimeFromDataUrl(url) ?: mimeType ?: "application/octet-stream"
                val headerAndData = url.removePrefix("data:")
                val commaIdx = headerAndData.indexOf(',')
                if (commaIdx <= 0) {
                    mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
                    return@execute
                }
                val meta = headerAndData.substring(0, commaIdx)
                val payload = headerAndData.substring(commaIdx + 1)
                val bytes = if (meta.contains(";base64", ignoreCase = true)) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                }
                saveBytesToDownloads(
                    context = context,
                    bytes = bytes,
                    mimeType = parsedMime,
                    rawFileName = guessedName,
                    mainHandler = mainHandler,
                    onSaved = { savedName ->
                        onNotify(context.getString(R.string.download_saved, savedName))
                    },
                    onSafExportRequested = onSafExportRequested,
                    onFailed = { onNotify(context.getString(R.string.download_failed)) }
                )
            } catch (_: Exception) {
                mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
            }
        }
        return
    }

    if (!url.startsWith("http://") && !url.startsWith("https://")) return

    val referer = webView.url
    val cookie = CookieManager.getInstance().getCookie(url)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
                if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer)
                addRequestHeader("User-Agent", userAgent)
                setTitle(guessedName)
                setDescription("Google AI Studio")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            onNotify("${context.getString(R.string.download_started)} : $guessedName")
            return
        } catch (_: Exception) {
            // Fall through to direct authenticated stream download below
        }
    }

    onNotify("${context.getString(R.string.download_started)} : $guessedName")
    downloadViaHttpUrlConnection(
        context = context,
        urlString = url,
        userAgent = userAgent,
        cookie = cookie,
        referer = referer,
        fallbackName = guessedName,
        fallbackMime = mimeType,
        mainHandler = mainHandler,
        onSafExportRequested = onSafExportRequested,
        onNotify = onNotify
    )
}

private fun downloadViaHttpUrlConnection(
    context: Context,
    urlString: String,
    userAgent: String,
    cookie: String?,
    referer: String?,
    fallbackName: String,
    fallbackMime: String?,
    mainHandler: Handler,
    onSafExportRequested: (PendingSafExport) -> Unit,
    onNotify: (String) -> Unit
) {
    ioExecutor.execute {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
                return@execute
            }
            val serverDisposition = connection.getHeaderField("Content-Disposition")
            val serverMime = connection.contentType?.substringBefore(';')?.trim()
                ?: fallbackMime
                ?: "application/octet-stream"
            val finalName = resolveFileName(urlString, serverDisposition, serverMime)
                .ifBlank { fallbackName }

            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }

            saveBytesToDownloads(
                context = context,
                bytes = bytes,
                mimeType = serverMime,
                rawFileName = finalName,
                mainHandler = mainHandler,
                onSaved = { savedName ->
                    onNotify(context.getString(R.string.download_saved, savedName))
                },
                onSafExportRequested = onSafExportRequested,
                onFailed = { onNotify(context.getString(R.string.download_failed)) }
            )
        } catch (_: Exception) {
            mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
        } finally {
            connection?.disconnect()
        }
    }
}

private fun saveBytesToDownloads(
    context: Context,
    bytes: ByteArray,
    mimeType: String,
    rawFileName: String?,
    mainHandler: Handler,
    onSaved: (String) -> Unit,
    onSafExportRequested: (PendingSafExport) -> Unit,
    onFailed: () -> Unit
) {
    val cleanMime = mimeType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
    val safeName = ensureExtension(sanitizeFileName(rawFileName), cleanMime)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, cleanMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                } ?: throw IllegalStateException("Null output stream")
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                StudioNotificationHelper.showDownloadCompletedNotification(
                    context = context,
                    fileName = safeName,
                    contentUri = uri,
                    mimeType = cleanMime
                )
                mainHandler.post { onSaved(safeName) }
                return
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                // Fall back to SAF document picker below
            }
        }
    }

    mainHandler.post {
        onSafExportRequested(
            PendingSafExport(
                bytes = bytes,
                fileName = safeName,
                mimeType = cleanMime
            )
        )
    }
}

private fun resolveFileName(
    url: String,
    contentDisposition: String?,
    mimeType: String?
): String {
    if (!contentDisposition.isNullOrBlank()) {
        val utf8Match = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\r\n]+)""")
            .find(contentDisposition)
        if (utf8Match != null) {
            val decoded = runCatching {
                URLDecoder.decode(utf8Match.groupValues[1].trim().trim('"'), "UTF-8")
            }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                return ensureExtension(sanitizeFileName(decoded), mimeType)
            }
        }
        val plainMatch = Regex("""filename\s*=\s*"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentDisposition)
        if (plainMatch != null) {
            val candidate = plainMatch.groupValues[1].trim()
            if (candidate.isNotBlank()) {
                return ensureExtension(sanitizeFileName(candidate), mimeType)
            }
        }
    }

    val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
    return ensureExtension(sanitizeFileName(guessed), mimeType)
}

private fun sanitizeFileName(name: String?): String {
    val cleaned = (name ?: "")
        .replace(Regex("""[\\/:*?"<>|\r\n]"""), "_")
        .trim('.', ' ')
    return cleaned.ifBlank { "aistudio_export_${System.currentTimeMillis()}" }
}

private fun ensureExtension(fileName: String, mimeType: String?): String {
    val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (fileName.contains('.') && !fileName.endsWith(".bin", ignoreCase = true)) {
        return fileName
    }
    val extFromMime = cleanMime?.let { mime ->
        when (mime) {
            "application/zip", "application/x-zip-compressed" -> "zip"
            "application/json" -> "json"
            "text/plain" -> "txt"
            "text/markdown" -> "md"
            "text/html" -> "html"
            "text/csv" -> "csv"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "application/pdf" -> "pdf"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        }
    }
    if (extFromMime.isNullOrBlank()) return fileName
    val base = if (fileName.endsWith(".bin", ignoreCase = true)) {
        fileName.dropLast(4)
    } else {
        fileName
    }
    return "$base.$extFromMime"
}

private fun extractMimeFromDataUrl(dataUrl: String): String? {
    if (!dataUrl.startsWith("data:")) return null
    val header = dataUrl.removePrefix("data:").substringBefore(',')
    val mime = header.substringBefore(';').trim()
    return mime.takeIf { it.contains('/') }
}
