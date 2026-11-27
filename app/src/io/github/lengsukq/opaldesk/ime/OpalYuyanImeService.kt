package io.github.lengsukq.opaldesk.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.text.InputType
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Optional Same-Cert Companion Input Method Service (`io.github.lengsukq.opaldesk.ime/.OpalYuyanImeService`).
 *
 * Designed specifically to cure multi-display DeX / Cast / Bluetooth-Keyboard + Touchscreen IME failures:
 * 1. **Zero Clipping & Zero Orientation Bug on DeX VirtualDisplays**: Returns a `0×0` invisible view
 *    when attached to any DeX `OpalWin-*` VirtualDisplay (`imeDisplayId > 0`) and streams live
 *    composition, candidates, and matrix-transformed `CursorAnchorInfo` directly to `OpalDesk`
 *    (`BridgeProvider.pluginImeSync`) so `OpalCompositorImeOverlay` renders them at native canvas DPI.
 * 2. **Physical Bluetooth / USB Keyboard Native CJK Typing**: Intercepts `a..z`, `1..7`, `Space`,
 *    `Enter`, `Backspace`, `Esc`, `-`/`=`, `Shift` tap (CN/EN toggle), and `Ctrl+Space` directly on
 *    the focused `OpalWin-*` display without `NativeImeBridgeHost` or clipboard `Ctrl+V` hacks.
 * 3. **Display 0 Smart Yield**: Automatically yields back to the user's normal phone keyboard
 *    (`switchInputMethod(originalImeId)`) in 0ms whenever an editable field is tapped on Phone Display 0
 *    while DeX is running on an external monitor or wireless cast, and includes a clean standalone
 *    fallback keyboard bar if manually selected outside DeX.
 */
class OpalYuyanImeService : InputMethodService() {

