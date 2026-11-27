package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

private const val DESKTOP_CHROME_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Safari/537.36"

private const val MAX_DOWNLOAD_BYTES = 200L * 1024L * 1024L

private val ioExecutor = Executors.newCachedThreadPool()

private val DOWNLOAD_INTERCEPTOR_JS = """
(function() {
  if (window.__spDownloadHookInstalled) return;
  window.__spDownloadHookInstalled = true;

  var blobStore = new Map();
  var maxBlobs = 30;
  var origCreateObjectURL = URL.createObjectURL ? URL.createObjectURL.bind(URL) : null;
  var origRevokeObjectURL = URL.revokeObjectURL ? URL.revokeObjectURL.bind(URL) : null;

  if (origCreateObjectURL) {
    URL.createObjectURL = function(obj) {
      var url = origCreateObjectURL(obj);
      try {
        if (obj && typeof Blob !== 'undefined' && obj instanceof Blob) {
          if (blobStore.size >= maxBlobs) {
            var oldest = blobStore.keys().next().value;
            if (oldest) blobStore.delete(oldest);
          }
          blobStore.set(url, obj);
        }
      } catch (e) {}
      return url;
    };
  }

  if (origRevokeObjectURL) {
    URL.revokeObjectURL = function(url) {
      setTimeout(function() {
        try { blobStore.delete(url); } catch (e) {}
        try { origRevokeObjectURL(url); } catch (e) {}
      }, 30000);
    };
  }

  function sendBlobToNative(blob, fileName, mimeType) {
    var bridge = window.StudioPortalBridge;
    if (!blob || !bridge) return false;
    var finalMime = mimeType || blob.type || 'application/octet-stream';
    var finalName = fileName || '';
    if (!bridge.beginChunkedFile) {
      var fallbackReader = new FileReader();
      fallbackReader.onloadend = function() {
        bridge.saveBase64File(fallbackReader.result, finalMime, finalName);
      };
      fallbackReader.readAsDataURL(blob);
      return true;
    }
    var id = 'sp_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10);
    if (!bridge.beginChunkedFile(id, finalMime, finalName)) {
      bridge.onDownloadError('start_error');
      return true;
    }
    var CHUNK = 393216;
    var offset = 0;
    function nextChunk() {
      if (offset >= blob.size) {
        bridge.finishChunkedFile(id);
        return;
      }
      var slice = blob.slice(offset, Math.min(offset + CHUNK, blob.size));
      var reader = new FileReader();
      reader.onloadend = function() {
        var res = reader.result || '';
        var idx = res.indexOf('base64,');
        var payload = idx !== -1 ? res.substring(idx + 7) : res;
        if (payload && !bridge.appendFileChunk(id, payload)) {
          if (bridge.cancelChunkedFile) bridge.cancelChunkedFile(id);
          bridge.onDownloadError('write_error');
          return;
        }
        offset += slice.size;
        nextChunk();
      };
      reader.onerror = function() {
        if (bridge.cancelChunkedFile) bridge.cancelChunkedFile(id);
        bridge.onDownloadError('read_error');
      };
      reader.readAsDataURL(slice);
    }
    nextChunk();
    return true;
  }

  function handleAnchorDownload(anchor) {
    if (!anchor || !window.StudioPortalBridge) return false;
    var bridge = window.StudioPortalBridge;
    var rawHref = anchor.getAttribute ? (anchor.getAttribute('href') || '') : '';
    var href = anchor.href || rawHref;
    if (!href) return false;
    var hasDl = anchor.hasAttribute ? anchor.hasAttribute('download') : false;
    var dlName = anchor.getAttribute ? (anchor.getAttribute('download') || '') : '';

    if (href.indexOf('data:') === 0) {
      bridge.saveBase64File(href, '', dlName);
      return true;
    }

    if (href.indexOf('blob:') === 0) {
      var cached = blobStore.get(href);
      if (cached) {
        return sendBlobToNative(cached, dlName, cached.type || '');
      }
      fetch(href).then(function(r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.blob();
      }).then(function(blob) {
        sendBlobToNative(blob, dlName, blob.type || '');
      }).catch(function() {
        bridge.onDownloadError('cross_origin');
      });
      return true;
    }

    if (hasDl && (href.indexOf('https://') === 0 || href.indexOf('http://') === 0)) {
      if (bridge.downloadRemoteUrl) {
        bridge.downloadRemoteUrl(href, dlName, window.location.href || '');
        return true;
      }
    }

    return false;
  }

  window.__spSendBlobByUrl = function(blobUrl, fileName, mimeType) {
    var cached = blobStore.get(blobUrl);
    if (cached) {
      return sendBlobToNative(cached, fileName, mimeType || cached.type || '');
    }
    fetch(blobUrl).then(function(r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.blob();
    }).then(function(blob) {
      sendBlobToNative(blob, fileName, mimeType || blob.type || '');
    }).catch(function() {
      if (window.StudioPortalBridge) {
        window.StudioPortalBridge.onDownloadError('cross_origin');
      }
    });
    return true;
  };

  if (typeof HTMLAnchorElement !== 'undefined' && HTMLAnchorElement.prototype && HTMLAnchorElement.prototype.click) {
    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      try {
        if (handleAnchorDownload(this)) return;
      } catch (e) {}
      return origAnchorClick.apply(this, arguments);
    };
  }

  document.addEventListener('click', function(e) {
    try {
      var target = e.target;
      var anchor = target && target.closest ? target.closest('a') : null;
      if (!anchor) return;
      var href = anchor.href || '';
      var hasDl = anchor.hasAttribute && anchor.hasAttribute('download');
      if (hasDl || href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
        if (handleAnchorDownload(anchor)) {
          e.preventDefault();
          e.stopPropagation();
        }
      }
    } catch (err) {}
  }, true);

  if (window.open) {
    var origOpen = window.open;
    window.open = function(url) {
      try {
        if (typeof url === 'string' && window.StudioPortalBridge) {
          if (url.indexOf('data:') === 0) {
            window.StudioPortalBridge.saveBase64File(url, '', '');
            return null;
          }
          if (url.indexOf('blob:') === 0 && window.__spSendBlobByUrl) {
            window.__spSendBlobByUrl(url, '', '');
            return null;
          }
        }
      } catch (e) {}
      return origOpen.apply(window, arguments);
    };
  }
})();
""".trimIndent()

