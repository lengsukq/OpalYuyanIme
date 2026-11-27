package io.github.lengsukq.opaldesk.ime

import android.content.Context
import java.io.File

/**
 * Pluggable Rime / YuyanIme engine backend contract.
 *
 * Modeled on BSD-3-Clause [gurecn/YuyanIme](https://github.com/gurecn/YuyanIme) (`imesdk` /
 * `com.yuyan.inputmethod.core.Rime` wrapping `librime`):
 * - Automatically detects and loads `librime_jni.so` + `assets/rime/` YAML schemas when bundled
 *   into `ime-plugin/jniLibs/arm64-v8a` & `ime-plugin/assets/rime/`.
 * - Falls back seamlessly to [OpalYuyanEngine]'s pure-Kotlin multi-schema Pinyin / Shuangpin / T9
 *   engine when native `librime_jni.so` is absent, guaranteeing 100% out-of-the-box offline reliability
 *   and deterministic JVM unit testing.
 */
enum class YuyanSchema(
    val id: String,
    val displayLabel: String,
    val shortBadge: String,
) {
    PINYIN("pinyin", "语燕全拼", "全拼"),
    FLYPY("double_pinyin_flypy", "小鹤双拼", "小鹤"),
    ZIRANMA("double_pinyin", "自然码", "自然码"),
    SOGOU("double_pinyin_sogou", "搜狗双拼", "搜狗"),
    MSPY("double_pinyin_mspy", "微软双拼", "微软"),
    T9("t9", "语燕九宫格", "九键");

    companion object {
        fun fromId(raw: String?): YuyanSchema {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.id.equals(clean, ignoreCase = true) } ?: PINYIN
        }
    }
}

data class YuyanCandidate(
    val text: String,
    val consumedRawLength: Int,
    val score: Int = 0,
)

data class YuyanEngineSnapshot(
    val chineseMode: Boolean = true,
    val schema: YuyanSchema = YuyanSchema.PINYIN,
    val rawBuffer: String = "",
    val displayComposing: String = "",
    val candidates: List<YuyanCandidate> = emptyList(),
    val pageIndex: Int = 0,
    val pageSize: Int = 7,
    val highlightedInPage: Int = 0,
    val nativeRimeActive: Boolean = false,
) {
    val isComposing: Boolean get() = rawBuffer.isNotEmpty() || displayComposing.isNotEmpty()
    val pageCount: Int
        get() = if (candidates.isEmpty()) 0 else ((candidates.size + pageSize - 1) / pageSize)

    val visibleCandidates: List<YuyanCandidate>
        get() {
            if (candidates.isEmpty()) return emptyList()
            val safePage = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val from = safePage * pageSize
            val to = (from + pageSize).coerceAtMost(candidates.size)
            return if (from < to) candidates.subList(from, to) else emptyList()
        }
}

sealed interface YuyanKeyOutcome {
    /** Key was not handled by the composition engine; let the caller / app process it. */
    data object PassThrough : YuyanKeyOutcome

    /** Engine updated internal composition/candidate state; sync `composingText` to InputConnection. */
    data class ComposingUpdated(val inlineComposing: String) : YuyanKeyOutcome

    /** Engine committed `committedText`; if `remainingInlineComposing` is non-empty, partial composition continues. */
    data class Commit(
        val committedText: String,
        val remainingInlineComposing: String = "",
    ) : YuyanKeyOutcome
}

/**
 * Optional native `librime_jni.so` probe & asset deployer compatible with `gurecn/YuyanIme`'s
 * `imesdk` shared/user Rime workspace layout.
 */
object NativeYuyanRimeProbe {
    @Volatile
    private var probed = false

    @Volatile
    var nativeLibraryAvailable: Boolean = false
        private set

    fun probeAndDeployIfPresent(ctx: Context): Boolean {
        if (probed) return nativeLibraryAvailable
        synchronized(this) {
            if (probed) return nativeLibraryAvailable
            val hasRimeAssets = runCatching {
                ctx.assets.list("rime")?.isNotEmpty() == true
            }.getOrDefault(false)
            if (hasRimeAssets) {
                runCatching {
                    deployRimeAssets(ctx)
                    System.loadLibrary("rime_jni")
                    nativeLibraryAvailable = true
                }.onFailure {
                    nativeLibraryAvailable = false
                }
            } else {
                nativeLibraryAvailable = false
            }
            probed = true
            return nativeLibraryAvailable
        }
    }

    private fun deployRimeAssets(ctx: Context) {
        val sharedDir = File(ctx.filesDir, "rime/shared").apply { mkdirs() }
        File(ctx.filesDir, "rime/user").mkdirs()
        val entries = ctx.assets.list("rime").orEmpty()
        for (name in entries) {
            val out = File(sharedDir, name)
            if (!out.exists() || out.length() == 0L) {
                runCatching {
                    ctx.assets.open("rime/$name").use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
