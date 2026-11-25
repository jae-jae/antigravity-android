package com.antigravity.webapp

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        const val TARGET_URL = "https://antigravity.google.com/"
        private const val DEFAULT_BG_COLOR = 0xFF1F1F1F.toInt()
        private const val BACK_PRESS_INTERVAL_MS = 2000L
        private const val SCROLL_SAMPLE_INTERVAL_MS = 140L
        private const val MIN_SPLASH_MS = 650L
        private const val MAX_SPLASH_WAIT_MS = 6000L
        private const val POST_LOAD_SETTLE_MS = 380L
        private const val KEY_SPLASH_DISMISSED = "splash_dismissed"
    }

    private lateinit var rootContainer: LinearLayout
    private lateinit var statusBarSpacer: View
    private lateinit var navBarSpacer: View
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var splashOverlay: FrameLayout
    private lateinit var splashBrandGroup: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var popupDialog: Dialog? = null
    private var lastBackPressedAt = 0L

    private var currentStatusColor = DEFAULT_BG_COLOR
    private var currentNavColor = DEFAULT_BG_COLOR
    private var statusAnimator: ValueAnimator? = null
    private var navAnimator: ValueAnimator? = null
    private var lastScrollSampleAt = 0L
    private var isImeVisible = false

    private var launchStartAt = 0L
    private var hasDismissedSplash = false

    private val delayedSampleRunnable = Runnable { samplePageHeaderAndFooterColors() }
    private val splashTimeoutRunnable = Runnable { dismissSplashOverlay() }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        statusBarSpacer = findViewById(R.id.statusBarSpacer)
        navBarSpacer = findViewById(R.id.navBarSpacer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        splashBrandGroup = findViewById(R.id.splashBrandGroup)

        hasDismissedSplash = savedInstanceState?.getBoolean(KEY_SPLASH_DISMISSED, false) ?: false
        if (hasDismissedSplash) {
            splashOverlay.visibility = View.GONE
        } else {
            launchStartAt = System.currentTimeMillis()
            mainHandler.postDelayed(splashTimeoutRunnable, MAX_SPLASH_WAIT_MS)
        }

        setupImmersiveInsets()
        applyStatusBarColor(DEFAULT_BG_COLOR, animate = false)
        applyNavBarColor(DEFAULT_BG_COLOR, animate = false)
        configureWebView(webView, isPopup = false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAt < BACK_PRESS_INTERVAL_MS) {
                    finish()
                } else {
                    lastBackPressedAt = now
                    Toast.makeText(
                        this@MainActivity,
                        R.string.press_back_again_to_exit,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(TARGET_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun setupImmersiveInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            isImeVisible = ime.bottom > systemBars.bottom

            val topHeight = max(systemBars.top, cutout.top)
            val bottomHeight = max(systemBars.bottom, ime.bottom)

            if (statusBarSpacer.layoutParams.height != topHeight) {
                statusBarSpacer.layoutParams = statusBarSpacer.layoutParams.apply {
                    height = topHeight
                }
            }
            if (navBarSpacer.layoutParams.height != bottomHeight) {
                navBarSpacer.layoutParams = navBarSpacer.layoutParams.apply {
                    height = bottomHeight
                }
            }

            view.setPadding(
                max(systemBars.left, cutout.left),
                0,
                max(systemBars.right, cutout.right),
                0
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun scheduleSplashDismissAfterPageLoad() {
        if (hasDismissedSplash) return
        // Pre-match header color via DOM while splash is still covering the WebView
        sampleHeaderViaDom()
        val elapsed = System.currentTimeMillis() - launchStartAt
        val remainingMin = (MIN_SPLASH_MS - elapsed).coerceAtLeast(0L)
        val delay = max(remainingMin, POST_LOAD_SETTLE_MS)
        mainHandler.removeCallbacks(splashTimeoutRunnable)
        mainHandler.postDelayed({ dismissSplashOverlay() }, delay)
    }

    private fun dismissSplashOverlay() {
        if (hasDismissedSplash) return
        hasDismissedSplash = true
        mainHandler.removeCallbacks(splashTimeoutRunnable)

        splashBrandGroup.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(0f)
            .setDuration(260L)
            .start()

        splashOverlay.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction {
                splashOverlay.visibility = View.GONE
                scheduleHeaderSampling()
            }
            .start()
    }

    private fun applyStatusBarColor(targetColor: Int, animate: Boolean = true) {
        if (targetColor == currentStatusColor && animate) return
        statusAnimator?.cancel()

        val updateIcons: (Int) -> Unit = { color ->
            val isLight = ColorUtils.calculateLuminance(color) > 0.5
            WindowInsetsControllerCompat(window, rootContainer).isAppearanceLightStatusBars = isLight
        }

        if (!animate) {
            currentStatusColor = targetColor
            statusBarSpacer.setBackgroundColor(targetColor)
            updateIcons(targetColor)
            return
        }

        val startColor = currentStatusColor
        currentStatusColor = targetColor
        updateIcons(targetColor)
        statusAnimator = ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = 180L
            addUpdateListener { anim ->
                statusBarSpacer.setBackgroundColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    private fun applyNavBarColor(targetColor: Int, animate: Boolean = true) {
        if (targetColor == currentNavColor && animate) return
        navAnimator?.cancel()

        val updateIcons: (Int) -> Unit = { color ->
            val isLight = ColorUtils.calculateLuminance(color) > 0.5
            WindowInsetsControllerCompat(window, rootContainer).isAppearanceLightNavigationBars = isLight
        }

        if (!animate) {
            currentNavColor = targetColor
            navBarSpacer.setBackgroundColor(targetColor)
            updateIcons(targetColor)
            return
        }

        val startColor = currentNavColor
        currentNavColor = targetColor
        updateIcons(targetColor)
        navAnimator = ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = 180L
            addUpdateListener { anim ->
                navBarSpacer.setBackgroundColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView, isPopup: Boolean) {
        targetWebView.setBackgroundColor(DEFAULT_BG_COLOR)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(targetWebView, true)

        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace("Version/4.0 ", "")
        }

        if (!isPopup) {
            targetWebView.setOnScrollChangeListener { _, _, _, _, _ ->
                if (!hasDismissedSplash) return@setOnScrollChangeListener
                val now = System.currentTimeMillis()
                if (now - lastScrollSampleAt >= SCROLL_SAMPLE_INTERVAL_MS) {
                    lastScrollSampleAt = now
                    samplePageHeaderAndFooterColors()
                }
                mainHandler.removeCallbacks(delayedSampleRunnable)
                mainHandler.postDelayed(delayedSampleRunnable, 200L)
            }
        }

        targetWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!isPopup && hasDismissedSplash) {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (!isPopup && hasDismissedSplash) {
                    scheduleHeaderSampling()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (!isPopup && hasDismissedSplash) {
                    scheduleHeaderSampling()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isPopup) {
                    progressBar.visibility = View.GONE
                    if (!hasDismissedSplash) {
                        scheduleSplashDismissAfterPageLoad()
                    } else {
                        scheduleHeaderSampling()
                    }
                }
                CookieManager.getInstance().flush()
            }
        }

        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!isPopup) {
                    progressBar.progress = newProgress
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val childWebView = WebView(this@MainActivity)
                configureWebView(childWebView, isPopup = true)

                val dialog = Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                    setContentView(
                        childWebView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    setOnDismissListener {
                        childWebView.destroy()
                        if (popupDialog === this) {
                            popupDialog = null
                        }
                    }
                }
                popupDialog?.dismiss()
                popupDialog = dialog
                dialog.show()

                transport.webView = childWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (isPopup) {
                    popupDialog?.dismiss()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    false
                }
            }
        }

        targetWebView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleHeaderSampling() {
        if (!hasDismissedSplash) return
        mainHandler.removeCallbacks(delayedSampleRunnable)
        samplePageHeaderAndFooterColors()
        mainHandler.postDelayed(delayedSampleRunnable, 250L)
        mainHandler.postDelayed({ samplePageHeaderAndFooterColors() }, 800L)
    }

    private fun samplePageHeaderAndFooterColors() {
        if (!hasDismissedSplash || splashOverlay.visibility == View.VISIBLE) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            webView.isAttachedToWindow &&
            webView.width > 0 &&
            webView.height > 24 &&
            hasWindowFocus()
        ) {
            sampleStripWithPixelCopy(isTop = true) { color ->
                if (color != null) {
                    applyStatusBarColor(color)
                } else {
                    sampleHeaderViaDom()
                }
            }
            if (!isImeVisible) {
                sampleStripWithPixelCopy(isTop = false) { color ->
                    if (color != null) {
                        applyNavBarColor(color)
                    }
                }
            }
        } else {
            sampleHeaderViaDom()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun sampleStripWithPixelCopy(isTop: Boolean, onResult: (Int?) -> Unit) {
        val loc = IntArray(2)
        webView.getLocationInWindow(loc)
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 24 || loc[1] < 0) {
            onResult(null)
            return
        }

        val stripHeight = 6
        val offsetPx = dpToPx(6)
        val yInWindow = if (isTop) {
            loc[1] + offsetPx
        } else {
            loc[1] + height - offsetPx - stripHeight
        }
        val rect = Rect(loc[0], yInWindow, loc[0] + width, yInWindow + stripHeight)
        if (rect.width() <= 0 || rect.height() <= 0) {
            onResult(null)
            return
        }

        val bitmap = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                window,
                rect,
                bitmap,
                { copyResult ->
                    val sampled = if (copyResult == PixelCopy.SUCCESS) {
                        extractDominantStripColor(bitmap)
                    } else {
                        null
                    }
                    bitmap.recycle()
                    onResult(sampled)
                },
                mainHandler
            )
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            onResult(null)
        }
    }

    private fun extractDominantStripColor(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val xFractions = floatArrayOf(0.06f, 0.18f, 0.35f, 0.50f, 0.65f, 0.82f, 0.94f)
        val yRows = intArrayOf(0, h / 2, h - 1)

        data class Bucket(var count: Int = 0, var rSum: Int = 0, var gSum: Int = 0, var bSum: Int = 0)
        val buckets = HashMap<Int, Bucket>()

        for (xf in xFractions) {
            val x = (w * xf).toInt().coerceIn(0, w - 1)
            for (y in yRows) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 200) continue
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                val bucket = buckets.getOrPut(key) { Bucket() }
                bucket.count++
                bucket.rSum += r
                bucket.gSum += g
                bucket.bSum += b
            }
        }

        val dominant = buckets.values.maxByOrNull { it.count } ?: return null
        return Color.rgb(
            dominant.rSum / dominant.count,
            dominant.gSum / dominant.count,
            dominant.bSum / dominant.count
        )
    }

    private fun sampleHeaderViaDom() {
        val script = """
            (function() {
              try {
                function parseRgba(str) {
                  var m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([0-9.]+))?\s*\)/.exec(str);
                  if (!m) return null;
                  return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] !== undefined ? Number(m[4]) : 1];
                }
                function blendOver(fg, bg) {
                  var a = fg[3];
                  return [
                    Math.round(fg[0] * a + bg[0] * (1 - a)),
                    Math.round(fg[1] * a + bg[1] * (1 - a)),
                    Math.round(fg[2] * a + bg[2] * (1 - a)),
                    1
                  ];
                }
                var xs = [0.1, 0.5, 0.9];
                for (var i = 0; i < xs.length; i++) {
                  var els = document.elementsFromPoint(window.innerWidth * xs[i], 8) || [];
                  var layers = [];
                  for (var j = 0; j < els.length; j++) {
                    var c = parseRgba(window.getComputedStyle(els[j]).backgroundColor);
                    if (c && c[3] > 0.02) layers.push(c);
                  }
                  if (layers.length > 0) {
                    var acc = [255, 255, 255, 1];
                    for (var k = layers.length - 1; k >= 0; k--) {
                      acc = blendOver(layers[k], acc);
                    }
                    return 'rgb(' + acc[0] + ',' + acc[1] + ',' + acc[2] + ')';
                  }
                }
                return '';
              } catch (e) {
                return '';
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            val cleaned = raw?.trim()?.trim('"')?.trim() ?: return@evaluateJavascript
            val parsedColor = parseCssColor(cleaned) ?: return@evaluateJavascript
            applyStatusBarColor(parsedColor)
        }
    }

    private fun parseCssColor(value: String): Int? {
        if (value.isEmpty() || value == "transparent") return null
        if (value.startsWith("#")) {
            return try {
                Color.parseColor(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val rgbMatch = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([0-9.]+))?\s*\)""")
            .find(value) ?: return null
        val r = rgbMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val g = rgbMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val b = rgbMatch.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val alpha = rgbMatch.groupValues[4].toFloatOrNull() ?: 1f
        if (alpha <= 0.05f) return null
        return Color.rgb(r, g, b)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SPLASH_DISMISSED, hasDismissedSplash)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        statusAnimator?.cancel()
        navAnimator?.cancel()
        popupDialog?.dismiss()
        popupDialog = null
        super.onDestroy()
    }
}
