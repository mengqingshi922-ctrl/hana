package com.hana.agent.core.subgoal

import kotlinx.serialization.Serializable

@Serializable
data class SubgoalCheckpoint(
    val checkpointId: String,
    val subgoalName: String,
    val packageName: String,
    val activityName: String? = null,
    val uiSnapshotHash: Long,
    val registers: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    init {
        try {
            val field = SubgoalCheckpoint::class.java.getDeclaredField("registers")
            field.isAccessible = true
            field.set(this, registers.toMap())
        } catch (_: Throwable) {}
    }
}

class SubgoalCheckpointManager {
    private val checkpoints = mutableListOf<SubgoalCheckpoint>()

    @Synchronized
    fun saveCheckpoint(checkpoint: SubgoalCheckpoint) {
        checkpoints.add(checkpoint)
    }

    @Synchronized
    fun getLatestCheckpoint(): SubgoalCheckpoint? {
        return checkpoints.lastOrNull()
    }

    @Synchronized
    fun rollbackTo(checkpointId: String): SubgoalCheckpoint? {
        val index = checkpoints.indexOfFirst { it.checkpointId == checkpointId }
        if (index < 0) return null
        val target = checkpoints[index]
        while (checkpoints.size > index + 1) {
            checkpoints.removeAt(checkpoints.lastIndex)
        }
        return target
    }

    @Synchronized
    fun getCheckpoints(): List<SubgoalCheckpoint> = checkpoints.toList()

    @Synchronized
    fun clear() {
        checkpoints.clear()
    }
}