private val AGENT_THINKING_OBSERVER_JS = """
(function() {
  if (window.__studioPortalObserverInstalled) return;
  window.__studioPortalObserverInstalled = true;

  var mainHandler = {
    isThinking: false,
    startedAt: 0,
    lastProgressAt: 0,
    lastSignature: '',
    stalledNotified: false,
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

  function extractProgressSignature() {
    try {
      var candidates = document.querySelectorAll('ms-chat-turn, [data-turn-role="model"], ms-thought-chunk, .model-prompt-container');
      if (candidates && candidates.length > 0) {
        var last = candidates[candidates.length - 1];
        var txt = last.innerText || '';
        return candidates.length + ':' + txt.length + ':' + txt.slice(-40);
      }
      var bodyLen = document.body ? (document.body.innerText || '').length : 0;
      return 'b:' + bodyLen;
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
      var sig = extractProgressSignature();
      if (!mainHandler.isThinking) {
        mainHandler.isThinking = true;
        mainHandler.startedAt = Date.now();
        mainHandler.lastProgressAt = Date.now();
        mainHandler.lastSignature = sig;
        mainHandler.stalledNotified = false;
        if (window.StudioPortalBridge && window.StudioPortalBridge.onAgentThinkingStarted) {
          window.StudioPortalBridge.onAgentThinkingStarted();
        }
      } else {
        if (sig && sig !== mainHandler.lastSignature) {
          mainHandler.lastSignature = sig;
          mainHandler.lastProgressAt = Date.now();
          if (mainHandler.stalledNotified) {
            mainHandler.stalledNotified = false;
            if (window.StudioPortalBridge && window.StudioPortalBridge.onAgentThinkingStarted) {
              window.StudioPortalBridge.onAgentThinkingStarted();
            }
          }
        } else if (!mainHandler.stalledNotified && (Date.now() - mainHandler.lastProgressAt >= 45000)) {
          mainHandler.stalledNotified = true;
          if (window.StudioPortalBridge && window.StudioPortalBridge.onAgentThinkingStalled) {
            window.StudioPortalBridge.onAgentThinkingStalled();
          }
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
        mainHandler.stalledNotified = false;
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
    val bytes: ByteArray? = null,
    val tempFile: File? = null,
    val fileName: String,
    val mimeType: String
)

private class ActiveChunkedTransfer(
    val tempFile: File,
    val out: OutputStream,
    val mimeType: String,
    val fileName: String?,
    var totalBytes: Long = 0L
)

@Keep
private class StudioPortalBridge(
    private val context: Context,
    private val userAgentProvider: () -> String,
    private val onSaved: (String) -> Unit,
    private val onSafExportRequested: (PendingSafExport) -> Unit,
    private val onDownloadFailed: (String) -> Unit,
    private val onThinkingStarted: () -> Unit,
    private val onThinkingStalled: () -> Unit,
    private val onThinkingFinished: (Int, String?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transfersLock = Any()
    private val activeTransfers = ConcurrentHashMap<String, ActiveChunkedTransfer>()
    private val safeIdRegex = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Keep
    @JavascriptInterface
    fun beginChunkedFile(transferId: String?, mimeType: String?, fileName: String?): Boolean {
        val id = transferId?.takeIf { safeIdRegex.matches(it) } ?: return false
        return try {
            val tmpDir = File(context.cacheDir, "downloads_tmp").apply { mkdirs() }
            val tempFile = File.createTempFile("sp_dl_", ".part", tmpDir)
            val stream = BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024)
            val cleanMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val transfer = ActiveChunkedTransfer(
                tempFile = tempFile,
                out = stream,
                mimeType = cleanMime,
                fileName = fileName?.takeIf { it.isNotBlank() }
            )
            synchronized(transfersLock) {
                activeTransfers.remove(id)?.let { prev ->
                    runCatching { prev.out.close() }
                    runCatching { prev.tempFile.delete() }
                }
                activeTransfers[id] = transfer
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun appendFileChunk(transferId: String?, base64Chunk: String?): Boolean {
        if (transferId.isNullOrBlank() || base64Chunk == null) return false
        val transfer = synchronized(transfersLock) { activeTransfers[transferId] } ?: return false
        return try {
            val decoded = Base64.decode(base64Chunk, Base64.DEFAULT)
            synchronized(transfer) {
                if (transfer.totalBytes + decoded.size > MAX_DOWNLOAD_BYTES) {
                    return false
                }
                transfer.out.write(decoded)
                transfer.totalBytes += decoded.size
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Keep
    @JavascriptInterface
    fun finishChunkedFile(transferId: String?) {
        val transfer = if (transferId != null) {
            synchronized(transfersLock) { activeTransfers.remove(transferId) }
        } else {
            null
        } ?: return

        ioExecutor.execute {
            try {
                synchronized(transfer) {
                    transfer.out.flush()
                    transfer.out.close()
                }
                saveTempFileToDownloads(
                    context = context,
                    tempFile = transfer.tempFile,
                    mimeType = transfer.mimeType,
                    rawFileName = transfer.fileName,
                    mainHandler = mainHandler,
                    onSaved = onSaved,
                    onSafExportRequested = onSafExportRequested,
                    onFailed = { onDownloadFailed(context.getString(R.string.download_failed)) }
                )
            } catch (_: Exception) {
                runCatching { transfer.out.close() }
                runCatching { transfer.tempFile.delete() }
                mainHandler.post {
                    onDownloadFailed(context.getString(R.string.download_failed))
                }
            }
        }
    }

    @Keep
    @JavascriptInterface
    fun cancelChunkedFile(transferId: String?) {
        if (transferId == null) return
        val transfer = synchronized(transfersLock) { activeTransfers.remove(transferId) } ?: return
        runCatching { transfer.out.close() }
        runCatching { transfer.tempFile.delete() }
    }

    @Keep
    @JavascriptInterface
    fun downloadRemoteUrl(url: String?, suggestedName: String?, pageReferer: String?) {
        if (url.isNullOrBlank() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            return
        }
        val cleanName = sanitizeFileName(
            suggestedName?.takeIf { it.isNotBlank() }
                ?: URLUtil.guessFileName(url, null, null)
        )
        mainHandler.post {
            onDownloadFailed("${context.getString(R.string.download_started)} : $cleanName")
        }
        downloadViaHttpUrlConnection(
            context = context,
            urlString = url,
            userAgent = userAgentProvider(),
            referer = pageReferer,
            fallbackName = cleanName,
            fallbackMime = null,
            mainHandler = mainHandler,
            onSafExportRequested = onSafExportRequested,
            onNotify = onDownloadFailed
        )
    }

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
    fun onAgentThinkingStalled() {
        mainHandler.post { onThinkingStalled() }
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
    isAgentThinking: Boolean,
    webCommands: SharedFlow<WebCommand>,
    onPageStarted: (String) -> Unit,
    onPageFinished: (url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onNavigationStateChanged: (canGoBack: Boolean, canGoForward: Boolean, url: String?) -> Unit,
    onMainFrameError: (String?) -> Unit,
    onAgentThinkingStarted: () -> Unit,
    onAgentThinkingStalled: () -> Unit,
    onAgentThinkingFinished: (durationSeconds: Int, preview: String?) -> Unit,
    onFeedbackMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webViewRecoveryKey by remember { mutableIntStateOf(0) }
    var activeLoadUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
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
        if (export == null) return@rememberLauncherForActivityResult
        if (destUri != null) {
            ioExecutor.execute {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        when {
                            export.tempFile != null && export.tempFile.exists() -> {
                                FileInputStream(export.tempFile).use { input ->
                                    input.copyTo(out)
                                }
                            }
                            export.bytes != null -> {
                                out.write(export.bytes)
                            }
                        }
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
                } finally {
                    runCatching { export.tempFile?.delete() }
                }
            }
        } else {
            runCatching { export.tempFile?.delete() }
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
            val webView = webViewRef
            when (command) {
                is WebCommand.LoadUrl -> {
                    activeLoadUrl = command.url
                    webView?.loadUrl(command.url)
                }
                WebCommand.Reload -> webView?.reload()
                WebCommand.GoBack -> if (webView?.canGoBack() == true) webView.goBack()
                WebCommand.GoForward -> if (webView?.canGoForward() == true) webView.goForward()
                WebCommand.ClearCache -> {
                    webView?.clearCache(true)
                    webView?.clearHistory()
                    onFeedbackMessage(context.getString(R.string.cache_cleared))
                    webView?.reload()
                }
                WebCommand.ResetGoogleSession -> {
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    webView?.clearCache(true)
                    webView?.clearFormData()
                    activeLoadUrl = StudioSection.STUDIO.url
                    onFeedbackMessage(context.getString(R.string.session_reset))
                    webView?.loadUrl(StudioSection.STUDIO.url)
                }
                WebCommand.RestartWebEngine -> {
                    val resumeUrl = webView?.url?.takeIf { it.startsWith("https://") } ?: activeLoadUrl
                    activeLoadUrl = resumeUrl
                    CookieManager.getInstance().flush()
                    webViewRef = null
                    runCatching { webView?.destroy() }
                    webViewRecoveryKey++
                    onFeedbackMessage(context.getString(R.string.engine_restarted))
                }
            }
        }
    }

    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    webViewRef?.setNetworkAvailable(true)
                    webViewRef?.resumeTimers()
                    webViewRef?.evaluateJavascript(
                        "window.dispatchEvent(new Event('online'));",
                        null
                    )
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    webViewRef?.setNetworkAvailable(false)
                }
            }
        }
        val registered = runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, callback)
            true
        }.getOrDefault(false)

        onDispose {
            if (registered) {
                runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Ensure timers and streaming connections are never left paused after background/dialogs.
                    webViewRef?.onResume()
                    webViewRef?.resumeTimers()
                    webViewRef?.setNetworkAvailable(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Do NOT call webView.onPause(): pausing Chromium freezes active AI Studio SSE streams mid-thinking.
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
                    setBackgroundColor(
                        if (isDarkTheme) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    )
                    keepScreenOn = isAgentThinking
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
                        userAgentProvider = {
                            if (appliedDesktopMode) DESKTOP_CHROME_USER_AGENT else MOBILE_CHROME_USER_AGENT
                        },
                        onSaved = { fileName ->
                            onFeedbackMessage(ctx.getString(R.string.download_saved, fileName))
                        },
                        onSafExportRequested = triggerSafExport,
                        onDownloadFailed = onFeedbackMessage,
                        onThinkingStarted = onAgentThinkingStarted,
                        onThinkingStalled = onAgentThinkingStalled,
                        onThinkingFinished = onAgentThinkingFinished
                    ),
                    "StudioPortalBridge"
                )

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            DOWNLOAD_INTERCEPTOR_JS,
                            setOf("*")
                        )
                    }
                }

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
                        if (url.startsWith("https://")) activeLoadUrl = url
                        view.evaluateJavascript(DOWNLOAD_INTERCEPTOR_JS, null)
                        onPageStarted(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        if (url.startsWith("https://")) activeLoadUrl = url
                        CookieManager.getInstance().flush()
                        view.evaluateJavascript(DOWNLOAD_INTERCEPTOR_JS, null)
                        if (url.contains("aistudio.google.com")) {
                            view.evaluateJavascript(AGENT_THINKING_OBSERVER_JS, null)
                        }
                        onPageFinished(url, view.title, view.canGoBack(), view.canGoForward())
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        if (url.startsWith("https://")) activeLoadUrl = url
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

                loadUrl(activeLoadUrl)
                webViewRef = this
            }
        },
        update = { webView ->
            webView.keepScreenOn = isAgentThinking
            webView.setBackgroundColor(
                if (isDarkTheme) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
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
                userAgentProvider = {
                    if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else MOBILE_CHROME_USER_AGENT
                },
                onSaved = { fileName ->
                    onFeedbackMessage(context.getString(R.string.download_saved, fileName))
                },
                onSafExportRequested = onSafExportRequested,
                onDownloadFailed = onFeedbackMessage,
                onThinkingStarted = {},
                onThinkingStalled = {},
                onThinkingFinished = { _, _ -> }
            ),
            "StudioPortalBridge"
        )

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    DOWNLOAD_INTERCEPTOR_JS,
                    setOf("*")
                )
            }
        }

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
            if (this.url.isNullOrBlank() || this.url == url) {
                mainHandler.post { onClose() }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(DOWNLOAD_INTERCEPTOR_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                view.evaluateJavascript(DOWNLOAD_INTERCEPTOR_JS, null)
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
        val jsUrl = JSONObject.quote(url)
        val jsName = JSONObject.quote(guessedName)
        val jsMime = JSONObject.quote(mimeType ?: "")
        val script = """
            (function() {
              if (window.__spSendBlobByUrl) {
                window.__spSendBlobByUrl($jsUrl, $jsName, $jsMime);
                return;
              }
              fetch($jsUrl).then(function(r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.blob();
              }).then(function(blob) {
                var reader = new FileReader();
                reader.onloadend = function() {
                  if (window.StudioPortalBridge) {
                    window.StudioPortalBridge.saveBase64File(
                      reader.result,
                      blob.type || $jsMime,
                      $jsName
                    );
                  }
                };
                reader.onerror = function() {
                  if (window.StudioPortalBridge) {
                    window.StudioPortalBridge.onDownloadError('read_error');
                  }
                };
                reader.readAsDataURL(blob);
              }).catch(function() {
                if (window.StudioPortalBridge) {
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
    onNotify("${context.getString(R.string.download_started)} : $guessedName")
    downloadViaHttpUrlConnection(
        context = context,
        urlString = url,
        userAgent = userAgent,
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
    referer: String?,
    fallbackName: String,
    fallbackMime: String?,
    mainHandler: Handler,
    onSafExportRequested: (PendingSafExport) -> Unit,
    onNotify: (String) -> Unit
) {
    ioExecutor.execute {
        var tempFile: File? = null
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = urlString
            var currentReferer = referer
            var redirects = 0
            val cookieManager = CookieManager.getInstance()

            while (true) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", userAgent)
                    val hopCookie = cookieManager.getCookie(currentUrl)
                    if (!hopCookie.isNullOrBlank()) setRequestProperty("Cookie", hopCookie)
                    if (!currentReferer.isNullOrBlank()) setRequestProperty("Referer", currentReferer)
                }
                connection = conn
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    connection = null
                    if (location.isNullOrBlank() || redirects >= 10) {
                        mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
                        return@execute
                    }
                    val nextUrl = URL(URL(currentUrl), location).toString()
                    if (!nextUrl.startsWith("https://") && !nextUrl.startsWith("http://")) {
                        mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
                        return@execute
                    }
                    currentReferer = currentUrl
                    currentUrl = nextUrl
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    mainHandler.post { onNotify(context.getString(R.string.download_failed)) }
                    return@execute
                }
                break
            }

            val activeConn = connection ?: return@execute
            val serverDisposition = activeConn.getHeaderField("Content-Disposition")
            val serverMime = activeConn.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackMime
                ?: "application/octet-stream"
            val finalName = resolveFileName(currentUrl, serverDisposition, serverMime)
                .ifBlank { fallbackName }

            val tmpDir = File(context.cacheDir, "downloads_tmp").apply { mkdirs() }
            val dlFile = File.createTempFile("sp_http_", ".part", tmpDir)
            tempFile = dlFile

            activeConn.inputStream.use { input ->
                BufferedOutputStream(FileOutputStream(dlFile), 64 * 1024).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw IllegalStateException("Fichier trop volumineux")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }

            tempFile = null
            saveTempFileToDownloads(
                context = context,
                tempFile = dlFile,
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
            runCatching { tempFile?.delete() }
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

private fun saveTempFileToDownloads(
    context: Context,
    tempFile: File,
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
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IllegalStateException("Null output stream")
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                runCatching { tempFile.delete() }
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
            }
        }
    }

    mainHandler.post {
        onSafExportRequested(
            PendingSafExport(
                tempFile = tempFile,
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
