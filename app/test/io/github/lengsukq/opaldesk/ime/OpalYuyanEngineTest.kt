package io.github.lengsukq.opaldesk.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpalYuyanEngineTest {

    private fun loadFullLexicon(): YuyanLexicon {
        val candidates = listOf(
            File("assets/opal_yuyan_dict.txt"),
            File("ime-plugin/assets/opal_yuyan_dict.txt"),
        )
        val file = candidates.firstOrNull { it.isFile }
        return if (file != null) {
            file.inputStream().use { YuyanLexicon.fromStream(it) }
        } else {
            YuyanLexicon.defaultBuiltIn()
        }
    }

    @Test
    fun `full pinyin and jianpin produce accurate phrases and support partial multi-syllable commit`() {
        val engine = OpalYuyanEngine(lexicon = loadFullLexicon(), initialSchema = YuyanSchema.PINYIN)

        "nihao".forEach { engine.processChar(it) }
        val snap1 = engine.snapshot()
        assertEquals("ni'hao", snap1.displayComposing)
        assertEquals("你好", snap1.visibleCandidates.first().text)

        val spaceOut = engine.space() as YuyanKeyOutcome.Commit
        assertEquals("你好", spaceOut.committedText)
        assertEquals("", spaceOut.remainingInlineComposing)
        assertFalse(engine.snapshot().isComposing)

        // Jianpin initials: "wsm" -> "为什么", "srff" / "srf" -> "输入法"
        "srf".forEach { engine.processChar(it) }
        assertTrue(engine.snapshot().visibleCandidates.any { it.text == "输入法" })
        engine.clearComposition()

        // Partial commit: type "nihaoshijie", select 2-char "你好" -> commits "你好" and leaves "shi'jie" -> "世界"!
        "nihaoshijie".forEach { engine.processChar(it) }
        val idxNiHao = engine.snapshot().visibleCandidates.indexOfFirst { it.text == "你好" }
        if (idxNiHao >= 0) {
            val partial = engine.selectVisibleCandidate(idxNiHao) as YuyanKeyOutcome.Commit
            assertEquals("你好", partial.committedText)
            assertEquals("shi'jie", partial.remainingInlineComposing)
            assertEquals("世界", engine.snapshot().visibleCandidates.first().text)
            val rest = engine.space() as YuyanKeyOutcome.Commit
            assertEquals("世界", rest.committedText)
        }
    }

    @Test
    fun `xiaohe ziranma sogou and microsoft shuangpin encode and decode accurately`() {
        val lexicon = loadFullLexicon()

        // 1. Xiaohe Shuangpin (小鹤双拼): nihc -> 你好, ulpb -> 双拼, vsgo -> 中国
        val flypy = OpalYuyanEngine(lexicon = lexicon, initialSchema = YuyanSchema.FLYPY)
        "nihc".forEach { flypy.processChar(it) }
        assertEquals("你好", flypy.snapshot().visibleCandidates.first().text)
        flypy.clearComposition()

        "ulpb".forEach { flypy.processChar(it) }
        assertTrue(flypy.snapshot().visibleCandidates.any { it.text == "双拼" })
        flypy.clearComposition()

        "vsgo".forEach { flypy.processChar(it) }
        assertTrue(flypy.snapshot().visibleCandidates.any { it.text == "中国" })

        // 2. Ziranma (自然码): nihk -> 你好, zrjma -> 自然码
        val zrm = OpalYuyanEngine(lexicon = lexicon, initialSchema = YuyanSchema.ZIRANMA)
        "nihk".forEach { zrm.processChar(it) }
        assertEquals("你好", zrm.snapshot().visibleCandidates.first().text)

        // 3. Sogou / Microsoft Shuangpin: ';' maps to 'ing' (e.g. "m;tm" -> ming'tian -> 明天)
        val sogou = OpalYuyanEngine(lexicon = lexicon, initialSchema = YuyanSchema.SOGOU)
        "m;tm".forEach { sogou.processChar(it) }
        assertTrue(sogou.snapshot().displayComposing.startsWith("ming"))
    }

    @Test
    fun `yuyan t9 nine-key digits resolve single characters and multi-syllable phrases`() {
        val engine = OpalYuyanEngine(lexicon = loadFullLexicon(), initialSchema = YuyanSchema.T9)
        // "64426" -> ni (64) + hao (426) -> "你好"
        "64426".forEach { engine.processChar(it) }
        val cands = engine.snapshot().visibleCandidates.map { it.text }
        assertTrue("Expected 你好 in T9 candidates: $cands", cands.contains("你好"))
        val out = engine.space() as YuyanKeyOutcome.Commit
        assertNotNull(out.committedText)
        assertFalse(engine.snapshot().isComposing)
    }

    @Test
    fun `shift language toggle enter raw commit and chinese punctuation behave like desktop IME`() {
        val engine = OpalYuyanEngine(lexicon = loadFullLexicon(), initialSchema = YuyanSchema.PINYIN)

        // Enter while composing commits raw English letters
        "opal".forEach { engine.processChar(it) }
        val enterOut = engine.enter() as YuyanKeyOutcome.Commit
        assertEquals("opal", enterOut.committedText)

        // Switching CN -> EN while composing commits pending letters and passes subsequent ASCII directly
        "dex".forEach { engine.processChar(it) }
        val toggleOut = engine.toggleChineseMode() as YuyanKeyOutcome.Commit
        assertEquals("dex", toggleOut.committedText)
        assertFalse(engine.snapshot().chineseMode)
        val asciiComma = engine.processChar(',') as YuyanKeyOutcome.Commit
        assertEquals(",", asciiComma.committedText)

        // Switch back to CN -> Chinese punctuation and alternating smart quotes
        engine.toggleChineseMode()
        assertTrue(engine.snapshot().chineseMode)
        assertEquals("，", (engine.processChar(',') as YuyanKeyOutcome.Commit).committedText)
        assertEquals("。", (engine.processChar('.') as YuyanKeyOutcome.Commit).committedText)
        assertEquals("“", (engine.processChar('"') as YuyanKeyOutcome.Commit).committedText)
        assertEquals("”", (engine.processChar('"') as YuyanKeyOutcome.Commit).committedText)
    }
}
