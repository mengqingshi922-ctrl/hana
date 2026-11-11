package com.hana.agent.task

import com.hana.agent.core.child.ChildPhase
import com.hana.agent.core.child.ChildRecord
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileChildRunStoreTest {
    @Test fun runningChildBecomesUnknownAfterRestart() {
        val file = Files.createTempDirectory("child-test").resolve("children.json").toFile()
        FileChildRunStore(file).save(ChildRecord("child", "parent", ChildPhase.Running, atMs = 10))
        val restored = FileChildRunStore(file)
        assertEquals(ChildPhase.Unknown, restored.get("child")?.phase)
        assertEquals(ChildPhase.Unknown, FileChildRunStore(file).get("child")?.phase)
    }
}
