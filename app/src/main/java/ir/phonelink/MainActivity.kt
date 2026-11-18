package ir.phonelink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("alzahra_settings", MODE_PRIVATE) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var status: TextView
    private lateinit var root: LinearLayout

    private var first: EditText? = null
    private var last: EditText? = null
    private var major: EditText? = null
    private var waitingForStorage = false

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

        // مرحله ۱: مجوز دسترسی به فایل‌ها
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

        // مرحله ۲: ثبت‌نام یا نمایش وضعیت
        if (prefs.getString("device_token", null).isNullOrBlank()) {
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
            label("اشتراک‌گذاری دستگاه", 19f)
            label("${prefs.getString("first_name", "")} ${prefs.getString("last_name", "")} — ${prefs.getString("major", "")}")
            label("سرور: ${prefs.getString("server_url", "")}")
            label("وضعیت: فعال")

            prefs.edit().putBoolean("sharing_enabled", true).apply()
            startSharing()

            status.text = ""
        }

        setContentView(root)
    }

    private fun enroll() {
        val a = first?.text?.toString()?.trim().orEmpty()
        val b = last?.text?.toString()?.trim().orEmpty()
        val c = major?.text?.toString()?.trim().orEmpty()
        val u = BuildConfig.SERVER_BASE_URL.trim().trimEnd('/')

        if (a.isBlank() || b.isBlank() || c.isBlank()) {
            status.text = "نام، نام خانوادگی و رشته را کامل کنید."
            return
        }
        if (!u.startsWith("https://")) {
            status.text = "آدرس سرور تنظیم نشده است."
            return
        }

        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString()
        val body = JSONObject()
            .put("deviceId", id)
            .put("firstName", a)
            .put("lastName", b)
            .put("major", c)
            .toString()
            .toRequestBody("application/json".toMediaType())

        status.text = "در حال ثبت..."

        http.newCall(
            Request.Builder()
                .url("$u/api/phone/enroll")
                .post(body)
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    status.text = "ثبت ناموفق: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val txt = response.body?.string().orEmpty()
                runOnUiThread {
                    if (response.isSuccessful) {
                        try {
                            val j = JSONObject(txt)
                            prefs.edit()
                                .putString("device_id", id)
                                .putString("device_token", j.getString("token"))
                                .putString("server_url", u)
                                .putString("first_name", a)
                                .putString("last_name", b)
                                .putString("major", c)
                                .putBoolean("sharing_enabled", true)
                                .apply()
                            startSharing()
                            render()
                        } catch (e: Exception) {
                            status.text = "خطا در پردازش پاسخ"
                        }
                    } else {
                        status.text = "ثبت ناموفق"
                    }
                }
            }
        })
    }

    private fun startSharing() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            render()
            return
        }

        prefs.edit().putBoolean("sharing_enabled", true).apply()
        startService(Intent(this, ShareService::class.java))
    }
}
