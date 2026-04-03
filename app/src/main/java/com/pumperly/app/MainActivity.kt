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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : ComponentActivity() {

    companion object {
        private const val BASE_URL = "https://pumperly.com"
        private val APP_VERSION = BuildConfig.VERSION_NAME
        private const val STATE_URL = "current_url"
        private val ALLOWED_HOSTS = setOf("pumperly.com", "www.pumperly.com")

        private fun isAllowedUrl(url: String): Boolean {
            return try {
                val uri = Uri.parse(url)
                uri.scheme == "https" && uri.host?.lowercase() in ALLOWED_HOSTS
            } catch (_: Exception) {
                false
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorView: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorButton: Button

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var currentError: WebViewError? = null

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

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        // Load URL — validate intent data against allowlist
        val intentUrl = intent?.data?.toString()
        val restoredUrl = savedInstanceState?.getString(STATE_URL)
        val urlToLoad = when {
            restoredUrl != null && isAllowedUrl(restoredUrl) -> restoredUrl
            intentUrl != null && isAllowedUrl(intentUrl) -> intentUrl
            else -> BASE_URL
        }

        if (isNetworkAvailable()) {
            webView.loadUrl(urlToLoad)
            isSplashReady = true
        } else {
            isSplashReady = true
            showErrorPage(WebViewError.OFFLINE)
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

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        swipeRefresh.addView(webView)
        root.addView(swipeRefresh)

        // Error view (hidden by default)
        errorView = buildErrorView()
        root.addView(errorView)

        return root
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
            databaseEnabled = true
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
            userAgentString = "$defaultUa PumperlyAndroid/$APP_VERSION"
        }

        applyDarkMode()

        webView.webViewClient = PumperlyWebViewClient(
            progressBar = progressBar,
            onError = { type, _ ->
                currentError = type
                showErrorPage(type)
            },
            onPageStarted = {
                if (currentError != null) {
                    currentError = null
                    hideErrorPage()
                }
            }
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
                    webView.loadUrl(BASE_URL)
                } else {
                    webView.reload()
                }
            } else {
                showErrorPage(WebViewError.OFFLINE)
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
        // Only grant geolocation to pumperly.com origins
        val originHost = try { Uri.parse(origin).host?.lowercase() } catch (_: Exception) { null }
        if (originHost !in ALLOWED_HOSTS) {
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
        fileChooserLauncher.launch(chooserIntent)
    }

    private fun showErrorPage(type: WebViewError) {
        when (type) {
            WebViewError.OFFLINE -> {
                errorTitle.text = getString(R.string.offline_title)
                errorMessage.text = getString(R.string.offline_message)
                errorButton.text = getString(R.string.offline_retry)
            }
            WebViewError.SSL -> {
                errorTitle.text = getString(R.string.ssl_error_title)
                errorMessage.text = getString(R.string.ssl_error_message)
                errorButton.text = getString(R.string.ssl_error_back)
            }
            WebViewError.PAGE -> {
                errorTitle.text = getString(R.string.page_error_title)
                errorMessage.text = getString(R.string.page_error_message)
                errorButton.text = getString(R.string.page_error_retry)
            }
        }
        swipeRefresh.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun hideErrorPage() {
        errorView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }

    private fun handleErrorAction() {
        when (currentError) {
            WebViewError.SSL -> {
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
            val currentUrl = webView.url ?: BASE_URL
            webView.loadUrl(currentUrl)
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

    @Suppress("DEPRECATION")
    private fun applyDarkMode() {
        val isDark = isSystemDarkTheme()
        webView.settings.apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = isDark
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            if (isAllowedUrl(uri.toString())) {
                if (isNetworkAvailable()) {
                    hideErrorPage()
                    webView.loadUrl(uri.toString())
                } else {
                    showErrorPage(WebViewError.OFFLINE)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDarkMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, webView.url)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
