package app.axis

import android.app.Application
import app.axis.device.CrashLog

class AxisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
