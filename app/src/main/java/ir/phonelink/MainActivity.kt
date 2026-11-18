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
            root.addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = size
                setPadding(0, 10, 0, 10)
            })
        }

        fun field(hint: String, value: String = ""): EditText {
            val editText = EditText(this@MainActivity).apply {
                this.hint = hint
                setSingleLine(true)
                setText(value)
            }
            root.addView(
                editText, LinearLayout.LayoutParams(
