package io.github.lengsukq.opaldesk.ime

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Pure-Kotlin Multi-Schema Chinese Input Engine (`OpalYuyanEngine`) for the optional `:ime-plugin`.
 *
 * Supports all core schemas popularized by `gurecn/YuyanIme`:
 * - **语燕全拼 (`pinyin`)**: Full Pinyin + **Jianpin (简拼)** (`nh` -> `你好`, `wsm` -> `为什么`, `srff` -> `输入法`)
 * - **小鹤双拼 (`double_pinyin_flypy`)**: `nihc` -> `你好`, `ulpb` -> `双拼`, `vsgo` -> `中国`
 * - **自然码 (`double_pinyin`)**: `nihk` -> `你好`, `zirma` -> `自然码`
 * - **搜狗双拼 (`double_pinyin_sogou`)**: `;` for `ing`, `y` for `uai`/`v`
 * - **微软双拼 (`double_pinyin_mspy`)**: `;` for `ing`, `v` for `ve`/`ui`
 * - **语燕九宫格 (`t9`)**: `64426` -> `你好`, `94664` -> `中`
 * - **Partial Multi-Syllable Commit**: Selecting a 2-character candidate while 4 syllables are buffered
 *   commits the first 2 syllables and keeps the remaining 2 syllables actively composing!
 * - **Smart Chinese Punctuation**: Alternating `“...”` / `‘...’` quotes and full-width CJK punctuation.
 */
