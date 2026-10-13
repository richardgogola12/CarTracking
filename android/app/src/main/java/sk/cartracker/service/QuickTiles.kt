package sk.cartracker.service

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import sk.cartracker.R
import sk.cartracker.container
import sk.cartracker.ui.MainActivity
import sk.cartracker.ui.formatDistance

/**
 * Tlačidlá v rýchlej lište Androidu (stiahnutá horná lišta → upraviť → pretiahnuť „Jazda“ / „Chôdza“).
 * Ťuknutie spustí alebo ukončí záznam.
 */
abstract class BaseTrackTile : TileService() {
    protected abstract val walk: Boolean

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (!container.session.isLoggedIn) { open(Intent(this, MainActivity::class.java)); return }
        val st = TrackingState.state.value
        val active = if (walk) st == DriveState.WALKING else st == DriveState.DRIVING || st == DriveState.CANDIDATE
        val action = when {
            active -> TrackingService.ACTION_STOP
            walk -> TrackingService.ACTION_MANUAL_WALK
            else -> TrackingService.ACTION_MANUAL_START
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java).setAction(action))
        } catch (e: Exception) {
            // Niektoré verzie Androidu nedovolia spustiť službu priamo z lišty → cez neviditeľnú aktivitu
            open(Intent(this, TileActionActivity::class.java).putExtra(TileActionActivity.EXTRA_ACTION, action))
        }
        refresh()
    }

    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, if (walk) 31 else 30, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    protected fun refresh() {
        val tile = qsTile ?: return
        val st = TrackingState.state.value
        val live = TrackingState.live.value
        val active = if (walk) st == DriveState.WALKING else st == DriveState.DRIVING || st == DriveState.CANDIDATE
        tile.icon = Icon.createWithResource(this, if (walk) R.drawable.ic_walk else R.drawable.ic_car)
        tile.label = if (walk) "Chôdza" else "Jazda"
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !active -> "Spustiť"
                st == DriveState.CANDIDATE -> "Overujem…"
                walk && live != null -> "${live.steps} krokov"
                live != null -> "${formatDistance(live.distanceM)} · ${live.speedKmh.toInt()} km/h"
                else -> "Zapnuté"
            }
        }
        tile.updateTile()
    }

    companion object {
        /** Android 13+: systémový dialóg „Pridať tlačidlo do rýchlej lišty“. */
        fun requestAdd(ctx: Context, walk: Boolean, onResult: (String) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) { onResult("Stiahnite hornú lištu → ceruzka (Upraviť) → pretiahnite tlačidlo Jazda / Chôdza."); return }
            val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(ctx, if (walk) WalkTile::class.java else DriveTile::class.java),
                if (walk) "Chôdza" else "Jazda",
                Icon.createWithResource(ctx, if (walk) R.drawable.ic_walk else R.drawable.ic_car),
                ctx.mainExecutor,
            ) { code ->
                onResult(when (code) {
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tlačidlo je v rýchlej lište ✓"
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tlačidlo už v lište je"
                    else -> "Tlačidlo nebolo pridané"
                })
            }
        }

        /** Požiada systém o prekreslenie oboch tlačidiel (volá sa pri zmene stavu). */
        fun requestUpdate(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, DriveTile::class.java)) }
            runCatching { requestListeningState(ctx, ComponentName(ctx, WalkTile::class.java)) }
        }
    }
}

class DriveTile : BaseTrackTile() { override val walk = false }
class WalkTile : BaseTrackTile() { override val walk = true }

/** Neviditeľná aktivita – spustí službu v popredí ako reakciu na ťuknutie a hneď sa zavrie. */
class TileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_ACTION)?.let { action ->
            runCatching { ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java).setAction(action)) }
        }
        finish()
    }

    companion object { const val EXTRA_ACTION = "action" }
}
