package com.hana.agent.memory

import com.hana.agent.core.memory.MemoryItem
import com.hana.agent.core.memory.MemoryKind
import com.hana.agent.core.memory.MemorySource
import com.hana.agent.core.memory.MemoryStatus
import com.hana.agent.core.memory.MemoryStore
import com.hana.agent.settings.AesGcmKeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class EncryptedMemoryArchiveFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun coldArchiveEncryptsAndIntegratesWithMemoryStore() {
        var now = 1_000L
        var seq = 0
        val cipher = AesGcmKeyCipher { key }
        val activeFile = EncryptedMemoryFile(tmp.root, cipher) { now }
        val archiveFile = EncryptedMemoryArchiveFile(tmp.root, cipher)

        val store = MemoryStore(
            persistence = activeFile,
            clock = { now },
            ids = { "m${++seq}" },
            limit = 2,
            archivePersistence = archiveFile,
        )
        val src = MemorySource("manual", atMs = now)
        val m1 = store.add(MemoryKind.fact, "旧事实：喜欢喝乌龙茶", listOf("茶"), src).getOrThrow().item
        now += 86_400_000L
        val m2 = store.add(MemoryKind.fact, "常用事实：住在徐汇", listOf("住址"), src).getOrThrow().item
        store.markAccessed(listOf(m2.id))
        now += 86_400_000L
        val m3 = store.add(MemoryKind.preference, "点餐不要放蒜", listOf("忌口"), src).getOrThrow().item

        assertEquals(setOf(m2.id, m3.id), store.items.value.map { it.id }.toSet())
        assertEquals(listOf(m1.id), store.archivedItems().map { it.id })

        val bin = File(tmp.root, "memory/memory_archive.bin")
        assertTrue(bin.exists())
        val raw = bin.readBytes()
        val needle = "乌龙茶".toByteArray(Charsets.UTF_8)
        assertFalse(raw.indices.any { i -> i + needle.size <= raw.size && needle.indices.all { raw[i + it] == needle[it] } })

        archiveFile.clearArchive()
        assertFalse(bin.exists())
        assertTrue(archiveFile.loadArchive().isEmpty())
    }
}