class OpalYuyanEngine(
    private val lexicon: YuyanLexicon = YuyanLexicon.defaultBuiltIn(),
    initialSchema: YuyanSchema = YuyanSchema.PINYIN,
    private val pageSize: Int = 7,
) {
    private var chineseMode: Boolean = true
    private var schema: YuyanSchema = initialSchema
    private val rawBuilder = StringBuilder()
    private var displayComposing: String = ""
    private var candidates: List<YuyanCandidate> = emptyList()
    private var pageIndex: Int = 0
    private var highlightedInPage: Int = 0
    private var doubleQuoteOpen: Boolean = false
    private var singleQuoteOpen: Boolean = false

    fun snapshot(nativeRimeActive: Boolean = false): YuyanEngineSnapshot = YuyanEngineSnapshot(
        chineseMode = chineseMode,
        schema = schema,
        rawBuffer = rawBuilder.toString(),
        displayComposing = displayComposing,
        candidates = candidates,
        pageIndex = pageIndex,
        pageSize = pageSize,
        highlightedInPage = highlightedInPage,
        nativeRimeActive = nativeRimeActive,
    )

    fun setSchema(next: YuyanSchema) {
        if (schema == next) return
        schema = next
        if (rawBuilder.isNotEmpty()) {
            recomputeCandidates()
        }
    }

    fun cycleSchema(): YuyanSchema {
        val all = YuyanSchema.entries
        val next = all[(all.indexOf(schema) + 1) % all.size]
        setSchema(next)
        return next
    }

    fun setChineseMode(enabled: Boolean): YuyanKeyOutcome {
        if (chineseMode == enabled) return YuyanKeyOutcome.ComposingUpdated(inlinePreview())
        val pendingRaw = rawBuilder.toString()
        clearComposition()
        chineseMode = enabled
        return if (pendingRaw.isNotEmpty()) {
            YuyanKeyOutcome.Commit(committedText = pendingRaw)
        } else {
            YuyanKeyOutcome.ComposingUpdated("")
        }
    }

    fun toggleChineseMode(): YuyanKeyOutcome = setChineseMode(!chineseMode)

    fun clearComposition() {
        rawBuilder.setLength(0)
        displayComposing = ""
        candidates = emptyList()
        pageIndex = 0
        highlightedInPage = 0
    }

    /** Processes a typed character from either a physical Bluetooth/USB keyboard or the Compositor soft keyboard. */
    fun processChar(ch: Char): YuyanKeyOutcome {
        if (!chineseMode) {
            return YuyanKeyOutcome.Commit(committedText = ch.toString())
        }

        // Uppercase letter while not composing commits directly (convenient Shift+Letter in CN mode)
        if (ch in 'A'..'Z' && rawBuilder.isEmpty()) {
            return YuyanKeyOutcome.Commit(committedText = ch.toString())
        }

        val lower = ch.lowercaseChar()
        val acceptsSemicolonAsIng =
            (schema == YuyanSchema.SOGOU || schema == YuyanSchema.MSPY) &&
                rawBuilder.isNotEmpty() &&
                (rawBuilder.length % 2 == 1)
        val isT9Digit = schema == YuyanSchema.T9 && ch in '2'..'9'
        val isComposeChar = when {
            schema == YuyanSchema.T9 -> isT9Digit || (ch == '\'' && rawBuilder.isNotEmpty())
            lower in 'a'..'z' -> true
            ch == ';' && acceptsSemicolonAsIng -> true
            ch == '\'' && rawBuilder.isNotEmpty() -> true
            else -> false
        }

        if (isComposeChar) {
            if (rawBuilder.length < 48) {
                rawBuilder.append(if (ch in 'A'..'Z') lower else ch)
                recomputeCandidates()
            }
            return YuyanKeyOutcome.ComposingUpdated(inlinePreview())
        }

        // Digit 1..pageSize while composing selects the visible candidate on the current page!
        if (rawBuilder.isNotEmpty() && ch in '1'..'9') {
            val idx = (ch - '1')
            if (idx in 0 until pageSize) {
                selectVisibleCandidate(idx)?.let { return it }
            }
        }

        // Punctuation while composing: commit top candidate first, then append Chinese punctuation
        val mappedPunct = mapChinesePunctuation(ch)
        if (rawBuilder.isNotEmpty()) {
            val committed = commitTopCandidateInternal()
            val suffix = mappedPunct ?: ch.toString()
            return YuyanKeyOutcome.Commit(committedText = committed + suffix)
        }

        if (mappedPunct != null) {
            return YuyanKeyOutcome.Commit(committedText = mappedPunct)
        }

        return YuyanKeyOutcome.Commit(committedText = ch.toString())
    }

    fun backspace(): YuyanKeyOutcome {
        if (rawBuilder.isEmpty()) return YuyanKeyOutcome.PassThrough
        rawBuilder.deleteCharAt(rawBuilder.length - 1)
        if (rawBuilder.isEmpty()) {
            clearComposition()
            return YuyanKeyOutcome.ComposingUpdated("")
        }
        recomputeCandidates()
        return YuyanKeyOutcome.ComposingUpdated(inlinePreview())
    }

    fun space(): YuyanKeyOutcome {
        if (rawBuilder.isEmpty()) {
            return YuyanKeyOutcome.Commit(" ")
        }
        val snap = snapshot()
        val vis = snap.visibleCandidates
        if (vis.isEmpty()) {
            val raw = rawBuilder.toString()
            clearComposition()
            return YuyanKeyOutcome.Commit(raw)
        }
        val pickIdx = highlightedInPage.coerceIn(0, vis.lastIndex)
        return selectVisibleCandidate(pickIdx) ?: YuyanKeyOutcome.Commit(commitTopCandidateInternal())
    }

    /** Desktop standard: pressing Enter while composing commits the raw ASCII buffer verbatim. */
    fun enter(): YuyanKeyOutcome {
        if (rawBuilder.isEmpty()) return YuyanKeyOutcome.PassThrough
        val raw = rawBuilder.toString().replace("'", "")
        clearComposition()
        return YuyanKeyOutcome.Commit(raw)
    }

    fun escape(): YuyanKeyOutcome {
        if (rawBuilder.isEmpty()) return YuyanKeyOutcome.PassThrough
        clearComposition()
        return YuyanKeyOutcome.ComposingUpdated("")
    }

    fun nextPage(): Boolean {
        val snap = snapshot()
        if (snap.pageIndex + 1 >= snap.pageCount) return false
        pageIndex = snap.pageIndex + 1
        highlightedInPage = 0
        return true
    }

    fun prevPage(): Boolean {
        if (pageIndex <= 0) return false
        pageIndex -= 1
        highlightedInPage = 0
        return true
    }

    fun selectVisibleCandidate(visibleIndex: Int): YuyanKeyOutcome? {
        val snap = snapshot()
        val vis = snap.visibleCandidates
        if (visibleIndex !in vis.indices) return null
        val chosen = vis[visibleIndex]
        val fullRaw = rawBuilder.toString()
        val consumed = chosen.consumedRawLength.coerceIn(1, fullRaw.length)
        val remainder = fullRaw.substring(consumed).trimStart('\'')
        if (remainder.isEmpty()) {
            clearComposition()
            return YuyanKeyOutcome.Commit(committedText = chosen.text, remainingInlineComposing = "")
        }
        rawBuilder.setLength(0)
        rawBuilder.append(remainder)
        recomputeCandidates()
        return YuyanKeyOutcome.Commit(
            committedText = chosen.text,
            remainingInlineComposing = inlinePreview(),
        )
    }

    private fun commitTopCandidateInternal(): String {
        val fullRaw = rawBuilder.toString()
        val top = candidates.firstOrNull()
        if (top == null) {
            clearComposition()
            return fullRaw.replace("'", "")
        }
        // If top candidate only covers a prefix, greedily assemble remaining syllables
        val sb = StringBuilder()
        var safety = 0
        while (rawBuilder.isNotEmpty() && safety++ < 24) {
            val curTop = candidates.firstOrNull()
            if (curTop == null) {
                sb.append(rawBuilder.toString().replace("'", ""))
                clearComposition()
                break
            }
            sb.append(curTop.text)
            val rem = rawBuilder.toString()
                .substring(curTop.consumedRawLength.coerceIn(1, rawBuilder.length))
                .trimStart('\'')
            if (rem.isEmpty()) {
                clearComposition()
                break
            }
            rawBuilder.setLength(0)
            rawBuilder.append(rem)
            recomputeCandidates()
        }
        clearComposition()
        return sb.toString()
    }

    private fun inlinePreview(): String = displayComposing.ifEmpty { rawBuilder.toString() }

    private fun mapChinesePunctuation(ch: Char): String? = when (ch) {
        ',' -> "，"
        '.' -> "。"
        '?' -> "？"
        '!' -> "！"
        ':' -> "："
        ';' -> "；"
        '(' -> "（"
        ')' -> "）"
        '[' -> "【"
        ']' -> "】"
        '<' -> "《"
        '>' -> "》"
        '\\' -> "、"
        '^' -> "……"
        '_' -> "——"
        '"' -> {
            val q = if (!doubleQuoteOpen) "“" else "”"
            doubleQuoteOpen = !doubleQuoteOpen
            q
        }
        '\'' -> {
            val q = if (!singleQuoteOpen) "‘" else "’"
            singleQuoteOpen = !singleQuoteOpen
            q
        }
        else -> null
    }

    private fun recomputeCandidates() {
        val raw = rawBuilder.toString()
        pageIndex = 0
        highlightedInPage = 0
        if (raw.isEmpty()) {
            displayComposing = ""
            candidates = emptyList()
            return
        }
        val queryResult = lexicon.query(raw, schema)
        displayComposing = queryResult.first
        candidates = queryResult.second
    }
}

