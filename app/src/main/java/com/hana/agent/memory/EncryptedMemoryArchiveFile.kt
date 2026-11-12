package com.hana.agent.memory

import android.security.keystore.UserNotAuthenticatedException
import com.hana.agent.core.memory.MemoryArchivePersistence
import com.hana.agent.core.memory.MemoryItem
import com.hana.agent.settings.KeyCipher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * 加密冷归档文件 (`memory/memory_archive.bin`)。
 * 活跃记忆达到上限后，低分老旧条目由 [com.hana.agent.core.memory.MemoryStore] 优雅迁入此密文归档文件，
 * 支持按需检索与恢复。
 */
class EncryptedMemoryArchiveFile(
    dir: File,
    private val cipher: KeyCipher,
) : MemoryArchivePersistence {
    private val memDir = File(dir, "memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MemoryItem.serializer())

    @Synchronized
    override fun loadArchive(): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            emptyList()
        }
    }

    @Synchronized
    override fun appendArchive(items: List<MemoryItem>) {
        if (items.isEmpty()) return
        val existing = runCatching { loadArchive() }.getOrDefault(emptyList())
        val byId = existing.associateByTo(LinkedHashMap()) { it.id }
        items.forEach { byId[it.id] = it }
        saveArchive(byId.values.toList())
    }

    @Synchronized
    override fun saveArchive(items: List<MemoryItem>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun clearArchive() {
        if (tmp.exists()) tmp.delete()
        if (file.exists()) file.delete()
    }

    private companion object {
        const val FILE = "memory_archive.bin"
    }
}
