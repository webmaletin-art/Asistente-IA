package com.aiquickassist.service

import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Lifecycle mínimo para poder alojar Compose dentro de una ventana de un Service. */
class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; store.clear() }

    fun compose(ctx: Context, content: @Composable () -> Unit): ComposeView = ComposeView(ctx).apply {
        setViewTreeLifecycleOwner(this@ServiceLifecycleOwner)
        setViewTreeSavedStateRegistryOwner(this@ServiceLifecycleOwner)
        setViewTreeViewModelStoreOwner(this@ServiceLifecycleOwner)
        setContent(content)
    }
}

fun overlayParams(w: Int, h: Int, flags: Int, gravity: Int = android.view.Gravity.TOP or android.view.Gravity.START) =
    WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, android.graphics.PixelFormat.TRANSLUCENT)
        .also { it.gravity = gravity }

