package com.pumperly.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pumperly.app.core.AppConfig
import com.pumperly.app.core.HttpNearestApi
import com.pumperly.app.core.Locator
import com.pumperly.app.core.NearbyPrefetcher
import com.pumperly.app.core.SharedSettings
import com.pumperly.app.core.StationCache
import com.pumperly.app.core.UserAgent
import com.pumperly.app.shell.NavigationPolicy
import com.pumperly.app.shell.ShellError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : ComponentActivity() {

    companion object {
        private const val BASE_URL = AppConfig.BASE_URL
        private val APP_VERSION = AppConfig.appVersion
        private const val STATE_URL = "current_url"
        private const val STATE_PENDING_URL = "pending_url"
        private const val STATE_ERROR = "current_error"

        private fun isAllowedUrl(url: String): Boolean = NavigationPolicy.isAllowed(url)
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    // SwipeRefreshLayout remembers its first child for good, so the WebView sits in this frame
    // and can be swapped after a renderer crash without leaving the refresh layout on a dead view.
    private lateinit var webViewFrame: FrameLayout
    private lateinit var errorView: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorButton: Button

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var currentError: ShellError? = null
    private var pendingUrl: String? = null

    // Background work of this screen (the nearby prefetch) ends with the Activity.
    private val backgroundScope by lazy { CoroutineScope(lifecycleScope.coroutineContext + Dispatchers.IO) }
    private val settings by lazy { SharedSettings.forContext(this) }
    private val stationCache by lazy { StationCache.forContext(this) }
    private val prefetcher by lazy { NearbyPrefetcher(settings, stationCache, HttpNearestApi(), backgroundScope) }
    private var locationJob: Job? = null

    // Activity result launcher for file chooser
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results = if (result.resultCode == RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        } else {
            null
        }
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    // Permission request launcher for location
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val granted = fineGranted || coarseGranted
        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep splash until webview starts loading
        var isSplashReady = false
        splashScreen.setKeepOnScreenCondition { !isSplashReady }

        // Build the layout programmatically
        val rootLayout = buildLayout()
        setContentView(rootLayout)

        // Apply window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        // Restore state after process death
        val restoredPendingUrl = savedInstanceState?.getString(STATE_PENDING_URL)
        val restoredError = ShellError.fromName(savedInstanceState?.getString(STATE_ERROR))

        // Load URL — validate intent data against allowlist
        val intentUrl = intent?.data?.toString()
        val restoredUrl = savedInstanceState?.getString(STATE_URL)
        val urlToLoad = when {
            restoredUrl != null && isAllowedUrl(restoredUrl) -> restoredUrl
            intentUrl != null && isAllowedUrl(intentUrl) -> intentUrl
            else -> BASE_URL
        }

        if (restoredError != null) {
            pendingUrl = restoredPendingUrl ?: urlToLoad
            isSplashReady = true
            showErrorPage(restoredError)
        } else if (isNetworkAvailable()) {
            webView.loadUrl(urlToLoad)
            isSplashReady = true
        } else {
            pendingUrl = urlToLoad
            isSplashReady = true
            showErrorPage(ShellError.OFFLINE)
        }
    }

    private fun buildLayout(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                6
            )
            max = 100
            visibility = View.GONE
            progressDrawable.setColorFilter(
                ContextCompat.getColor(this@MainActivity, R.color.pumperly_green),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }
        root.addView(progressBar)

        // Swipe refresh wrapping the WebView
        swipeRefresh = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setColorSchemeResources(R.color.pumperly_green)
        }

        webView = newWebView()
        webViewFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(webView)
        }
        swipeRefresh.addView(webViewFrame)
        // Pull to refresh only when the page itself is scrolled to the top.
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
        root.addView(swipeRefresh)

        // Error view (hidden by default)
        errorView = buildErrorView()
        root.addView(errorView)

        return root
    }

    private fun newWebView(): WebView = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * The renderer of [gone] died (crash or low-memory kill). That WebView can never draw again,
     * so it is swapped for a fresh one that reloads the page it was showing.
     */
    private fun replaceDeadWebView(gone: WebView) {
        if (gone !== webView) return
        val url = gone.url?.takeIf { isAllowedUrl(it) } ?: pendingUrl ?: BASE_URL
        webViewFrame.removeView(gone)
        gone.destroy()
        webView = newWebView()
        webViewFrame.addView(webView)
        setupWebView()
        if (currentError == null) webView.loadUrl(url) else pendingUrl = url
    }

    private fun buildErrorView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(48, 48, 48, 48)

            val textColor = if (isSystemDarkTheme())
                ContextCompat.getColor(this@MainActivity, R.color.on_surface_dark)
            else
                ContextCompat.getColor(this@MainActivity, R.color.on_surface_light)

            errorTitle = TextView(this@MainActivity).apply {
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                setTextColor(textColor)
            }
            addView(errorTitle)

            errorMessage = TextView(this@MainActivity).apply {
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 48)
                setTextColor(textColor)
            }
            addView(errorMessage)

            errorButton = Button(this@MainActivity).apply {
                setOnClickListener { handleErrorAction() }
            }
            addView(errorButton)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true

            // Append custom user agent
            val defaultUa = userAgentString
            userAgentString = "$defaultUa ${UserAgent.webViewSuffix(APP_VERSION)}"
        }

        // No algorithmic darkening: pumperly.com has its own dark theme and follows
        // prefers-color-scheme, which the WebView takes from the app's DayNight theme. Letting
        // the WebView darken it as well dulled the site's own dark colours (checked on an
        // Android 15 emulator, see the shell-policy PR).

        webView.webViewClient = PumperlyWebViewClient(
            progressBar = progressBar,
            onError = { type, failingUrl ->
                pendingUrl = failingUrl ?: pendingUrl
                showErrorPage(type)
            },
            onPageStarted = {
                pendingUrl = null
                if (currentError != null) {
                    hideErrorPage()
                }
            },
            onRendererGone = { gone -> replaceDeadWebView(gone) }
        )

        webView.webChromeClient = PumperlyWebChromeClient(
            progressBar = progressBar,
            onGeolocationRequest = { origin, callback ->
                handleGeolocationRequest(origin, callback)
            },
            onFileChooserRequest = { filePathCallback, intent ->
                handleFileChooser(filePathCallback, intent)
            }
        )
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                if (currentError != null) {
                    hideErrorPage()
                    val url = pendingUrl ?: webView.url ?: BASE_URL
                    pendingUrl = null
                    webView.loadUrl(url)
                } else {
                    webView.reload()
                }
            } else {
                showErrorPage(ShellError.OFFLINE)
            }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun handleGeolocationRequest(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        // Only https pumperly.com origins get the location. Android does not say which frame asks,
        // so the origin (scheme and host) is the check.
        val originUri = try { Uri.parse(origin) } catch (_: Exception) { null }
        val mayShare = NavigationPolicy.mayShareLocation(
            isMainFrame = true,
            scheme = originUri?.scheme.orEmpty(),
            host = originUri?.host.orEmpty()
        )
        if (!mayShare) {
            callback.invoke(origin, false, false)
            return
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            callback.invoke(origin, true, false)
        } else {
            pendingGeolocationOrigin = origin
            pendingGeolocationCallback = callback
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun handleFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        chooserIntent: Intent
    ) {
        // Cancel any pending callback (null signals cancellation to WebView)
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = filePathCallback
        try {
            fileChooserLauncher.launch(chooserIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }

    private fun showErrorPage(type: ShellError) {
        currentError = type
        errorTitle.text = getString(type.title)
        errorMessage.text = getString(type.message)
        errorButton.text = getString(type.action)
        swipeRefresh.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun hideErrorPage() {
        currentError = null
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }

    private fun handleErrorAction() {
        when (currentError) {
            ShellError.SSL -> {
                // Go back in history or fall back to base URL
                hideErrorPage()
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    webView.loadUrl(BASE_URL)
                }
            }
            else -> retryLoading()
        }
    }

    private fun retryLoading() {
        if (isNetworkAvailable()) {
            hideErrorPage()
            val url = pendingUrl ?: webView.url ?: BASE_URL
            pendingUrl = null
            webView.loadUrl(url)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isSystemDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            val url = uri.toString()
            if (isAllowedUrl(url)) {
                if (isNetworkAvailable()) {
                    pendingUrl = null
                    hideErrorPage()
                    webView.loadUrl(url)
                } else {
                    pendingUrl = url
                    showErrorPage(ShellError.OFFLINE)
                }
            }
        }
    }

    private var nightMode: Int = Configuration.UI_MODE_NIGHT_UNDEFINED

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The WebView picks up the new prefers-color-scheme at once, but pumperly.com reads it
        // only when a page loads, so a light/dark switch reloads the page.
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightMode != nightMode) {
            nightMode = newNightMode
            if (currentError == null) webView.reload()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, webView.url)
        pendingUrl?.let { outState.putString(STATE_PENDING_URL, it) }
        currentError?.let { outState.putString(STATE_ERROR, it.name) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        refreshSavedLocation()
    }

    /**
     * One foreground fix each time the app comes to the front, saved (rounded) for the widget and
     * the offline screen and handed to the prefetcher. Never prompts: the page asks through the
     * WebView when it needs the location. Without permission (never given, or revoked, which
     * restarts the process, so this runs on that cold start) the saved position and the cached
     * stations are forgotten, as on iOS.
     */
    private fun refreshSavedLocation() {
        if (locationJob?.isActive == true) return
        locationJob = lifecycleScope.launch {
            when (val outcome = Locator.locate(this@MainActivity)) {
                is Locator.Outcome.Fix -> {
                    runCatching { settings.saveLocation(outcome.latitude, outcome.longitude) }
                    prefetcher.positionDelivered(outcome.latitude, outcome.longitude)
                }
                Locator.Outcome.Denied -> withContext(Dispatchers.IO) {
                    runCatching { settings.clearLocation() }
                    stationCache.clear()
                }
                Locator.Outcome.Unavailable -> Unit
            }
        }
    }

    override fun onPause() {
        // A fix still being searched for is not wanted once the app is off screen; the next
        // onResume starts a new one. Cancelling the job also stops the platform request.
        locationJob?.cancel()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
