package com.example.template

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView
    private var isFirstPageLoaded = false
    private var backPressedTime: Long = 0L
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    // รับผลลัพธ์จากการเลือกไฟล์หรือถ่ายรูป
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        } else {
            callback.onReceiveValue(null)
        }
        cameraImageUri = null
    }

    // ขอสิทธิ์กล้องสำหรับ WebRTC (เช่น สแกน QR Code บนหน้าเว็บ)
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val request = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (isGranted) {
            request?.grant(request.resources)
        } else {
            request?.deny()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 1) ติดตั้ง Splash Screen (ต้องอยู่ก่อน super.onCreate)
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // ค้างหน้า Splash Screen จนกว่าเว็บจะโหลดเสร็จ (หรือสูงสุด 2.5 วินาที)
        splashScreen.setKeepOnScreenCondition { !isFirstPageLoaded }
        Handler(Looper.getMainLooper()).postDelayed({
            isFirstPageLoaded = true
        }, 2500)

        enableFullScreen()

        // 2) สร้าง SwipeRefreshLayout ครอบ WebView
        swipeRefresh = SwipeRefreshLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        swipeRefresh.addView(webView)
        setContentView(swipeRefresh)

        // ลากลงเพื่อรีเฟรช (ทำงานเฉพาะตอนอยู่บนสุดของหน้าเว็บ)
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0
        }

        // 3) ตั้งค่า WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        // ลงทะเบียน JavaScript Interface สำหรับรับไฟล์ Base64 จาก blob: / data:
        webView.addJavascriptInterface(BlobDownloaderInterface(), "AndroidBlobDownloader")

        // 4) จัดการลิงก์ภายนอก + ปิด Splash Screen และตัวหมุนรีเฟรช
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()

                // รองรับ intent://
                if (url.startsWith("intent://")) {
                    return try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                        } else {
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrEmpty()) {
                                view?.loadUrl(fallbackUrl)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                // ลิงก์โทรศัพท์, อีเมล, SMS, แผนที่ (geo:) และ LINE scheme
                val isExternalScheme = url.startsWith("tel:") ||
                    url.startsWith("mailto:") ||
                    url.startsWith("sms:") ||
                    url.startsWith("geo:") ||
                    url.startsWith("line:")

                // ลิงก์เว็บของ LINE และ Google Maps
                val isExternalAppLink = url.startsWith("https://line.me/") ||
                    url.startsWith("https://lin.ee/") ||
                    url.startsWith("https://maps.google.com/") ||
                    url.startsWith("https://www.google.com/maps") ||
                    url.startsWith("https://maps.app.goo.gl/") ||
                    url.startsWith("https://goo.gl/maps/")

                if (isExternalScheme || isExternalAppLink) {
                    return openExternalApp(uri)
                }

                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isFirstPageLoaded = true
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    isFirstPageLoaded = true
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        // 5) จัดการเลือกไฟล์, ถ่ายรูป และสิทธิ์กล้องสด (WebRTC)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val cameraIntent = createCameraIntent()
                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }
                    if (!acceptTypes.isNullOrEmpty()) {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                    }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "เลือกไฟล์หรือถ่ายภาพ")
                    if (cameraIntent != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                }

                fileChooserLauncher.launch(chooserIntent)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val hasCamera = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (hasCamera) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        // 6) จัดการดาวน์โหลดไฟล์ทั้ง http/https, blob: และ data: URL
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("http://") || url.startsWith("https://") -> {
                    downloadHttpFile(url, userAgent, contentDisposition, mimeType)
                }
                url.startsWith("blob:") -> {
                    val fileName = buildSafeFileName(url, contentDisposition, mimeType)
                    val safeUrl = JSONObject.quote(url)
                    val safeName = JSONObject.quote(fileName)
                    val safeMime = JSONObject.quote(mimeType ?: "application/octet-stream")
                    val js = """
                        (function() {
                            fetch($safeUrl)
                                .then(res => res.blob())
                                .then(blob => {
                                    var reader = new FileReader();
                                    reader.onloadend = function() {
                                        AndroidBlobDownloader.saveBase64File(reader.result, $safeName, blob.type || $safeMime);
                                    };
                                    reader.readAsDataURL(blob);
                                })
                                .catch(err => AndroidBlobDownloader.onError(err.toString()));
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
                url.startsWith("data:") -> {
                    val fileName = buildSafeFileName(url, contentDisposition, mimeType)
                    saveBase64ToDownloads(url, fileName, mimeType ?: "application/octet-stream")
                }
                else -> {
                    Toast.makeText(this@MainActivity, "ไม่รองรับการดาวน์โหลดลิงก์รูปแบบนี้", Toast.LENGTH_SHORT).show()
                }
            }
        }

        webView.loadUrl(getString(R.string.app_url))

        // 7) กดปุ่ม Back เพื่อย้อนหน้าเว็บ หรือกด 2 ครั้งภายใน 2 วินาทีเพื่อออกจากแอป
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = currentTime
                        Toast.makeText(
                            this@MainActivity,
                            "กดปุ่มย้อนกลับอีกครั้งเพื่อออกจากแอป",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    inner class BlobDownloaderInterface {
        @JavascriptInterface
        fun saveBase64File(dataUrl: String, fileName: String, mimeType: String) {
            saveBase64ToDownloads(dataUrl, fileName, mimeType)
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "ดาวน์โหลดไม่สำเร็จ: $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadHttpFile(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("cookie", cookies)
                }
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("กำลังดาวน์โหลดไฟล์...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "กำลังดาวน์โหลด $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่สามารถดาวน์โหลดไฟล์ได้", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBase64ToDownloads(dataUrl: String, rawFileName: String, rawMimeType: String) {
        try {
            val headerMime = if (dataUrl.startsWith("data:") && dataUrl.contains(";")) {
                dataUrl.substringAfter("data:").substringBefore(";")
            } else {
                rawMimeType
            }
            val mimeType = headerMime.ifBlank { "application/octet-stream" }
            val base64Part = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
            val fileBytes = Base64.decode(base64Part, Base64.DEFAULT)

            val fileName = ensureFileExtension(rawFileName, mimeType)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("ไม่สามารถสร้างไฟล์ใน Downloads ได้")

                contentResolver.openOutputStream(uri)?.use { it.write(fileBytes) }

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, fileName)
                FileOutputStream(outFile).use { it.write(fileBytes) }
            }

            runOnUiThread {
                Toast.makeText(this, "บันทึก $fileName ลงโฟลเดอร์ Downloads แล้ว", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "บันทึกไฟล์ไม่สำเร็จ: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildSafeFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        return if (guessed.isBlank() || guessed == "downloadfile.bin") {
            "file_${System.currentTimeMillis()}"
        } else {
            guessed
        }
    }

    private fun ensureFileExtension(fileName: String, mimeType: String): String {
        if (fileName.contains(".") && !fileName.endsWith(".bin")) return fileName
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (!ext.isNullOrBlank()) {
            "${fileName.substringBeforeLast(".")}.$ext"
        } else {
            fileName
        }
    }

    private fun openExternalApp(uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่พบแอปที่รองรับลิงก์นี้ในเครื่อง", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val imageFile = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreen()
    }

    private fun enableFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
