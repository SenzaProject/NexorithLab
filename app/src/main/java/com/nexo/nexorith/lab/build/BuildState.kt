package com.nexo.nexorith.lab.build

import java.util.concurrent.CopyOnWriteArrayList

/** Status build yang dibagi antara BuildService dan layar. Listener dipanggil dari thread build. */
object BuildState {
    data class Snapshot(
        val running: Boolean = false,
        val projectName: String? = null,
        val stageIndex: Int = 0,
        val stageCount: Int = 0,
        val stageName: String? = null,
        val lastResult: String? = null
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logs = ArrayList<String>()

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }
    fun addLogListener(l: (String) -> Unit) { logListeners.add(l) }
    fun removeLogListener(l: (String) -> Unit) { logListeners.remove(l) }

    fun logs(): List<String> = synchronized(logs) { ArrayList(logs) }

    fun log(line: String) {
        synchronized(logs) {
            logs.add(line)
            if (logs.size > 3000) logs.removeAt(0)
        }
        logListeners.forEach { it(line) }
    }

    fun begin(projectName: String, stageCount: Int) {
        synchronized(logs) { logs.clear() }
        update(Snapshot(running = true, projectName = projectName, stageCount = stageCount))
    }

    fun stage(index: Int, name: String) {
        update(snapshot.copy(stageIndex = index, stageName = name))
    }

    fun finish(result: String) {
        update(snapshot.copy(running = false, lastResult = result))
    }

    private fun update(s: Snapshot) {
        snapshot = s
        listeners.forEach { it(s) }
    }
}
