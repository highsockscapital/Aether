package com.highsockscapital.sunshine.accessibility

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * SunshineKillSwitchTileService — the constitution's emergency brake.
 *
 * A Quick Settings tile that reflects whether Sunshine's accessibility
 * engine is alive, and can cut it instantly:
 *
 *  - tap while active   → disableSelf() (hands+sheets off immediately)
 *  - tap while inactive → deep-link into system accessibility settings
 *    so the user can re-enable with one more tap
 */
class SunshineKillSwitchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        val service = SunshineAccessibilityService.instance
        if (service != null) {
            Log.i(TAG, "Kill-switch tapped: disabling Sunshine eyes/hands")
            service.disableSelf()
        } else {
            Log.i(TAG, "Kill-switch tapped: opening accessibility settings")
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not open accessibility settings", t)
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = SunshineAccessibilityService.isActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Sunshine"
        tile.contentDescription = if (active) {
            "Sunshine accessibility is on — tap to disable"
        } else {
            "Sunshine accessibility is off — tap to open settings"
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "SunshineKillSwitch"
    }
}
