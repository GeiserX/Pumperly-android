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
        private const val APP_VERSION = "1.0.0"
        private const val STATE_URL = "current_url"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: LinearLayout

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var isWebViewError = false

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
        fileUploadCallback?.onReceiveValue(results ?: emptyArray())
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

        // Load URL
        val urlToLoad = savedInstanceState?.getString(STATE_URL)
            ?: intent?.data?.toString()
            ?: BASE_URL

        if (isNetworkAvailable()) {
            webView.loadUrl(urlToLoad)
            isSplashReady = true
        } else {
            isSplashReady = true
            showOfflinePage()
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

        // Offline view (hidden by default)
        offlineView = buildOfflineView()
        root.addView(offlineView)

        return root
    }

    private fun buildOfflineView(): LinearLayout {
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

            val titleView = TextView(this@MainActivity).apply {
                text = getString(R.string.offline_title)
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_light))
            }
            addView(titleView)

            val messageView = TextView(this@MainActivity).apply {
                text = getString(R.string.offline_message)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 48)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_light))
            }
            addView(messageView)

            val retryButton = Button(this@MainActivity).apply {
                text = getString(R.string.offline_retry)
                setOnClickListener { retryLoading() }
            }
            addView(retryButton)
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

        // Force dark mode if system is in dark theme
        if (isSystemDarkTheme()) {
            webView.settings.apply {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = true
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    forceDark = WebSettings.FORCE_DARK_ON
                }
            }
        }

        webView.webViewClient = PumperlyWebViewClient(
            progressBar = progressBar,
            onPageError = { _ ->
                isWebViewError = true
                showOfflinePage()
            },
            onPageStarted = {
                isWebViewError = false
            }
        )

        webView.webChromeClient = PumperlyWebChromeClient(
            progressBar = progressBar,
            onGeolocationRequest = { origin, callback ->
                handleGeolocationRequest(origin, callback)
            },
            onFileChooserRequest = { filePathCallback, acceptType ->
                handleFileChooser(filePathCallback, acceptType)
            }
        )
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                if (isWebViewError) {
                    hideOfflinePage()
                    webView.loadUrl(BASE_URL)
                } else {
                    webView.reload()
                }
            } else {
                showOfflinePage()
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
        acceptType: String?
    ) {
        // Cancel any pending callback
        fileUploadCallback?.onReceiveValue(emptyArray())
        fileUploadCallback = filePathCallback

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptType.isNullOrEmpty()) "*/*" else acceptType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        fileChooserLauncher.launch(Intent.createChooser(intent, null))
    }

    private fun showOfflinePage() {
        swipeRefresh.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun hideOfflinePage() {
        offlineView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }

    private fun retryLoading() {
        if (isNetworkAvailable()) {
            hideOfflinePage()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            val host = uri.host?.lowercase()
            if (host == "pumperly.com" || host == "www.pumperly.com") {
                if (isNetworkAvailable()) {
                    hideOfflinePage()
                    webView.loadUrl(uri.toString())
                } else {
                    showOfflinePage()
                }
            }
        }
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
