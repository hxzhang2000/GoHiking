package com.gohiking.core.data.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文件命名（F-IO-06/07/13） */
class FileNamerTest {

    @Test
    fun `illegal characters replaced and length truncated`() {
        assertEquals("a_b", FileNamer.sanitize("a/b"))
        assertEquals("a_b", FileNamer.sanitize("a\\b"))
        assertEquals("a_b_c_d_e_f_g", FileNamer.sanitize("a:b*c?d\"e<f>g|"))
        assertEquals(50, FileNamer.sanitize("x".repeat(80)).length)
        assertEquals("record", FileNamer.sanitize("///"))
    }

    @Test
    fun `trip file name follows F-IO-06 format`() {
        val name = FileNamer.tripFileName("梧桐山", 1_758_240_723_000)
        assertTrue(name.startsWith("GoHiking_梧桐山_"))
        assertTrue(name.endsWith(".json"))
        assertTrue(Regex("""GoHiking_梧桐山_\d{8}_\d{6}\.json""").matches(name))
    }

    @Test
    fun `backup file name follows F-IO-07 format`() {
        assertTrue(Regex("""GoHiking_Backup_\d{8}_\d{6}\.zip""").matches(FileNamer.backupFileName(0L)))
    }

    @Test
    fun `dedupe appends numeric suffix without overwriting`() {
        val existing = setOf("a.json", "a_1.json")
        val next = FileNamer.dedupe("a.json") { it in existing }
        assertEquals("a_2.json", next)
        assertEquals("fresh.json", FileNamer.dedupe("fresh.json") { false })
    }
}