/**
 * Multi-schema Mandarin lexicon indexing:
 * - Full Pinyin syllables & multi-syllable phrases
 * - Jianpin initials (`nh` -> `ni hao` -> `你好`)
 * - 4 standard Shuangpin encodings (`小鹤`, `自然码`, `搜狗`, `微软`)
 * - T9 digit sequences (`2..9`)
 */
class YuyanLexicon private constructor(
    private val singleBySyllable: Map<String, List<String>>,
    private val phrasesBySyllables: Map<List<String>, List<String>>,
    private val phrasesByInitials: Map<String, List<Pair<List<String>, List<String>>>>,
    private val allSyllables: Set<String>,
    private val shuangpinMaps: Map<YuyanSchema, Map<String, List<String>>>,
    private val t9SyllableMap: Map<String, List<String>>,
) {
    data class SegmentedSyllable(
        val syllable: String,
        val rawLength: Int,
        val isExact: Boolean = true,
    )

    fun query(rawInput: String, schema: YuyanSchema): Pair<String, List<YuyanCandidate>> {
        val result = LinkedHashMap<String, YuyanCandidate>()
        fun addCandidate(text: String, consumedLen: Int, score: Int) {
            if (text.isEmpty()) return
            val prev = result[text]
            if (prev == null || score > prev.score) {
                result[text] = YuyanCandidate(text, consumedLen, score)
            }
        }

        when (schema) {
            YuyanSchema.PINYIN -> queryFullPinyin(rawInput, ::addCandidate)
            YuyanSchema.T9 -> queryT9(rawInput, ::addCandidate)
            else -> queryShuangpin(rawInput, schema, ::addCandidate)
        }

        val sorted = result.values.sortedByDescending { it.score }
        val preedit = formatPreedit(rawInput, schema)
        return preedit to sorted
    }

    private fun queryFullPinyin(
        raw: String,
        emit: (String, Int, Int) -> Unit,
    ) {
        val cleanNoSep = raw.replace("'", "")
        // 1. Segment into full Pinyin syllables (supporting trailing prefix / initial)
        val segs = segmentFullPinyin(raw)
        if (segs.isNotEmpty()) {
            val sylList = segs.map { it.syllable }
            // Full phrase match
            phrasesBySyllables[sylList]?.forEachIndexed { idx, word ->
                emit(word, raw.length, 10_000 - idx * 10)
            }
            // Greedy assembled full-sentence candidate when >= 2 syllables
            if (segs.size >= 2) {
                val assembled = assembleBestPhrase(sylList)
                if (assembled != null) {
                    emit(assembled, raw.length, 9_200)
                }
            }
            // Prefix multi-syllable phrase matches (e.g. first 2 or 3 syllables)
            for (len in (segs.size - 1) downTo 2) {
                val subSyls = sylList.subList(0, len)
                val consumedRaw = segs.take(len).sumOf { it.rawLength }
                phrasesBySyllables[subSyls]?.forEachIndexed { idx, word ->
                    emit(word, consumedRaw, 8_000 + len * 100 - idx * 8)
                }
            }
        }

        // 2. Jianpin initial match (e.g. "nh" -> "你好", "wsm" -> "为什么", "srff" -> "输入法")
        if (!raw.contains('\'') && cleanNoSep.length in 2..8) {
            phrasesByInitials[cleanNoSep]?.forEachIndexed { groupIdx, (_, words) ->
                words.forEachIndexed { idx, word ->
                    emit(word, raw.length, 8_900 - groupIdx * 20 - idx * 10)
                }
            }
        }

        // 3. First-syllable single characters (exact + prefix match)
        if (segs.isNotEmpty()) {
            val first = segs.first()
            singleBySyllable[first.syllable]?.forEachIndexed { idx, ch ->
                val base = if (segs.size == 1 && first.isExact) 9_500 else 7_000
                emit(ch, first.rawLength, base - idx * 5)
            }
            if (segs.size == 1 && !first.isExact) {
                allSyllables
                    .filter { it.startsWith(first.syllable) && it != first.syllable }
                    .sortedBy { it.length }
                    .take(4)
                    .forEachIndexed { pIdx, prefSyl ->
                        singleBySyllable[prefSyl]?.take(4)?.forEachIndexed { cIdx, ch ->
                            emit(ch, raw.length, 6_500 - pIdx * 30 - cIdx * 5)
                        }
                    }
            }
        }
    }

    private fun queryShuangpin(
        raw: String,
        schema: YuyanSchema,
        emit: (String, Int, Int) -> Unit,
    ) {
        val spMap = shuangpinMaps[schema].orEmpty()
        val clean = raw.replace("'", "")
        val pairCount = clean.length / 2
        val trailingChar = if (clean.length % 2 == 1) clean.last() else null

        if (pairCount >= 1) {
            // Enumerate top syllable paths across the 2-char Shuangpin pairs
            var paths: List<List<String>> = listOf(emptyList())
            for (i in 0 until pairCount) {
                val pair = clean.substring(i * 2, i * 2 + 2)
                val sylOptions = spMap[pair].orEmpty()
                if (sylOptions.isEmpty()) {
                    paths = emptyList()
                    break
                }
                val nextPaths = ArrayList<List<String>>()
                for (p in paths) {
                    for (s in sylOptions) {
                        if (nextPaths.size < 24) {
                            nextPaths.add(p + s)
                        }
                    }
                }
                paths = nextPaths
            }

            if (trailingChar == null) {
                paths.forEachIndexed { pathIdx, syls ->
                    phrasesBySyllables[syls]?.forEachIndexed { idx, word ->
                        emit(word, raw.length, 10_000 - pathIdx * 25 - idx * 10)
                    }
                    if (syls.size >= 2) {
                        assembleBestPhrase(syls)?.let { assembled ->
                            emit(assembled, raw.length, 9_100 - pathIdx * 25)
                        }
                    }
                }
            } else {
                // Odd trailing initial: match phrases whose last syllable starts with trailing initial!
                val initialPaths = resolveInitialFilter(trailingChar, schema)
                paths.forEachIndexed { pathIdx, prefixSyls ->
                    phrasesBySyllables.forEach { (phraseSyls, words) ->
                        if (phraseSyls.size == prefixSyls.size + 1 &&
                            phraseSyls.subList(0, prefixSyls.size) == prefixSyls &&
                            initialPaths.any { phraseSyls.last().startsWith(it) }
                        ) {
                            words.forEachIndexed { idx, w ->
                                emit(w, raw.length, 9_600 - pathIdx * 20 - idx * 10)
                            }
                        }
                    }
                }
            }

            // Prefix 2-char first syllable single characters
            val firstPair = clean.substring(0, 2)
            val firstRawConsumed = computeRawLengthForCleanChars(raw, 2)
            spMap[firstPair].orEmpty().forEachIndexed { sIdx, syl ->
                singleBySyllable[syl]?.forEachIndexed { cIdx, ch ->
                    val base = if (clean.length == 2) 9_500 else 7_200
                    emit(ch, firstRawConsumed, base - sIdx * 20 - cIdx * 5)
                }
            }
        } else if (trailingChar != null) {
            // Single initial typed (1 char): offer high-frequency characters starting with that initial
            val initials = resolveInitialFilter(trailingChar, schema)
            initials.forEachIndexed { iIdx, ini ->
                singleBySyllable[ini]?.take(6)?.forEachIndexed { cIdx, ch ->
                    emit(ch, raw.length, 8_200 - iIdx * 30 - cIdx * 6)
                }
                allSyllables
                    .filter { it.startsWith(ini) }
                    .sortedBy { it.length }
                    .take(5)
                    .forEachIndexed { sIdx, syl ->
                        singleBySyllable[syl]?.take(4)?.forEachIndexed { cIdx, ch ->
                            emit(ch, raw.length, 7_800 - iIdx * 30 - sIdx * 15 - cIdx * 5)
                        }
                    }
            }
        }

        // Also support pure Jianpin initials fallback in Shuangpin when no 2-char pair matched
        if (clean.length in 2..6) {
            phrasesByInitials[clean]?.forEachIndexed { gIdx, (_, words) ->
                words.forEachIndexed { idx, w ->
                    emit(w, raw.length, 6_800 - gIdx * 20 - idx * 8)
                }
            }
        }
    }

    private fun queryT9(
        raw: String,
        emit: (String, Int, Int) -> Unit,
    ) {
        val clean = raw.replace("'", "")
        if (clean.isEmpty()) return

        // 1. Exact single-syllable T9 matches
        t9SyllableMap[clean].orEmpty().forEachIndexed { sIdx, syl ->
            singleBySyllable[syl]?.forEachIndexed { cIdx, ch ->
                emit(ch, raw.length, 9_500 - sIdx * 25 - cIdx * 5)
            }
        }

        // 2. Multi-syllable T9 phrase matches via dynamic programming over T9 digit splits
        val dpPaths = segmentT9Digits(clean)
        dpPaths.forEachIndexed { pIdx, syls ->
            phrasesBySyllables[syls]?.forEachIndexed { wIdx, word ->
                emit(word, raw.length, 10_200 - pIdx * 20 - wIdx * 10)
            }
            if (syls.size >= 2) {
                assembleBestPhrase(syls)?.let { assembled ->
                    emit(assembled, raw.length, 9_100 - pIdx * 25)
                }
            }
        }

        // 3. Prefix T9 syllable matches for short or partial digit strings
        t9SyllableMap.entries
            .filter { it.key.startsWith(clean) && it.key != clean }
            .sortedBy { it.key.length }
            .take(5)
            .forEachIndexed { kIdx, (_, syls) ->
                syls.take(3).forEachIndexed { sIdx, syl ->
                    singleBySyllable[syl]?.take(3)?.forEachIndexed { cIdx, ch ->
                        emit(ch, raw.length, 7_600 - kIdx * 25 - sIdx * 10 - cIdx * 4)
                    }
                }
            }

        // 4. First-syllable partial commit candidates for long T9 strings
        for (len in 6.coerceAtMost(clean.length - 1) downTo 1) {
            val sub = clean.substring(0, len)
            val consumed = computeRawLengthForCleanChars(raw, len)
            t9SyllableMap[sub].orEmpty().take(3).forEachIndexed { sIdx, syl ->
                singleBySyllable[syl]?.take(4)?.forEachIndexed { cIdx, ch ->
                    emit(ch, consumed, 7_000 + len * 40 - sIdx * 15 - cIdx * 5)
                }
            }
        }
    }

    private fun formatPreedit(raw: String, schema: YuyanSchema): String = when (schema) {
        YuyanSchema.PINYIN -> {
            val segs = segmentFullPinyin(raw)
            if (segs.isEmpty()) raw else segs.joinToString("'") { it.syllable }
        }
        YuyanSchema.T9 -> {
            val clean = raw.replace("'", "")
            val dp = segmentT9Digits(clean).firstOrNull()
            if (dp != null && dp.isNotEmpty()) {
                dp.joinToString("'")
            } else {
                t9SyllableMap[clean]?.firstOrNull() ?: raw
            }
        }
        else -> {
            val spMap = shuangpinMaps[schema].orEmpty()
            val clean = raw.replace("'", "")
            val parts = ArrayList<String>()
            var i = 0
            while (i + 1 < clean.length) {
                val pair = clean.substring(i, i + 2)
                val syl = spMap[pair]?.firstOrNull() ?: pair
                parts.add(syl)
                i += 2
            }
            if (i < clean.length) {
                parts.add(clean.substring(i))
            }
            parts.joinToString("'")
        }
    }

    private fun assembleBestPhrase(syls: List<String>): String? {
        if (syls.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < syls.size) {
            var matched = false
            for (span in (syls.size - i).coerceAtMost(4) downTo 2) {
                val sub = syls.subList(i, i + span)
                val phrase = phrasesBySyllables[sub]?.firstOrNull()
                if (phrase != null) {
                    sb.append(phrase)
                    i += span
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = singleBySyllable[syls[i]]?.firstOrNull() ?: return null
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun segmentFullPinyin(raw: String): List<SegmentedSyllable> {
        val out = ArrayList<SegmentedSyllable>()
        var idx = 0
        while (idx < raw.length) {
            if (raw[idx] == '\'') {
                idx++
                continue
            }
            var bestLen = 0
            val maxSpan = (raw.length - idx).coerceAtMost(6)
            for (len in maxSpan downTo 1) {
                val sub = raw.substring(idx, idx + len)
                if (sub.contains('\'')) continue
                if (allSyllables.contains(sub)) {
                    // Ambiguity guard: e.g. "xian" vs "xi'an", prefer longer valid syllable unless remainder starts invalidly
                    if (len >= 2 && idx + len < raw.length && raw[idx + len] != '\'') {
                        val nextChar = raw[idx + len]
                        val shorter = raw.substring(idx, idx + len - 1)
                        val altPair = raw.substring(idx + len - 1, (idx + len + 1).coerceAtMost(raw.length))
                        if (sub.endsWith("n") && nextChar in "aeiouv" &&
                            allSyllables.contains(shorter) &&
                            allSyllables.any { it.startsWith(altPair) }
                        ) {
                            continue
                        }
                    }
                    bestLen = len
                    break
                }
            }
            if (bestLen > 0) {
                var consumed = bestLen
                if (idx + consumed < raw.length && raw[idx + consumed] == '\'') {
                    consumed++
                }
                out.add(SegmentedSyllable(raw.substring(idx, idx + bestLen), consumed, isExact = true))
                idx += consumed
            } else {
                // Prefix or initial fallback (e.g. "zh", "ch", "sh", or single consonant)
                val two = if (idx + 2 <= raw.length) raw.substring(idx, idx + 2) else ""
                val step = if (two in setOf("zh", "ch", "sh")) 2 else 1
                var consumed = step
                if (idx + consumed < raw.length && raw[idx + consumed] == '\'') {
                    consumed++
                }
                out.add(SegmentedSyllable(raw.substring(idx, idx + step), consumed, isExact = false))
                idx += consumed
            }
        }
        return out
    }

    private fun segmentT9Digits(digits: String): List<List<String>> {
        if (digits.isEmpty() || digits.length > 18) return emptyList()
        val memo = Array<List<List<String>>?>(digits.length + 1) { null }
        memo[digits.length] = listOf(emptyList())
        for (i in digits.length - 1 downTo 0) {
            val pathsHere = ArrayList<List<String>>()
            val maxLen = (digits.length - i).coerceAtMost(6)
            for (len in maxLen downTo 1) {
                val sub = digits.substring(i, i + len)
                val syls = t9SyllableMap[sub].orEmpty()
                if (syls.isEmpty()) continue
                val suffixes = memo[i + len].orEmpty()
                if (suffixes.isEmpty()) continue
                for (syl in syls.take(10)) {
                    for (suf in suffixes) {
                        if (pathsHere.size < 64) {
                            pathsHere.add(listOf(syl) + suf)
                        }
                    }
                }
            }
            memo[i] = pathsHere
        }
        return memo[0].orEmpty()
    }

    private fun resolveInitialFilter(ch: Char, schema: YuyanSchema): List<String> {
        return when {
            (schema == YuyanSchema.FLYPY || schema == YuyanSchema.ZIRANMA ||
                schema == YuyanSchema.SOGOU || schema == YuyanSchema.MSPY) && ch == 'v' -> listOf("zh")
            (schema == YuyanSchema.FLYPY || schema == YuyanSchema.ZIRANMA ||
                schema == YuyanSchema.SOGOU || schema == YuyanSchema.MSPY) && ch == 'i' -> listOf("ch")
            (schema == YuyanSchema.FLYPY || schema == YuyanSchema.ZIRANMA ||
                schema == YuyanSchema.SOGOU || schema == YuyanSchema.MSPY) && ch == 'u' -> listOf("sh")
            ch == 'o' -> listOf("a", "e", "o")
            else -> listOf(ch.toString())
        }
    }

    private fun computeRawLengthForCleanChars(raw: String, cleanTarget: Int): Int {
        var seen = 0
        for (i in raw.indices) {
            if (raw[i] != '\'') {
                seen++
                if (seen == cleanTarget) {
                    var end = i + 1
                    while (end < raw.length && raw[end] == '\'') end++
                    return end
                }
            }
        }
        return raw.length
    }

    companion object {
        @Volatile
        private var cachedDefault: YuyanLexicon? = null

        fun fromStream(stream: InputStream): YuyanLexicon {
            val lines = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readLines() }
            return fromLines(lines)
        }

        fun defaultBuiltIn(): YuyanLexicon {
            cachedDefault?.let { return it }
            synchronized(this) {
                cachedDefault?.let { return it }
                val stream = YuyanLexicon::class.java.classLoader
                    ?.getResourceAsStream("assets/opal_yuyan_dict.txt")
                val built = if (stream != null) {
                    fromStream(stream)
                } else {
                    fromLines(EMBEDDED_CORE_LINES)
                }
                cachedDefault = built
                return built
            }
        }

        fun fromLines(lines: Iterable<String>): YuyanLexicon {
            val singleMap = LinkedHashMap<String, MutableList<String>>()
            val phraseMap = LinkedHashMap<List<String>, MutableList<String>>()
            val initialMap = LinkedHashMap<String, MutableList<Pair<List<String>, List<String>>>>()

            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val keyPart = line.substring(0, tab).trim().lowercase()
                val words = line.substring(tab + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (keyPart.isEmpty() || words.isEmpty()) continue

                val syls = keyPart.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (syls.size == 1 && syls[0].all { it in 'a'..'z' }) {
                    val list = singleMap.getOrPut(syls[0]) { ArrayList() }
                    for (w in words) {
                        if (!list.contains(w)) list.add(w)
                    }
                } else if (syls.size >= 2 && syls.all { s -> s.all { it in 'a'..'z' } }) {
                    val pList = phraseMap.getOrPut(syls) { ArrayList() }
                    for (w in words) {
                        if (!pList.contains(w)) pList.add(w)
                    }
                    val initials = syls.joinToString("") { it.take(1) }
                    initialMap.getOrPut(initials) { ArrayList() }.add(syls to words)
                }
            }

            val allSyllables = LinkedHashSet<String>(singleMap.keys)
            phraseMap.keys.forEach { syls -> allSyllables.addAll(syls) }

            val shuangpinSchemas = listOf(
                YuyanSchema.FLYPY,
                YuyanSchema.ZIRANMA,
                YuyanSchema.SOGOU,
                YuyanSchema.MSPY,
            )
            val spMaps = LinkedHashMap<YuyanSchema, Map<String, List<String>>>()
            for (sc in shuangpinSchemas) {
                val map = LinkedHashMap<String, MutableList<String>>()
                for (syl in allSyllables) {
                    for (code in encodeSyllableToShuangpin(syl, sc)) {
                        val list = map.getOrPut(code) { ArrayList() }
                        if (!list.contains(syl)) list.add(syl)
                    }
                }
                spMaps[sc] = map
            }

            val t9Map = LinkedHashMap<String, MutableList<String>>()
            for (syl in allSyllables) {
                val digits = syllableToT9Digits(syl)
                if (digits.isNotEmpty()) {
                    val list = t9Map.getOrPut(digits) { ArrayList() }
                    if (!list.contains(syl)) list.add(syl)
                }
            }

            return YuyanLexicon(
                singleBySyllable = singleMap,
                phrasesBySyllables = phraseMap,
                phrasesByInitials = initialMap,
                allSyllables = allSyllables,
                shuangpinMaps = spMaps,
                t9SyllableMap = t9Map,
            )
        }

        internal fun splitInitialAndFinal(syllable: String): Pair<String, String> {
            return when {
                syllable.startsWith("zh") -> "zh" to syllable.substring(2)
                syllable.startsWith("ch") -> "ch" to syllable.substring(2)
                syllable.startsWith("sh") -> "sh" to syllable.substring(2)
                syllable.isNotEmpty() && syllable[0] in "bpmfdtnlgkhjqxrzcsyw" ->
                    syllable.substring(0, 1) to syllable.substring(1)
                else -> "" to syllable
            }
        }

        /** Encodes a standard Mandarin Pinyin syllable into its 2-key Shuangpin code(s) for [schema]. */
        fun encodeSyllableToShuangpin(syllable: String, schema: YuyanSchema): List<String> {
            val (ini, fin) = splitInitialAndFinal(syllable)
            if (ini.isEmpty()) {
                return when (schema) {
                    YuyanSchema.FLYPY -> listOfNotNull(
                        when (fin) {
                            "a" -> "aa"
                            "ai" -> "ai"
                            "an" -> "an"
                            "ang" -> "ah"
                            "ao" -> "ao"
                            "e" -> "ee"
                            "ei" -> "ei"
                            "en" -> "en"
                            "eng" -> "eg"
                            "er" -> "er"
                            "o" -> "oo"
                            "ou" -> "ou"
                            else -> null
                        },
                    )
                    YuyanSchema.ZIRANMA, YuyanSchema.SOGOU, YuyanSchema.MSPY -> {
                        val oCode = when (fin) {
                            "a" -> "oa"
                            "ai" -> "ol"
                            "an" -> "oj"
                            "ang" -> "oh"
                            "ao" -> "ok"
                            "e" -> "oe"
                            "ei" -> "oz"
                            "en" -> "of"
                            "eng" -> "og"
                            "er" -> "or"
                            "o" -> "oo"
                            "ou" -> "ob"
                            else -> null
                        }
                        val naturalAlt = when (fin) {
                            "a" -> "aa"
                            "ai" -> "ai"
                            "an" -> "an"
                            "ang" -> "ah"
                            "ao" -> "ao"
                            "e" -> "ee"
                            "ei" -> "ei"
                            "en" -> "en"
                            "eng" -> "eg"
                            "er" -> "er"
                            "ou" -> "ou"
                            else -> null
                        }
                        listOfNotNull(oCode, naturalAlt).distinct()
                    }
                    else -> emptyList()
                }
            }

            val iniKey = when (ini) {
                "zh" -> "v"
                "ch" -> "i"
                "sh" -> "u"
                else -> ini
            }

            val finKeys: List<String> = when (schema) {
                YuyanSchema.FLYPY -> listOfNotNull(
                    when (fin) {
                        "a" -> "a"
                        "o", "uo" -> "o"
                        "e" -> "e"
                        "i" -> "i"
                        "u" -> "u"
                        "v", "ui" -> "v"
                        "ai" -> "d"
                        "ei" -> "w"
                        "ao" -> "c"
                        "ou" -> "z"
                        "iu" -> "q"
                        "ie" -> "p"
                        "ue", "ve" -> "t"
                        "an" -> "j"
                        "en" -> "f"
                        "in" -> "b"
                        "un" -> "y"
                        "ang" -> "h"
                        "eng" -> "g"
                        "ing", "uai" -> "k"
                        "ong", "iong" -> "s"
                        "ia", "ua" -> "x"
                        "iao" -> "n"
                        "ian" -> "m"
                        "uan" -> "r"
                        "iang", "uang" -> "l"
                        else -> null
                    },
                )
                YuyanSchema.ZIRANMA -> listOfNotNull(
                    when (fin) {
                        "a" -> "a"
                        "o", "uo" -> "o"
                        "e" -> "e"
                        "i" -> "i"
                        "u" -> "u"
                        "v", "ui" -> "v"
                        "ai" -> "l"
                        "ei" -> "z"
                        "ao" -> "k"
                        "ou" -> "b"
                        "iu" -> "q"
                        "ie" -> "x"
                        "ue", "ve" -> "t"
                        "an" -> "j"
                        "en" -> "f"
                        "in" -> "n"
                        "un" -> "p"
                        "ang" -> "h"
                        "eng" -> "g"
                        "ing", "uai" -> "y"
                        "ong", "iong" -> "s"
                        "ia", "ua" -> "w"
                        "iao" -> "c"
                        "ian" -> "m"
                        "uan" -> "r"
                        "iang", "uang" -> "d"
                        else -> null
                    },
                )
                YuyanSchema.SOGOU -> when (fin) {
                    "ing" -> listOf(";", "y")
                    "v", "uai" -> listOf("y")
                    "ue", "ve" -> listOf("t")
                    else -> encodeSyllableToShuangpin(syllable, YuyanSchema.ZIRANMA)
                        .map { it.substring(1) }
                }
                YuyanSchema.MSPY -> when (fin) {
                    "ing" -> listOf(";", "y")
                    "v", "uai" -> listOf("y")
                    "ve" -> listOf("v", "t")
                    "ue" -> listOf("t")
                    else -> encodeSyllableToShuangpin(syllable, YuyanSchema.ZIRANMA)
                        .map { it.substring(1) }
                }
                else -> emptyList()
            }
            return finKeys.map { fk -> iniKey + fk }.distinct()
        }

        fun syllableToT9Digits(syllable: String): String {
            val sb = StringBuilder(syllable.length)
            for (ch in syllable) {
                val d = when (ch) {
                    'a', 'b', 'c' -> '2'
                    'd', 'e', 'f' -> '3'
                    'g', 'h', 'i' -> '4'
                    'j', 'k', 'l' -> '5'
                    'm', 'n', 'o' -> '6'
                    'p', 'q', 'r', 's' -> '7'
                    't', 'u', 'v' -> '8'
                    'w', 'x', 'y', 'z' -> '9'
                    else -> return ""
                }
                sb.append(d)
            }
            return sb.toString()
        }

        // Minimal JVM fallback seed used when running pure unit tests without Android AssetManager
        private val EMBEDDED_CORE_LINES = listOf(
            "ni\t你 泥 拟 逆",
            "hao\t好 号 毫 豪",
            "ma\t吗 妈 马 码",
            "shi\t是 时 事 世",
            "jie\t界 节 解 接",
            "zhong\t中 重 种 钟",
            "guo\t国 过 果 锅",
            "shu\t输 书 数 树",
            "ru\t入 如 乳 辱",
            "fa\t法 发 罚 乏",
            "yu\t语 雨 育 遇",
            "yan\t燕 言 眼 演",
            "shuang\t双 爽 霜",
            "pin\t拼 品 贫 频",
            "xiao\t小 笑 校 消",
            "he\t鹤 和 喝 合",
            "zi\t自 字 子 紫",
            "ran\t然 染 燃",
            "sou\t搜 艘",
            "gou\t狗 够 沟 构",
            "wei\t微 为 位 味",
            "ruan\t软 阮",
            "jiu\t九 就 酒 久",
            "gong\t宫 工 公 共",
            "ge\t格 个 各 歌",
            "wo\t我 握 窝",
            "men\t们 门 闷",
            "ke\t可 科 课 客",
            "yi\t以 一 已 意",
            "ni hao\t你好 拟好",
            "ni hao ma\t你好吗",
            "shi jie\t世界 视觉",
            "ni hao shi jie\t你好世界",
            "zhong guo\t中国",
            "shu ru fa\t输入法",
            "yu yan\t语燕 语言 雨燕",
            "yu yan shu ru fa\t语燕输入法 雨燕输入法",
            "shuang pin\t双拼",
            "xiao he\t小鹤 校核",
            "xiao he shuang pin\t小鹤双拼",
            "zi ran ma\t自然码",
            "sou gou\t搜狗",
            "sou gou shuang pin\t搜狗双拼",
            "wei ruan\t微软",
            "wei ruan shuang pin\t微软双拼",
            "jiu gong ge\t九宫格",
            "wei shi me\t为什么",
            "wo men\t我们",
            "ke yi\t可以 刻意",
        )
    }
}
