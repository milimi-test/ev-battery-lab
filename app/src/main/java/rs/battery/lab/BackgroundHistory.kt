package rs.battery.lab

/** Large archived JSON logs must not compete with touch and map rendering on slower phones. */
internal fun backgroundHistory(block: () -> Unit) {
    val tid = android.os.Process.myTid()
    val original = android.os.Process.getThreadPriority(tid)
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
    try { block() }
    finally { android.os.Process.setThreadPriority(original) }
}
