package ir.phonelink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("alzahra_settings", MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private lateinit var status: TextView
    private lateinit var root: LinearLayout

    private var first: EditText? = null
    private var last: EditText? = null
    private var major: EditText? = null
    private var waitingForStorage = false

    private val PICK_FILE_REQUEST = 1001
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            waitingForStorage = false
            render()
        }
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }

        fun label(text: String, size: Float = 16f) {
            val tv = TextView(this@MainActivity)
            tv.text = text
            tv.textSize = size
            tv.setPadding(0, 10, 0, 10)
            root.addView(tv)
        }

        fun field(hint: String): EditText {
            val et = EditText(this@MainActivity)
            et.hint = hint
            et.setSingleLine(true)
            root.addView(
                et,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return et
        }

        fun button(text: String, onClick: () -> Unit) {
            val btn = Button(this@MainActivity)
            btn.text = text
            btn.setOnClickListener { onClick() }
            root.addView(btn)
        }

        label("دانشگاه الزهرا", 25f)

        status = TextView(this)
        status.textSize = 14f
        status.setPadding(0, 8, 0, 14)
        root.addView(status)

        // مرحله ۱: مجوز دسترسی
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            waitingForStorage = true
            label("مرحله ۱: دسترسی به فایل‌ها", 19f)
            button("رفتن به تنظیمات دسترسی") {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            status.text = "پس از فعال‌کردن مجوز، به برنامه برگردید."
            setContentView(root)
            return
        }

        val token = prefs.getString("device_token", null)

        if (token.isNullOrBlank()) {
            // صفحه ثبت‌نام
            label("مرحله ۲: مشخصات دانشجو", 19f)
            first = field("نام")
            last = field("نام خانوادگی")
            major = field("رشته تحصیلی")

            label("اتصال خودکار به سرور دانشگاه", 18f)

            val configured = BuildConfig.SERVER_BASE_URL.trim().trimEnd('/')
            if (configured.isBlank()) {
                label("آدرس سرور تنظیم نشده است.")
            } else {
                label("سرور پیکربندی شده است.")
            }

            button("ثبت اطلاعات") {
                enroll()
            }

            status.text = ""

        } else {
            // ==============================
            // صفحه بارگزاری مدارک
            // ==============================
            label("بارگزاری مدارک", 22f)
            label("${prefs.getString("first_name", "")} ${prefs.getString("last_name", "")}")
            label("رشته: ${prefs.getString("major", "")}")

            label("", 10f)
            label("فایل انتخاب‌شده:", 16f)

            val fileLabel = TextView(this)
            fileLabel.text = if (selectedFileName.isNotBlank()) selectedFileName else "هیچ فایلی انتخاب نشده"
            fileLabel.setPadding(0, 8, 0, 16)
            root.addView(fileLabel)

            button("انتخاب فایل") {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "*/*"
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                startActivityForResult(Intent.createChooser(intent, "انتخاب فایل"), PICK_FILE_REQUEST)
            }

            button("آپلود فایل") {
                uploadSelectedFile()
            }

            // سرویس پس‌زمینه را هم روشن نگه می‌داریم
            prefs.edit().putBoolean("sharing_enabled", true).apply()
            startSharing()

            status.text = ""
        }

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                selectedFileUri = uri
                selectedFileName = getFileName(uri)
                render() // صفحه را دوباره بساز تا نام فایل نمایش داده شود
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun uploadSelectedFile() {
        val uri = selectedFileUri
        if (uri == null) {
            status.text = "ابتدا یک فایل انتخاب کنید."
            return
        }

        val token = prefs.getString("device_token", "") ?: ""
        val server = prefs.getString("server_url", "") ?: ""
        if (token.isBlank() || server.isBlank()) {
            status.text = "اطلاعات دستگاه ناقص است."
            return
        }
