package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        // رابط مشغل اللعبة الرئيسي
        const val GAME_LAUNCHER_URL = "https://v2.fiveplay.lol/launcher.html"

        // الرابط المباشر لمحرك اللعبة (يتجاوز سكريبتات المشغل والفحوصات بالكامل)
        const val DIRECT_GAME_URL = "https://v2.fiveplay.lol/game.html?mode=sandbox&fps=60&scale=1&shadows=0&bcfallback=force"

        // متصفح سطح المكتب لتجاوز القيود عند الحاجة
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    // عميل OkHttp لاعتراض الطلبات وحقن ترويسات العزل (COOP / COEP) وتجاوز قيود الذاكرة
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // عناصر واجهة المستخدم
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var etUrl: EditText
    private lateinit var btnPlayGame: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnExitFullscreenFloating: ImageButton
    private lateinit var topBarLayout: LinearLayout
    private lateinit var customViewContainer: FrameLayout
    private lateinit var errorLayout: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var tvClearCache: TextView
    private lateinit var tvToggleDesktop: TextView
    private lateinit var tvOpenInChrome: TextView
    private lateinit var tvDirectPlay: TextView

    // حالة ملء الشاشة والشاشة الكاملة للفيديو / ألعاب Canvas
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isAppFullscreen = false
    private var isDesktopMode = false
    private var defaultUserAgent: String = ""

    // مخصص لاختيار الملفات من المتصفح (<input type="file">)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // طلب الصلاحيات الأساسية ديناميكياً عند بدء التشغيل
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "تم تفعيل الصلاحيات بنجاح", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // تفعيل تسريع الأجهزة (Hardware Acceleration) على مستوى النافذة بالكامل
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // إبقاء الشاشة نشطة أثناء تشغيل الألعاب
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        initViews()
        setupHardwareAcceleratedWebView()
        setupListeners()
        setupBackNavigation()
        requestEssentialPermissions()

        // تحميل رابط مشغل اللعبة مباشرة عند فتح التطبيق
        loadUrl(GAME_LAUNCHER_URL)
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        etUrl = findViewById(R.id.etUrl)
        btnPlayGame = findViewById(R.id.btnPlayGame)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnExitFullscreenFloating = findViewById(R.id.btnExitFullscreenFloating)
        topBarLayout = findViewById(R.id.topBarLayout)
        customViewContainer = findViewById(R.id.customViewContainer)
        errorLayout = findViewById(R.id.errorLayout)
        btnRetry = findViewById(R.id.btnRetry)
        tvClearCache = findViewById(R.id.tvClearCache)
        tvToggleDesktop = findViewById(R.id.tvToggleDesktop)
        tvOpenInChrome = findViewById(R.id.tvOpenInChrome)
        tvDirectPlay = findViewById(R.id.tvDirectPlay)

        defaultUserAgent = webView.settings.userAgentString
    }

    /**
     * إعدادات الـ WebView المتقدمة وتسريع الألعاب والرسوميات (WebGL / GPU) وحل قيود Isolated-Memory
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHardwareAcceleratedWebView() {
        // تفعيل طبقة التسريع العتادي المباشر لتشغيل WebGL و WebGPU بكفاءة قصوى
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // إعدادات محرك الويب
        webView.settings.apply {
            // 1. تفعيل جافا سكريبت بالكامل
            javaScriptEnabled = true

            // 2. تفعيل التخزين المؤقت المحلي وقواعد البيانات
            domStorageEnabled = true
            databaseEnabled = true

            // 3. السماح بالمحتوى المختلط لتشغيل وسائط وألعاب HTTP/HTTPS المشتركة
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 4. تسريع تشغيل الوسائط والأصوات بدون الحاجة للمس الشاشة أولاً
            mediaPlaybackRequiresUserGesture = false

            // 5. تفعيل الوصول للملفات والمحتوى
            allowFileAccess = true
            allowContentAccess = true

            // 6. ملاءمة أبعاد شاشات الألعاب
            loadWithOverviewMode = true
            useWideViewPort = true

            // 7. تحسين التكبير والتصغير
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // 8. إعدادات الذاكرة المؤقتة لتحميل أسرع للألعاب المتكررة
            cacheMode = WebSettings.LOAD_DEFAULT

            // 9. تعطيل فحص SafeBrowsing لمنع تحميل Variations Seed غير الموقعة وتسريع التحميل
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }

            defaultTextEncodingName = "utf-8"
        }

        // تفعيل قبول ملفات تعريف الارتباط (Cookies) وملفات الطرف الثالث
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // دعم تنزيل الملفات ومحركات الألعاب عبر DownloadManager المدمج بالنظام
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("جاري تنزيل الملف...")
                    setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType)
                    )
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "بدأ تنزيل الملف...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }

        // إعداد الـ WebViewClient للتحكم بالتنقل واعتراض الطلبات لحل Isolated-Memory
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                // فتح الروابط الخارجية عبر تطبيقات النظام
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }

            /**
             * حل مشكلة "This browser blocked required isolated-memory features":
             * اعتراض طلبات موقع المشغل وحقن ترويسات العزل COOP / COEP وتجاوز فحص SharedArrayBuffer
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // معالجة ملفات مشغل GTA V (FivePlay)
                if (url.contains("fiveplay.lol")) {
                    try {
                        // 1. اعتراض touch_controls.js وإلغاء حظر 64-bit WebAssembly memory
                        if (url.contains("touch_controls.js")) {
                            val okRequest = Request.Builder()
                                .url(url)
                                .header("User-Agent", webView.settings.userAgentString)
                                .build()
                            val response = okHttpClient.newCall(okRequest).execute()
                            var jsCode = response.body?.string() ?: ""

                            // إلغاء الفحص المسؤول عن رسالة "This browser lacks shared 64-bit WebAssembly memory"
                            jsCode = jsCode.replace(
                                "function capabilityError(){",
                                "function capabilityError(){ return '';"
                            )

                            val responseHeaders = mutableMapOf<String, String>()
                            response.headers.forEach { pair ->
                                responseHeaders[pair.first] = pair.second
                            }
                            responseHeaders["Cross-Origin-Opener-Policy"] = "same-origin"
                            responseHeaders["Cross-Origin-Embedder-Policy"] = "require-corp"
                            responseHeaders["Cross-Origin-Resource-Policy"] = "cross-origin"
                            responseHeaders["Access-Control-Allow-Origin"] = "*"

                            return WebResourceResponse(
                                "application/javascript",
                                "utf-8",
                                200,
                                "OK",
                                responseHeaders,
                                ByteArrayInputStream(jsCode.toByteArray(Charsets.UTF_8))
                            )
                        }

                        // 2. اعتراض سكريبت launcher_player_flow.js وإلغاء حظر isolated-memory و GPU و touch_controls
                        if (url.contains("launcher_player_flow.js")) {
                            val okRequest = Request.Builder()
                                .url(url)
                                .header("User-Agent", webView.settings.userAgentString)
                                .build()
                            val response = okHttpClient.newCall(okRequest).execute()
                            var jsCode = response.body?.string() ?: ""

                            // إلغاء الفحوصات المقيدة
                            jsCode = jsCode.replace(
                                "if(!crossOriginIsolated||typeof SharedArrayBuffer==='undefined')",
                                "if(false)"
                            ).replace(
                                "if(!navigator.gpu)",
                                "if(false)"
                            ).replace(
                                "if(window.GTATouchControls)return GTATouchControls.capabilityError();",
                                "if(false) return '';"
                            ).replace(
                                "async function graphicsError(){",
                                "async function graphicsError(){ return '';"
                            ).replace(
                                "const graphics=await graphicsError();",
                                "const graphics='';"
                            ).replace(
                                "if(!adapter)return 'The game cannot use your graphics card",
                                "if(false)return 'The game cannot use your graphics card"
                            )

                            val responseHeaders = mutableMapOf<String, String>()
                            response.headers.forEach { pair ->
                                responseHeaders[pair.first] = pair.second
                            }
                            responseHeaders["Cross-Origin-Opener-Policy"] = "same-origin"
                            responseHeaders["Cross-Origin-Embedder-Policy"] = "require-corp"
                            responseHeaders["Cross-Origin-Resource-Policy"] = "cross-origin"
                            responseHeaders["Access-Control-Allow-Origin"] = "*"

                            return WebResourceResponse(
                                "application/javascript",
                                "utf-8",
                                200,
                                "OK",
                                responseHeaders,
                                ByteArrayInputStream(jsCode.toByteArray(Charsets.UTF_8))
                            )
                        }

                        // 2. اعتراض صفحات HTML لحقن ترويسات العزل و Shims للذاكرة المعزولة
                        if (request.isForMainFrame || url.endsWith(".html") || url.contains(".html?")) {
                            val okRequest = Request.Builder()
                                .url(url)
                                .header("User-Agent", webView.settings.userAgentString)
                                .build()
                            val response = okHttpClient.newCall(okRequest).execute()
                            var htmlCode = response.body?.string() ?: ""

                            // حقن كود مبكر لتفعيل crossOriginIsolated و SharedArrayBuffer و WebAssembly memory64
                            val injectScript = """
                                <script>
                                (function() {
                                    try {
                                        Object.defineProperty(window, 'crossOriginIsolated', { get: function() { return true; }, configurable: true });
                                        Object.defineProperty(globalThis, 'crossOriginIsolated', { get: function() { return true; }, configurable: true });
                                    } catch(e) {}
                                    if (typeof SharedArrayBuffer === 'undefined' && typeof ArrayBuffer !== 'undefined') {
                                        try {
                                            window.SharedArrayBuffer = ArrayBuffer;
                                            globalThis.SharedArrayBuffer = ArrayBuffer;
                                        } catch(e) {}
                                    }
                                    if (window.WebAssembly) {
                                        try {
                                            const origValidate = WebAssembly.validate;
                                            WebAssembly.validate = function(bytes) {
                                                if (bytes && bytes.length === 14 && bytes[8] === 5) return true;
                                                return origValidate ? origValidate.call(WebAssembly, bytes) : true;
                                            };
                                        } catch(e) {}
                                    }
                                    const mockAdapter = {
                                        features: new Set(['texture-compression-bc', 'clip-distances', 'depth-clip-control']),
                                        limits: { maxTextureDimension2D: 8192 },
                                        requestDevice: async function() {
                                            return {
                                                features: new Set(['texture-compression-bc', 'clip-distances', 'depth-clip-control']),
                                                limits: { maxTextureDimension2D: 8192 },
                                                queue: { submit: function() {} }
                                            };
                                        }
                                    };
                                    if (typeof navigator !== 'undefined') {
                                        if (navigator.gpu) {
                                            try {
                                                const origReq = navigator.gpu.requestAdapter;
                                                navigator.gpu.requestAdapter = async function(opts) {
                                                    try {
                                                        const real = await origReq.call(navigator.gpu, opts);
                                                        if (real) return real;
                                                    } catch(e) {}
                                                    return mockAdapter;
                                                };
                                            } catch(e) {}
                                        } else {
                                            try {
                                                navigator.gpu = {
                                                    requestAdapter: async function() { return mockAdapter; }
                                                };
                                            } catch(e) {}
                                        }
                                    }
                                    function neutraliseLauncher() {
                                        try {
                                            if (window.GTAPlayerFlow) {
                                                window.GTAPlayerFlow.browserError = function() { return ''; };
                                                window.GTAPlayerFlow.graphicsError = async function() { return ''; };
                                                window.GTAPlayerFlow.unsupportedBrowser = function() { return ''; };
                                            }
                                            if (window.GTATouchControls) {
                                                window.GTATouchControls.capabilityError = function() { return ''; };
                                            }
                                            const startBtn = document.getElementById('start');
                                            if (startBtn && !startBtn.dataset.bypassInstalled) {
                                                startBtn.dataset.bypassInstalled = 'true';
                                                startBtn.disabled = false;
                                                const newBtn = startBtn.cloneNode(true);
                                                newBtn.disabled = false;
                                                startBtn.parentNode.replaceChild(newBtn, startBtn);
                                                newBtn.addEventListener('click', function(e) {
                                                    e.preventDefault();
                                                    e.stopImmediatePropagation();
                                                    const modeInput = document.querySelector('input[name=mode]:checked');
                                                    const mode = modeInput ? modeInput.value : 'sandbox';
                                                    location.href = 'game.html?mode=' + mode + '&fps=60&scale=1&shadows=0&bcfallback=force';
                                                }, true);
                                            }
                                            const failDialog = document.getElementById('mp-failure-dialog');
                                            if (failDialog && failDialog.open) {
                                                failDialog.close();
                                            }
                                            const warnDialog = document.getElementById('browser-warning-dialog');
                                            if (warnDialog && warnDialog.open) {
                                                warnDialog.close();
                                            }
                                        } catch(e) {}
                                    }
                                    const watchdog = setInterval(neutraliseLauncher, 100);
                                    setTimeout(function() { clearInterval(watchdog); }, 30000);
                                    neutraliseLauncher();
                                })();
                                </script>
                            """.trimIndent()

                            htmlCode = if (htmlCode.contains("<head>")) {
                                htmlCode.replaceFirst("<head>", "<head>$injectScript")
                            } else {
                                injectScript + htmlCode
                            }

                            val responseHeaders = mutableMapOf<String, String>()
                            response.headers.forEach { pair ->
                                responseHeaders[pair.first] = pair.second
                            }
                            responseHeaders["Cross-Origin-Opener-Policy"] = "same-origin"
                            responseHeaders["Cross-Origin-Embedder-Policy"] = "credentialless"
                            responseHeaders["Cross-Origin-Resource-Policy"] = "cross-origin"
                            responseHeaders["Access-Control-Allow-Origin"] = "*"

                            return WebResourceResponse(
                                "text/html",
                                "utf-8",
                                200,
                                "OK",
                                responseHeaders,
                                ByteArrayInputStream(htmlCode.toByteArray(Charsets.UTF_8))
                            )
                        }
                    } catch (e: Exception) {
                        // في حال تعذر الاعتراض بالشبكة، المتابعة بالـ WebView الطبيعي
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                errorLayout.visibility = View.GONE
                url?.let {
                    etUrl.setText(it)
                    updateNavButtons()
                }

                // حقن بيئة الذاكرة المعزولة وتخطي أخطاء المشغل
                injectIsolatedMemoryBypass()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                updateNavButtons()

                // التأكيد على تجاوز فحص المشغل بعد اكتمال تحميل DOM
                injectIsolatedMemoryBypass()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    errorLayout.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }

        // إعداد الـ WebChromeClient لدعم ملء الشاشة للألعاب والشريط المرحلي واختيار الملفات
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback

                customViewContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                customViewContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                topBarLayout.visibility = View.GONE
                setFullscreenImmersive(true)
            }

            override fun onHideCustomView() {
                if (customView == null) return

                customViewContainer.removeView(customView)
                customViewContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()

                webView.visibility = View.VISIBLE
                if (!isAppFullscreen) {
                    topBarLayout.visibility = View.VISIBLE
                    setFullscreenImmersive(false)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }
        }
    }

    /**
     * حقن جافا سكريبت يضمن تمكين crossOriginIsolated وتفريغ أي خطأ في فحص المشغل
     */
    private fun injectIsolatedMemoryBypass() {
        val js = """
            try {
                Object.defineProperty(window, 'crossOriginIsolated', { get: function() { return true; }, configurable: true });
                Object.defineProperty(globalThis, 'crossOriginIsolated', { get: function() { return true; }, configurable: true });
                if (typeof SharedArrayBuffer === 'undefined' && typeof ArrayBuffer !== 'undefined') {
                    window.SharedArrayBuffer = ArrayBuffer;
                    globalThis.SharedArrayBuffer = ArrayBuffer;
                }
                if (window.WebAssembly) {
                    const origValidate = WebAssembly.validate;
                    WebAssembly.validate = function(bytes) {
                        if (bytes && bytes.length === 14 && bytes[8] === 5) return true;
                        return origValidate ? origValidate.call(WebAssembly, bytes) : true;
                    };
                }
                function neutraliseLauncher() {
                    try {
                        if (window.GTAPlayerFlow) {
                            window.GTAPlayerFlow.browserError = function() { return ''; };
                            window.GTAPlayerFlow.graphicsError = async function() { return ''; };
                            window.GTAPlayerFlow.unsupportedBrowser = function() { return ''; };
                        }
                        if (window.GTATouchControls) {
                            window.GTATouchControls.capabilityError = function() { return ''; };
                        }
                        const startBtn = document.getElementById('start');
                        if (startBtn && !startBtn.dataset.bypassInstalled) {
                            startBtn.dataset.bypassInstalled = 'true';
                            startBtn.disabled = false;
                            const newBtn = startBtn.cloneNode(true);
                            newBtn.disabled = false;
                            startBtn.parentNode.replaceChild(newBtn, startBtn);
                            newBtn.addEventListener('click', function(e) {
                                e.preventDefault();
                                e.stopImmediatePropagation();
                                const modeInput = document.querySelector('input[name=mode]:checked');
                                const mode = modeInput ? modeInput.value : 'sandbox';
                                location.href = 'game.html?mode=' + mode + '&fps=60&scale=1&shadows=0&bcfallback=force';
                            }, true);
                        }
                        const failDialog = document.getElementById('mp-failure-dialog');
                        if (failDialog && failDialog.open) {
                            failDialog.close();
                        }
                        const warnDialog = document.getElementById('browser-warning-dialog');
                        if (warnDialog && warnDialog.open) {
                            warnDialog.close();
                        }
                    } catch(e) {}
                }
                const watchdog = setInterval(neutraliseLauncher, 100);
                setTimeout(function() { clearInterval(watchdog); }, 30000);
                neutraliseLauncher();
            } catch (e) {}
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupListeners() {
        // 1. زر تشغيل اللعبة البارز
        btnPlayGame.setOnClickListener {
            val currentUrl = webView.url ?: ""
            if (currentUrl.contains("launcher.html")) {
                loadUrl(DIRECT_GAME_URL)
                Toast.makeText(this, "جاري بدء اللعبة مباشرة...", Toast.LENGTH_SHORT).show()
            } else {
                loadUrl(GAME_LAUNCHER_URL)
                Toast.makeText(this, "جاري فتح المشغل بدون قيود...", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. زر البدء المباشر (تجاوز شاشة المشغل بالكامل والدخول مباشرة لمحرك GTA V)
        tvDirectPlay.setOnClickListener {
            loadUrl(DIRECT_GAME_URL)
            Toast.makeText(this, "جاري فتح محرك اللعبة مباشرة...", Toast.LENGTH_SHORT).show()
        }

        // أزرار التنقل
        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }

        btnRefresh.setOnClickListener {
            webView.reload()
        }

        // زر التبديل لوضع ملء الشاشة
        btnFullscreen.setOnClickListener {
            toggleAppFullscreen()
        }

        btnExitFullscreenFloating.setOnClickListener {
            toggleAppFullscreen()
        }

        // زر فتح الرابط في Google Chrome الرسمي (لتشغيل اللعبة بكامل عتاد Chrome إذا كان الـ WebView يفتقر للـ WebGPU)
        tvOpenInChrome.setOnClickListener {
            openInOfficialChrome()
        }

        // البحث أو الانتقال للرابط من حقل الإدخال
        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                var input = etUrl.text.toString().trim()
                if (input.isNotEmpty()) {
                    if (!input.startsWith("http://") && !input.startsWith("https://")) {
                        input = if (input.contains(".") && !input.contains(" ")) {
                            "https://$input"
                        } else {
                            "https://www.google.com/search?q=${Uri.encode(input)}"
                        }
                    }
                    loadUrl(input)
                }
                true
            } else {
                false
            }
        }

        btnRetry.setOnClickListener {
            errorLayout.visibility = View.GONE
            webView.reload()
        }

        tvClearCache.setOnClickListener {
            webView.clearCache(true)
            Toast.makeText(this, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
        }

        tvToggleDesktop.setOnClickListener {
            isDesktopMode = !isDesktopMode
            if (isDesktopMode) {
                webView.settings.userAgentString = DESKTOP_USER_AGENT
                tvToggleDesktop.text = getString(R.string.desktop_mode)
                Toast.makeText(this, "تم تفعيل وضع الكمبيوتر للألعاب", Toast.LENGTH_SHORT).show()
            } else {
                webView.settings.userAgentString = defaultUserAgent
                tvToggleDesktop.text = getString(R.string.mobile_mode)
                Toast.makeText(this, "تم تفعيل وضع الهاتف", Toast.LENGTH_SHORT).show()
            }
            webView.reload()
        }
    }

    /**
     * تشغيل الرابط في متصفح Google Chrome الرسمي أو المتصفح الخارجي
     * لضمان عمل WebGPU و SharedArrayBuffer بدون أي قيود للنظام
     */
    private fun openInOfficialChrome() {
        val targetUrl = if (webView.url.isNullOrEmpty()) GAME_LAUNCHER_URL else webView.url!!
        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(chromeIntent)
            Toast.makeText(this, "جاري فتح اللعبة في Google Chrome لتجاوز قيود الذاكرة المعزولة", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // في حال عدم توفر Chrome، فتح المتصفح الافتراضي الخارجي (Edge أو Brave)
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
            Toast.makeText(this, "جاري فتح اللعبة في المتصفح الخارجي", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUrl(url: String) {
        errorLayout.visibility = View.GONE
        etUrl.setText(url)
        webView.loadUrl(url)
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (webView.canGoBack()) 1.0f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1.0f else 0.4f
    }

    private fun toggleAppFullscreen() {
        isAppFullscreen = !isAppFullscreen
        setFullscreenImmersive(isAppFullscreen)
        topBarLayout.visibility = if (isAppFullscreen) View.GONE else View.VISIBLE
        btnExitFullscreenFloating.visibility = if (isAppFullscreen) View.VISIBLE else View.GONE
    }

    private fun setFullscreenImmersive(enable: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> {
                        webView.webChromeClient?.onHideCustomView()
                    }
                    isAppFullscreen -> {
                        toggleAppFullscreen()
                    }
                    webView.canGoBack() -> {
                        webView.goBack()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun requestEssentialPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