    companion object {
        const val PREFS_NAME = "opal_yuyan_plugin_prefs"
        const val KEY_DEFAULT_SCHEMA = "default_schema"

        private val BRIDGE_URI: Uri = Uri.parse("content://io.github.lengsukq.opaldesk.bridge")
        private const val METHOD_PLUGIN_IME_SYNC = "pluginImeSync"
        private const val PLUGIN_IME_BINDER_DESC = "io.github.lengsukq.opaldesk.IPluginImeSession"

        private const val TX_COMMIT_TEXT = 1
        private const val TX_SOFT_KEY_CHAR = 2
        private const val TX_SOFT_SPECIAL_ACTION = 3
        private const val TX_SELECT_CANDIDATE = 4
        private const val TX_SEND_KEY_TAP = 5
        private const val TX_SELECT_SCHEMA = 6

        private const val SPECIAL_BACKSPACE = 1
        private const val SPECIAL_SPACE = 2
        private const val SPECIAL_ENTER = 3
        private const val SPECIAL_TOGGLE_LANG = 4
        private const val SPECIAL_CYCLE_SCHEMA = 5
        private const val SPECIAL_NEXT_PAGE = 6
        private const val SPECIAL_PREV_PAGE = 7
        private const val SPECIAL_DISMISS_COMPOSING = 8
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: OpalYuyanEngine
    private var nativeRimeAvailable = false
    private var activePkg: String = ""
    private var activeInputType: Int = 0
    private var activeImeOptions: Int = 0
    private var activeFieldId: Int = 0
    private var inputActive: Boolean = false
    private var yieldedCurrentSession: Boolean = false
    private var shiftTapCandidate: Boolean = false
    private var opalDeskProviderReachable: Boolean = false

    // Standalone fallback view widgets (used only if user manually selects this IME on Display 0 outside DeX)
    private var rootContainer: FrameLayout? = null
    private var standalonePanel: LinearLayout? = null
    private var standalonePreeditView: TextView? = null
    private var standaloneCandidatesRow: LinearLayout? = null

    private val sessionBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(PLUGIN_IME_BINDER_DESC)
                return true
            }
            // Enforce same-UID or same-signing-cert OpalDesk host caller
            val callingUid = getCallingUid()
            if (callingUid != android.os.Process.myUid() && !isTrustedOpalHostUid(callingUid)) {
                return false
            }
            return try {
                data.enforceInterface(PLUGIN_IME_BINDER_DESC)
                when (code) {
                    TX_COMMIT_TEXT -> {
                        val text = data.readString().orEmpty()
                        val rejectSelf = data.readInt() != 0
                        val hostPkg = data.readString().orEmpty()
                        val ok = commitExternalTextOnMain(text, rejectSelf, hostPkg)
                        reply?.writeInt(if (ok) 1 else 0)
                        true
                    }
                    TX_SOFT_KEY_CHAR -> {
                        val ch = data.readInt().toChar()
                        mainHandler.post { handleEngineOutcome(engine.processChar(ch)) }
                        true
                    }
                    TX_SOFT_SPECIAL_ACTION -> {
                        val act = data.readInt()
                        mainHandler.post { handleSpecialAction(act) }
                        true
                    }
                    TX_SELECT_CANDIDATE -> {
                        val visIdx = data.readInt()
                        mainHandler.post {
                            engine.selectVisibleCandidate(visIdx)?.let(::handleEngineOutcome)
                        }
                        true
                    }
                    TX_SEND_KEY_TAP -> {
                        val keyCode = data.readInt()
                        val meta = data.readInt()
                        mainHandler.post { sendKeyTapToCurrentConnection(keyCode, meta) }
                        true
                    }
                    TX_SELECT_SCHEMA -> {
                        val schemaId = data.readString()
                        mainHandler.post {
                            val next = YuyanSchema.fromId(schemaId)
                            savePreferredSchema(next)
                            engine.setSchema(next)
                            syncStateToOpalDesk()
                        }
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val lexicon = runCatching {
            assets.open("opal_yuyan_dict.txt").use { YuyanLexicon.fromStream(it) }
        }.getOrElse { YuyanLexicon.defaultBuiltIn() }
        val savedSchema = loadPreferredSchema()
        nativeRimeAvailable = NativeYuyanRimeProbe.probeAndDeployIfPresent(this)
        engine = OpalYuyanEngine(lexicon = lexicon, initialSchema = savedSchema)
        attachToOpalDeskProvider()
    }

    override fun onDestroy() {
        callOpalDeskProvider("detach", null)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        // Always return true so Android IMMS dispatches onShowInputRequested even when a Bluetooth
        // or USB physical keyboard is attached.
        return true
    }

    override fun onCreateInputView(): View {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val fallback = buildStandaloneDisplay0KeyboardView()
        root.addView(fallback)
        rootContainer = root
        standalonePanel = fallback
        updateStandaloneVisibility()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        engine.clearComposition()
        yieldedCurrentSession = false

        val pkg = attribute?.packageName?.trim().orEmpty()
        val inputType = attribute?.inputType ?: 0
        val imeOptions = attribute?.imeOptions ?: 0
        val fieldId = attribute?.fieldId ?: 0
        val accepting = inputType != 0 || imeOptions != 0
        val dispId = resolveCurrentImeDisplayId()

        activePkg = pkg
        activeInputType = inputType
        activeImeOptions = imeOptions
        activeFieldId = fieldId
        inputActive = accepting

        // Default to English mode for password / URI / email fields; keep user's last mode otherwise
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            engine.setChineseMode(false)
        }

        // Re-attach binder if OpalDesk restarted
        attachToOpalDeskProvider()

        // Smart Display 0 handoff: if the user tapped an editable box on Phone Display 0 while DeX
        // is running on an external screen or remote cast, immediately restore their phone keyboard!
        if (accepting && dispId == Display.DEFAULT_DISPLAY) {
            val checkExtras = Bundle().apply {
                putInt("imeDisplayId", dispId)
                putString("pkg", pkg)
                putInt("inputType", inputType)
                putBoolean("accepting", true)
            }
            val res = callOpalDeskProvider("checkYieldDisplay0", checkExtras)
            if (res?.getBoolean("shouldYield", false) == true) {
                yieldedCurrentSession = true
                val origIme = res.getString("originalImeId").orEmpty()
                if (origIme.isNotEmpty() && origIme != "$packageName/.OpalYuyanImeService") {
                    runCatching { switchInputMethod(origIme) }
                    return
                }
            }
        }

        if (accepting) {
            requestCursorAnchorUpdatesSafe()
        }
        updateStandaloneVisibility()
        syncStateToOpalDesk()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        requestCursorAnchorUpdatesSafe()
        updateStandaloneVisibility()
        syncStateToOpalDesk()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (engine.snapshot().isComposing) {
            engine.clearComposition()
            currentInputConnection?.finishComposingText()
        }
        callOpalDeskProvider(
            "hideOrFinish",
            Bundle().apply { putInt("imeDisplayId", resolveCurrentImeDisplayId()) },
        )
        syncStateToOpalDesk()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        engine.clearComposition()
        inputActive = false
        activePkg = ""
        activeInputType = 0
        activeImeOptions = 0
        activeFieldId = 0
        syncStateToOpalDesk()
        super.onFinishInput()
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val show = super.onShowInputRequested(flags, configChange)
        if (!yieldedCurrentSession && inputActive) {
            callOpalDeskProvider(
                "showRequested",
                Bundle().apply {
                    putInt("imeDisplayId", resolveCurrentImeDisplayId())
                    putInt("inputType", activeInputType)
                    putString("pkg", activePkg)
                },
            )
            updateStandaloneVisibility()
        }
        return true
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        val info = cursorAnchorInfo ?: return
        val hx = info.insertionMarkerHorizontal
        val top = info.insertionMarkerTop
        val base = info.insertionMarkerBaseline
        val bot = info.insertionMarkerBottom
        if (hx.isNaN() || top.isNaN() || bot.isNaN()) return

        val pts = floatArrayOf(hx, top, hx, base, hx, bot)
        runCatching { info.matrix.mapPoints(pts) }
        callOpalDeskProvider(
            "cursor",
            Bundle().apply {
                putBoolean("valid", true)
                putFloat("h", pts[0])
                putFloat("top", pts[1])
                putFloat("baseline", pts[3])
                putFloat("bottom", pts[5])
                putInt("imeDisplayId", resolveCurrentImeDisplayId())
            },
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 1. Forward OpalDesk global window/tiling shortcuts (`Alt+Tab`, `Alt+2/3/4`, etc.) first
        if ((event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) &&
            forwardGlobalShortcutToOpalDesk(event)
        ) {
            shiftTapCandidate = false
            return true
        }

        // 2. Track standalone Shift tap for instant CN/EN toggle
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (event.repeatCount == 0) {
                shiftTapCandidate = true
            }
            return super.onKeyDown(keyCode, event)
        }
        shiftTapCandidate = false

        if (!inputActive || yieldedCurrentSession) {
            return super.onKeyDown(keyCode, event)
        }

        // 3. Explicit IME switch shortcuts (`Ctrl+Space`, `Shift+Space`, `LanguageSwitch`)
        if (isLanguageToggleShortcut(keyCode, event.metaState)) {
            handleEngineOutcome(engine.toggleChineseMode())
            return true
        }

        // Let Ctrl/Alt/Meta chords (like Ctrl+C / Ctrl+V / Ctrl+A) pass directly to the app
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            if (engine.snapshot().isComposing) {
                engine.clearComposition()
                currentInputConnection?.finishComposingText()
                syncStateToOpalDesk()
            }
            return super.onKeyDown(keyCode, event)
        }

        val snap = engine.snapshot()
        if (snap.isComposing) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    handleEngineOutcome(engine.backspace())
                    return true
                }
                KeyEvent.KEYCODE_SPACE -> {
                    handleEngineOutcome(engine.space())
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    handleEngineOutcome(engine.enter())
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    handleEngineOutcome(engine.escape())
                    return true
                }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                    if (engine.nextPage()) syncStateToOpalDesk()
                    return true
                }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_LEFT_BRACKET -> {
                    if (engine.prevPage()) syncStateToOpalDesk()
                    return true
                }
            }
        }

        val unicode = event.unicodeChar
        if (unicode >= 0x20 && unicode != 0x7F) {
            val ch = unicode.toChar()
            if (snap.chineseMode) {
                // In CN mode, feed letters, composing digits, or Chinese punctuation to engine
                val shouldIntercept = ch in 'a'..'z' ||
                    (ch in 'A'..'Z' && snap.isComposing) ||
                    (snap.isComposing && ch in '1'..'9') ||
                    (snap.schema == YuyanSchema.T9 && ch in '2'..'9') ||
                    ch in ",.!?:;()[]<>\\^_\"'"
                if (shouldIntercept) {
                    handleEngineOutcome(engine.processChar(ch))
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) &&
            forwardGlobalShortcutToOpalDesk(event)
        ) {
            shiftTapCandidate = false
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (shiftTapCandidate && inputActive && !yieldedCurrentSession) {
                shiftTapCandidate = false
                handleEngineOutcome(engine.toggleChineseMode())
                return true
            }
            shiftTapCandidate = false
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleSpecialAction(actionCode: Int) {
        when (actionCode) {
            SPECIAL_BACKSPACE -> {
                val out = engine.backspace()
                if (out === YuyanKeyOutcome.PassThrough) {
                    val ic = currentInputConnection
                    if (ic != null) {
                        if (!ic.deleteSurroundingText(1, 0)) {
                            sendKeyTapToCurrentConnection(KeyEvent.KEYCODE_DEL, 0)
                        }
                    }
                    syncStateToOpalDesk()
                } else {
                    handleEngineOutcome(out)
                }
            }
            SPECIAL_SPACE -> handleEngineOutcome(engine.space())
            SPECIAL_ENTER -> {
                val out = engine.enter()
                if (out === YuyanKeyOutcome.PassThrough) {
                    performEnterOrEditorAction()
                    syncStateToOpalDesk()
                } else {
                    handleEngineOutcome(out)
                }
            }
            SPECIAL_TOGGLE_LANG -> handleEngineOutcome(engine.toggleChineseMode())
            SPECIAL_CYCLE_SCHEMA -> {
                val next = engine.cycleSchema()
                savePreferredSchema(next)
                syncStateToOpalDesk()
            }
            SPECIAL_NEXT_PAGE -> if (engine.nextPage()) syncStateToOpalDesk()
            SPECIAL_PREV_PAGE -> if (engine.prevPage()) syncStateToOpalDesk()
            SPECIAL_DISMISS_COMPOSING -> {
                engine.clearComposition()
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
                syncStateToOpalDesk()
            }
        }
    }

    private fun handleEngineOutcome(outcome: YuyanKeyOutcome) {
        val ic = currentInputConnection
        when (outcome) {
            YuyanKeyOutcome.PassThrough -> Unit
            is YuyanKeyOutcome.ComposingUpdated -> {
                if (ic != null) {
                    if (outcome.inlineComposing.isEmpty()) {
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                    } else {
                        ic.setComposingText(outcome.inlineComposing, 1)
                    }
                }
            }
            is YuyanKeyOutcome.Commit -> {
                if (ic != null) {
                    ic.beginBatchEdit()
                    try {
                        if (outcome.committedText.isNotEmpty()) {
                            ic.commitText(outcome.committedText, 1)
                        }
                        if (outcome.remainingInlineComposing.isNotEmpty()) {
                            ic.setComposingText(outcome.remainingInlineComposing, 1)
                        } else {
                            ic.finishComposingText()
                        }
                    } finally {
                        ic.endBatchEdit()
                    }
                }
            }
        }
        syncStateToOpalDesk()
    }

    private fun performEnterOrEditorAction() {
        val ic = currentInputConnection ?: return
        val action = activeImeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (activeImeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = (activeInputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!noEnterAction && !multiLine &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            if (ic.performEditorAction(action)) return
        }
        sendKeyTapToCurrentConnection(KeyEvent.KEYCODE_ENTER, 0)
    }

    private fun sendKeyTapToCurrentConnection(keyCode: Int, metaState: Int) {
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now + 4L, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    private fun commitExternalTextOnMain(
        text: String,
        rejectSelf: Boolean,
        hostPkg: String,
    ): Boolean {
        if (text.isEmpty()) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return commitDirectNow(text, rejectSelf, hostPkg)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val okRef = java.util.concurrent.atomic.AtomicBoolean(false)
        mainHandler.post {
            try {
                okRef.set(commitDirectNow(text, rejectSelf, hostPkg))
            } finally {
                latch.countDown()
            }
        }
        return runCatching {
            latch.await(300L, java.util.concurrent.TimeUnit.MILLISECONDS) && okRef.get()
        }.getOrDefault(false)
    }

    private fun commitDirectNow(text: String, rejectSelf: Boolean, hostPkg: String): Boolean {
        if (!inputActive) return false
        if (rejectSelf && hostPkg.isNotEmpty() && activePkg == hostPkg) return false
        val ic = currentInputConnection ?: return false
        if (engine.snapshot().isComposing) {
            engine.clearComposition()
        }
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        ic.beginBatchEdit()
        return try {
            val multiLine = (activeInputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val lines = normalized.split('\n')
            lines.forEachIndexed { idx, seg ->
                if (seg.isNotEmpty()) ic.commitText(seg, 1)
                if (idx < lines.lastIndex) {
                    if (multiLine) ic.commitText("\n", 1) else sendKeyTapToCurrentConnection(KeyEvent.KEYCODE_ENTER, 0)
                }
            }
            syncStateToOpalDesk()
            true
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun requestCursorAnchorUpdatesSafe() {
        val ic = currentInputConnection ?: return
        runCatching {
            ic.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        }
    }

    private fun attachToOpalDeskProvider() {
        val snap = engine.snapshot(nativeRimeAvailable)
        val reply = callOpalDeskProvider(
            "attach",
            Bundle().apply {
                putBinder("binder", sessionBinder)
                putString("schemaId", snap.schema.id)
                putString("schemaLabel", snap.schema.displayLabel)
                putBoolean("nativeRime", nativeRimeAvailable)
            },
        )
        opalDeskProviderReachable = reply?.getBoolean("ok", false) == true
    }

    private fun syncStateToOpalDesk() {
        val snap = engine.snapshot(nativeRimeAvailable)
        updateStandaloneCandidatesUi(snap)
        val b = Bundle().apply {
            putBoolean("inputActive", inputActive && !yieldedCurrentSession)
            putString("pkg", activePkg)
            putInt("inputType", activeInputType)
            putInt("imeOptions", activeImeOptions)
            putInt("fieldId", activeFieldId)
            putInt("imeDisplayId", resolveCurrentImeDisplayId())
            putString("langMode", if (snap.chineseMode) "CN" else "EN")
            putString("schemaId", snap.schema.id)
            putString("schemaLabel", snap.schema.displayLabel)
            putString("rawBuffer", snap.rawBuffer)
            putString("displayComposing", snap.displayComposing)
            putStringArrayList("candidates", ArrayList(snap.candidates.map { it.text }))
            putInt("pageIndex", snap.pageIndex)
            putInt("pageSize", snap.pageSize)
            putInt("highlightedInPage", snap.highlightedInPage)
        }
        val reply = callOpalDeskProvider("state", b)
        opalDeskProviderReachable = reply?.getBoolean("ok", false) == true
    }

    private fun forwardGlobalShortcutToOpalDesk(event: KeyEvent): Boolean {
        val reply = callOpalDeskProvider(
            "globalKey",
            Bundle().apply { putParcelable("event", event) },
        )
        return reply?.getBoolean("handled", false) == true
    }

    private fun callOpalDeskProvider(subOp: String, extras: Bundle?): Bundle? {
        return try {
            contentResolver.call(BRIDGE_URI, METHOD_PLUGIN_IME_SYNC, subOp, extras)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCurrentImeDisplayId(): Int {
        val winDisp = runCatching { window?.window?.decorView?.display?.displayId }.getOrNull()
        if (winDisp != null && winDisp >= 0) return winDisp
        return runCatching { display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
    }

    private fun isLanguageToggleShortcut(keyCode: Int, metaState: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || keyCode == KeyEvent.KEYCODE_SWITCH_CHARSET) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            val mod = metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON)
            val alt = metaState and KeyEvent.META_ALT_ON
            return mod != 0 && alt == 0
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun isTrustedOpalHostUid(callingUid: Int): Boolean {
        return try {
            val pm = packageManager
            val callerPkgs = pm.getPackagesForUid(callingUid) ?: return false
            if (!callerPkgs.contains("io.github.lengsukq.opaldesk")) return false
            pm.checkSignatures(packageName, "io.github.lengsukq.opaldesk") ==
                android.content.pm.PackageManager.SIGNATURE_MATCH
        } catch (_: Throwable) {
            false
        }
    }

    private fun loadPreferredSchema(): YuyanSchema {
        val raw = runCatching {
            val ctx = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEFAULT_SCHEMA, YuyanSchema.PINYIN.id)
        }.getOrNull()
        return YuyanSchema.fromId(raw)
    }

    private fun savePreferredSchema(schema: YuyanSchema) {
        runCatching {
            val ctx = if (isDeviceProtectedStorage) this else createDeviceProtectedStorageContext()
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEFAULT_SCHEMA, schema.id)
                .apply()
        }
    }

    // ---- Zero-Clip DeX 0x0 Mode vs Standalone Display 0 Fallback Keyboard ----

    private fun updateStandaloneVisibility() {
        val panel = standalonePanel ?: return
        val dispId = resolveCurrentImeDisplayId()
        // CRITICAL: On any secondary / VirtualDisplay (`dispId > 0`) or when yielded, keep the
        // InputMethodService window 0x0 invisible so OpalCompositorImeOverlay renders in full canvas space!
        val showStandalone = dispId == Display.DEFAULT_DISPLAY && !yieldedCurrentSession && !opalDeskProviderReachable
        panel.visibility = if (showStandalone) View.VISIBLE else View.GONE
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun buildStandaloneDisplay0KeyboardView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EEF2F9"))
            setPadding(dp(6f), dp(6f), dp(6f), dp(10f))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val preedit = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            visibility = View.GONE
        }
        standalonePreeditView = preedit
        container.addView(preedit)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val candRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), dp(4f), dp(4f))
        }
        standaloneCandidatesRow = candRow
        scroll.addView(candRow)
        container.addView(scroll)

        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEachIndexed { rIdx, chars ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4f) }
            }
            if (rIdx == 2) {
                rowLayout.addView(makeKeyView("中/英", weight = 1.4f, functional = true) {
                    handleEngineOutcome(engine.toggleChineseMode())
                })
            }
            chars.forEach { ch ->
                rowLayout.addView(makeKeyView(ch.toString(), weight = 1f, functional = false) {
                    handleEngineOutcome(engine.processChar(ch))
                })
            }
            if (rIdx == 2) {
                rowLayout.addView(makeKeyView("⌫", weight = 1.4f, functional = true) {
                    handleSpecialAction(SPECIAL_BACKSPACE)
                })
            }
            container.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4f) }
        }
        bottomRow.addView(makeKeyView("方案", weight = 1.3f, functional = true) {
            handleSpecialAction(SPECIAL_CYCLE_SCHEMA)
        })
        bottomRow.addView(makeKeyView("，", weight = 1f, functional = true) {
            handleEngineOutcome(engine.processChar(','))
        })
        bottomRow.addView(makeKeyView("语燕 · 空格", weight = 4f, functional = false) {
            handleSpecialAction(SPECIAL_SPACE)
        })
        bottomRow.addView(makeKeyView("。", weight = 1f, functional = true) {
            handleEngineOutcome(engine.processChar('.'))
        })
        bottomRow.addView(makeKeyView("↵", weight = 1.5f, functional = true, accent = true) {
            handleSpecialAction(SPECIAL_ENTER)
        })
        container.addView(bottomRow)
        return container
    }

    private fun makeKeyView(
        label: String,
        weight: Float,
        functional: Boolean,
        accent: Boolean = false,
        onTap: () -> Unit,
    ): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(
                when {
                    accent -> Color.parseColor("#007AFF")
                    functional -> Color.parseColor("#DCE4F2")
                    else -> Color.WHITE
                },
            )
        }
        return TextView(this).apply {
            text = label
            textSize = if (label.length <= 2) 15f else 12f
            gravity = Gravity.CENTER
            background = bg
            setTextColor(if (accent) Color.WHITE else Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, dp(41f), weight).apply {
                marginStart = dp(2f)
                marginEnd = dp(2f)
            }
            setOnClickListener { onTap() }
        }
    }

    private fun updateStandaloneCandidatesUi(snap: YuyanEngineSnapshot) {
        val panel = standalonePanel ?: return
        if (panel.visibility != View.VISIBLE) return
        standalonePreeditView?.apply {
            if (snap.isComposing) {
                text = "${snap.displayComposing}  [${snap.schema.shortBadge}]"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        val row = standaloneCandidatesRow ?: return
        row.removeAllViews()
        snap.visibleCandidates.forEachIndexed { idx, cand ->
            val tv = TextView(this).apply {
                text = "${idx + 1}.${cand.text}"
                textSize = 14f
                setTextColor(if (idx == snap.highlightedInPage) Color.parseColor("#007AFF") else Color.parseColor("#0F172A"))
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                setOnClickListener {
                    engine.selectVisibleCandidate(idx)?.let(::handleEngineOutcome)
                }
            }
            row.addView(tv)
        }
    }
}
