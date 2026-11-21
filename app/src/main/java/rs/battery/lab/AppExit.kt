package rs.battery.lab

import android.app.Activity
import android.content.Context

/** Ends monitoring without deleting recordings or forgetting the paired adapter. */
internal object AppExit {
    fun stopAndExit(activity: Activity): Boolean {
        // commit() makes the opt-out durable before the task is closed.
        val saved = activity.getSharedPreferences("battery_lab", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_connect", false).commit()
        ObdBus.stop(activity)
        if (saved) activity.finishAffinity()
        return saved
    }
}
