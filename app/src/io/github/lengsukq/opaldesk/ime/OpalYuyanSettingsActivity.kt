package io.github.lengsukq.opaldesk.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Lightweight zero-Compose settings screen for `io.github.lengsukq.opaldesk.ime`
 * (`OpalDesk 语燕输入法插件`), accessible from Android System Keyboard Settings or OpalDesk.
 */
class OpalYuyanSettingsActivity : Activity() {

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        rebuildUi()
    }

    private fun rebuildUi() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val nativeRime = NativeYuyanRimeProbe.probeAndDeployIfPresent(this)
        val prefsCtx = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
        val prefs = prefsCtx.getSharedPreferences(OpalYuyanImeService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentSchema = YuyanSchema.fromId(
            prefs.getString(OpalYuyanImeService.KEY_DEFAULT_SCHEMA, YuyanSchema.PINYIN.id),
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F3F6FC"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        }

        root.addView(TextView(this).apply {
            text = "OpalDesk 语燕输入法插件"
            textSize = 21f
            setTextColor(Color.parseColor("#0F172A"))
            paint.isFakeBoldText = true
        })

        root.addView(TextView(this).apply {
            text = "基于开源语燕输入法（BSD-3-Clause）架构定制的可选跨屏输入组件。安装后 OpalDesk 主程序将通过同签名 SHA-256 校验自动启用跨屏光标候选栏与桌面软键盘，切回手机主屏自动恢复原输入法。"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, dp(8f), 0, dp(16f))
        })

        // Status Card
        val statusCard = makeCard().apply {
            addView(TextView(this@OpalYuyanSettingsActivity).apply {
                text = if (enabled) "系统启用状态：已在系统启用（进入 DeX 自动接管）" else "系统启用状态：尚未启用（OpalDesk 守护进程进入 DeX 时将自动启用，也可手动开启）"
                textSize = 14f
                setTextColor(if (enabled) Color.parseColor("#16A34A") else Color.parseColor("#D97706"))
                paint.isFakeBoldText = true
            })
            addView(TextView(this@OpalYuyanSettingsActivity).apply {
                text = if (nativeRime) {
                    "分词引擎：Native librime_jni + 离线多方案双引擎"
                } else {
                    "分词引擎：内置轻量离线多方案引擎（全拼 / 简拼 / 双拼 / 九宫格）"
                }
                textSize = 12.5f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(6f), 0, dp(10f))
            })
            addView(makeActionButton("打开系统输入法管理") {
                runCatching {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            })
        }
        root.addView(statusCard)

        // Default Schema Selector Card
        val schemaCard = makeCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14f) }

            addView(TextView(this@OpalYuyanSettingsActivity).apply {
                text = "默认中文输入方案"
                textSize = 15f
                setTextColor(Color.parseColor("#0F172A"))
                paint.isFakeBoldText = true
                setPadding(0, 0, 0, dp(8f))
            })

            YuyanSchema.entries.forEach { schema ->
                val selected = schema == currentSchema
                val row = TextView(this@OpalYuyanSettingsActivity).apply {
                    text = if (selected) "✓  ${schema.displayLabel}" else "    ${schema.displayLabel}"
                    textSize = 14f
                    setTextColor(if (selected) Color.parseColor("#007AFF") else Color.parseColor("#1E293B"))
                    paint.isFakeBoldText = selected
                    setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(if (selected) Color.parseColor("#E0EEFF") else Color.TRANSPARENT)
                    }
                    setOnClickListener {
                        prefs.edit().putString(OpalYuyanImeService.KEY_DEFAULT_SCHEMA, schema.id).apply()
                        Toast.makeText(
                            this@OpalYuyanSettingsActivity,
                            "已切换默认方案：${schema.displayLabel}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        rebuildUi()
                    }
                }
                addView(row)
            }
        }
        root.addView(schemaCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1f), Color.parseColor("#E2E8F0"))
        }
    }

    private fun makeActionButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13.5f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        paint.isFakeBoldText = true
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(Color.parseColor("#007AFF"))
        }
        setOnClickListener { onClick() }
    }
}
