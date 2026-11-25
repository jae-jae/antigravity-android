package com.antigravity.webapp

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.webkit.JavascriptInterface
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
        private const val PREFS_NAME = "antigravity_prefs"
        private const val PREF_NOTIF_PROMPTED = "notif_prompted"
        private const val NOTIFICATION_CHANNEL_ID = "antigravity_web_notifications"
        private const val EXTRA_WEB_NOTIFICATION_ID = "extra_web_notification_id"

        private val NOTIFICATION_POLYFILL_JS = """
            (function() {
              if (window.__agNotificationPolyfilled) return;
              var bridge = window.AndroidNotificationBridge;
              if (!bridge) return;
              window.__agNotificationPolyfilled = true;
              window.__agNotifications = window.__agNotifications || {};
              window.__agPermWaiters = window.__agPermWaiters || [];
              var nextId = 1;

              window.__agResolveNotificationPermission = function(state) {
                var waiters = window.__agPermWaiters.splice(0, window.__agPermWaiters.length);
                for (var i = 0; i < waiters.length; i++) {
                  try { waiters[i](state); } catch (_) {}
                }
              };

              window.__agOnNotificationClick = function(id) {
                var inst = window.__agNotifications[id];
                if (!inst) return;
                try {
                  var ev = new Event('click');
                  if (typeof inst.onclick === 'function') inst.onclick(ev);
                  inst._dispatch('click', ev);
                } catch (_) {}
              };

              function AgNotification(title, options) {
                options = options || {};
                this._id = 'ag_' + (nextId++);
                this._listeners = {};
                this.title = String(title == null ? '' : title);
                this.body = options.body != null ? String(options.body) : '';
                this.tag = options.tag != null ? String(options.tag) : '';
                this.icon = options.icon != null ? String(options.icon) : '';
                this.data = options.data !== undefined ? options.data : null;
                this.silent = !!options.silent;
                this.onclick = null;
                this.onshow = null;
                this.onclose = null;
                this.onerror = null;

                window.__agNotifications[this._id] = this;
                var self = this;
                try {
                  bridge.showNotification(this._id, this.title, this.body, this.tag, this.icon, this.silent);
                  setTimeout(function() {
                    var ev = new Event('show');
                    if (typeof self.onshow === 'function') self.onshow(ev);
                    self._dispatch('show', ev);
                  }, 0);
                } catch (e) {
                  setTimeout(function() {
                    var ev = new Event('error');
                    if (typeof self.onerror === 'function') self.onerror(ev);
                    self._dispatch('error', ev);
                  }, 0);
                }
              }

              AgNotification.prototype.addEventListener = function(type, fn) {
                if (typeof fn !== 'function') return;
                this._listeners[type] = this._listeners[type] || [];
                this._listeners[type].push(fn);
              };

              AgNotification.prototype.removeEventListener = function(type, fn) {
                var arr = this._listeners[type];
                if (!arr) return;
                this._listeners[type] = arr.filter(function(f) { return f !== fn; });
              };

              AgNotification.prototype.dispatchEvent = function(ev) {
                if (!ev || !ev.type) return false;
                this._dispatch(ev.type, ev);
                return true;
              };

              AgNotification.prototype._dispatch = function(type, ev) {
                var arr = this._listeners[type];
                if (!arr) return;
                for (var i = 0; i < arr.length; i++) {
                  try { arr[i].call(this, ev); } catch (_) {}
                }
              };

              AgNotification.prototype.close = function() {
                delete window.__agNotifications[this._id];
                try { bridge.closeNotification(this._id); } catch (_) {}
                var ev = new Event('close');
                if (typeof this.onclose === 'function') this.onclose(ev);
                this._dispatch('close', ev);
              };

              Object.defineProperty(AgNotification, 'permission', {
                configurable: true,
                get: function() {
                  try { return bridge.getPermissionState(); } catch (_) { return 'default'; }
                }
              });

              Object.defineProperty(AgNotification, 'maxActions', {
                configurable: true,
                get: function() { return 2; }
              });

              AgNotification.requestPermission = function(cb) {
                var promise = new Promise(function(resolve) {
                  var current = 'default';
                  try { current = bridge.getPermissionState(); } catch (_) {}
                  if (current === 'granted') {
                    resolve('granted');
                    return;
                  }
                  window.__agPermWaiters.push(resolve);
                  try {
                    bridge.requestPermission();
                  } catch (_) {
                    window.__agResolveNotificationPermission('denied');
                  }
                });
                if (typeof cb === 'function') {
                  promise.then(function(res) { try { cb(res); } catch (_) {} });
                }
                return promise;
              };

              window.Notification = AgNotification;

              if (typeof ServiceWorkerRegistration !== 'undefined' && ServiceWorkerRegistration.prototype) {
                ServiceWorkerRegistration.prototype.showNotification = function(title, options) {
                  return new Promise(function(resolve, reject) {
                    try {
                      new AgNotification(title, options || {});
                      resolve();
                    } catch (err) {
                      reject(err);
                    }
                  });
                };
              }
            })();
        """.trimIndent()
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

    private val notificationIdSeq = AtomicInteger(1000)
    private val activeNotificationIds = ConcurrentHashMap<String, Int>()
    private val webIdToKey = ConcurrentHashMap<String, String>()
    private val largeNotificationIcon: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ic_antigravity_logo)
    }

    private val delayedSampleRunnable = Runnable { samplePageHeaderAndFooterColors() }
    private val splashTimeoutRunnable = Runnable { dismissSplashOverlay() }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val state = if (granted) "granted" else "denied"
        notifyJsPermissionResolved(state)
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

        createNotificationChannel()

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

        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val webId = intent?.getStringExtra(EXTRA_WEB_NOTIFICATION_ID) ?: return
        intent.removeExtra(EXTRA_WEB_NOTIFICATION_ID)
        val quotedId = JSONObject.quote(webId)
        mainHandler.post {
            webView.evaluateJavascript(
                "window.__agOnNotificationClick && window.__agOnNotificationClick($quotedId);",
                null
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_desc)
                enableLights(true)
                lightColor = Color.parseColor("#8AB4F8")
                enableVibration(true)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPostNotificationsIfNeeded(forcePrompt: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notifyJsPermissionResolved("granted")
            return
        }
        if (hasNotificationPermission()) {
            notifyJsPermissionResolved("granted")
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean(PREF_NOTIF_PROMPTED, false)
        if (!forcePrompt && alreadyPrompted) {
            return
        }
        prefs.edit().putBoolean(PREF_NOTIF_PROMPTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notifyJsPermissionResolved(state: String) {
        val quoted = JSONObject.quote(state)
        mainHandler.post {
            webView.evaluateJavascript(
                "window.__agResolveNotificationPermission && window.__agResolveNotificationPermission($quoted);",
                null
            )
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
                requestPostNotificationsIfNeeded(forcePrompt = false)
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
            targetWebView.addJavascriptInterface(NotificationBridge(), "AndroidNotificationBridge")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    targetWebView,
                    NOTIFICATION_POLYFILL_JS,
                    setOf("https://*")
                )
            }

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
                if (!isPopup) {
                    if (hasDismissedSplash) {
                        progressBar.visibility = View.VISIBLE
                    }
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                        url?.startsWith("https://") == true
                    ) {
                        view?.evaluateJavascript(NOTIFICATION_POLYFILL_JS, null)
                    }
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
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                        url?.startsWith("https://") == true
                    ) {
                        view?.evaluateJavascript(NOTIFICATION_POLYFILL_JS, null)
                    }
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

    private inner class NotificationBridge {

        @JavascriptInterface
        fun getPermissionState(): String {
            return if (hasNotificationPermission()) {
                "granted"
            } else {
                val prompted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_NOTIF_PROMPTED, false)
                if (prompted) "denied" else "default"
            }
        }

        @JavascriptInterface
        fun requestPermission() {
            mainHandler.post {
                val url = webView.url ?: ""
                if (!url.startsWith("https://")) {
                    notifyJsPermissionResolved("denied")
                    return@post
                }
                requestPostNotificationsIfNeeded(forcePrompt = true)
            }
        }

        @JavascriptInterface
        fun showNotification(
            webId: String,
            title: String,
            body: String,
            tag: String,
            iconUrl: String,
            silent: Boolean
        ) {
            mainHandler.post {
                val url = webView.url ?: ""
                if (!url.startsWith("https://") || !hasNotificationPermission()) {
                    return@post
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@post
                }

                val safeTitle = title.trim().ifEmpty { getString(R.string.app_name) }.take(120)
                val safeBody = body.trim().take(500)
                val key = tag.trim().ifEmpty { webId }
                val androidId = activeNotificationIds.getOrPut(key) {
                    notificationIdSeq.incrementAndGet()
                }
                webIdToKey[webId] = key

                val tapIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_WEB_NOTIFICATION_ID, webId)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this@MainActivity,
                    androidId,
                    tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(this@MainActivity, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_antigravity)
                    .setLargeIcon(largeNotificationIcon)
                    .setColor(Color.parseColor("#8AB4F8"))
                    .setContentTitle(safeTitle)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setSilent(silent)

                if (safeBody.isNotEmpty()) {
                    builder.setContentText(safeBody)
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
                }

                NotificationManagerCompat.from(this@MainActivity).notify(androidId, builder.build())
            }
        }

        @JavascriptInterface
        fun closeNotification(webId: String) {
            mainHandler.post {
                val key = webIdToKey.remove(webId) ?: webId
                val androidId = activeNotificationIds.remove(key) ?: return@post
                NotificationManagerCompat.from(this@MainActivity).cancel(androidId)
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
