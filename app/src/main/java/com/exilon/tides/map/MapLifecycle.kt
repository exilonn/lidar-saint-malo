package com.exilon.tides.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

/**
 * Creates a [MapView] bound to the composition's lifecycle and forwards **every** MapView callback —
 * the well-known leak/crash point when hosting a native MapView in Compose. A MapView that misses
 * `onDestroy` leaks its GL surface + native context; one that misses `onStart`/`onResume` renders
 * nothing.
 *
 * Notes on the wiring:
 * - `onCreate(null)` is called once, directly, when the effect runs — NOT from an `ON_CREATE` event,
 *   because the host activity is already STARTED/RESUMED when this screen appears inside an
 *   `AnimatedContent`, so `ON_CREATE` would never fire and the MapView would stay uninitialized.
 * - For the same reason we manually bring the freshly-created MapView up to the owner's *current*
 *   state, since a [LifecycleEventObserver] only receives *future* transitions.
 * - `onDispose` calls `onDestroy()` only. When navigating away while the activity stays RESUMED the
 *   observer never drove pause/stop, but MapLibre's `onDestroy()` tears down fully regardless; when
 *   the activity is genuinely stopping, the observer already drove `onStop()` and `onDestroy()` then
 *   follows. Calling pause/stop again here would risk double-dispatch, so we don't.
 * - `onLowMemory` has no Lifecycle event, so it's forwarded via [ComponentCallbacks2].
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        // Catch up to the current state (observer only sees future transitions).
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()

        val memoryCallback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                mapView.onLowMemory()
            }

            @Suppress("DEPRECATION") // TRIM_MEMORY_* constants deprecated in API 34; still valid signal
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }
        }
        val appContext = context.applicationContext
        appContext.registerComponentCallbacks(memoryCallback)

        onDispose {
            appContext.unregisterComponentCallbacks(memoryCallback)
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    return mapView
}
