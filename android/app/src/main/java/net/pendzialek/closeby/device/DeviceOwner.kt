package net.pendzialek.closeby.device

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import net.pendzialek.closeby.ui.MainActivity

/** Needed for `dpm set-device-owner`; all logic lives in [DeviceOwner]. */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Device owner turns a tablet into a frame that nobody can leave by accident:
 * CloseBy is the home screen and locked in, camera and microphone are granted
 * without prompts, and updates install silently.
 *
 * Set up once via adb on a device without accounts:
 * `adb shell dpm set-device-owner net.pendzialek.closeby/.device.AdminReceiver`
 */
object DeviceOwner {
    fun isActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    /** During maintenance the status bar comes back, so Android settings are reachable. */
    fun setMaintenance(context: Context, active: Boolean) {
        if (!isActive(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setStatusBarDisabled(ComponentName(context, AdminReceiver::class.java), !active)
    }

    /**
     * Undoes everything and gives up device owner, so no factory reset is
     * needed. The caller must have left lock task mode before.
     */
    fun release(context: Context) {
        if (!isActive(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        dpm.setStatusBarDisabled(admin, false)
        dpm.setKeyguardDisabled(admin, false)
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    fun applyFramePolicies(context: Context) {
        if (!isActive(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AdminReceiver::class.java)

        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        // no home button, recents or notification shade while locked
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, MainActivity::class.java))

        dpm.setKeyguardDisabled(admin, true)
        dpm.setStatusBarDisabled(admin, true)
        val plugged = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())

        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.forEach {
            dpm.setPermissionGrantState(admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }
}
