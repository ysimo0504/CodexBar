package com.ysimo.codexbar.ink

import android.util.Log
import android.view.View
import com.ysimo.codexbar.ink.core.RegionKey
import com.ysimo.codexbar.ink.core.SemanticChangeSet
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class OnyxDisplayAdapter(private val generic: DisplayAdapter) : DisplayAdapter {
    private var rootView: View? = null
    private var epdControllerClass: Class<*>? = null
    private var updateModeClass: Class<*>? = null
    private var enabled = true
    private var colorDevice = false
    private var selectedPartialMode = "GU"

    override val capabilityLabel: String
        get() = if (enabled && epdControllerClass != null) {
            "BOOX${if (colorDevice) " color" else ""} $selectedPartialMode partial refresh"
        } else {
            "Generic Android refresh"
        }

    override fun attach(rootView: View) {
        generic.attach(rootView)
        this.rootView = rootView
        runVendor("attach") {
            require(rootView.isAttachedToWindow) { "BOOX root is not attached" }
            epdControllerClass = Class.forName(EPD_CONTROLLER_CLASS)
            updateModeClass = Class.forName(UPDATE_MODE_CLASS)
            colorDevice = detectColorDevice() || rootView.resources.configuration.isScreenWideColorGamut
            selectedPartialMode = if (supportsRegal()) "REGAL" else "GU"
            setDefaultMode(rootView, selectedPartialMode)
            Log.i(TAG, "Onyx attached color=$colorDevice with $selectedPartialMode partial refresh")
        }
    }

    override fun render(changes: SemanticChangeSet, regions: Map<RegionKey, View>) {
        generic.render(changes, regions)
        if (!enabled) return
        val targets = if (changes.regions.contains(RegionKey.Root)) {
            listOfNotNull(rootView)
        } else {
            changes.regions.mapNotNull(regions::get).distinct()
        }
        runVendor("partial-refresh") {
            targets.forEach { view ->
                require(view.isAttachedToWindow) { "BOOX target is not attached" }
                setDefaultMode(view, selectedPartialMode)
                invokeViewMode("invalidate", view, selectedPartialMode)
            }
            Log.i(TAG, "Onyx partial refresh mode=$selectedPartialMode regions=${targets.size}")
        }
    }

    override fun fullRefresh(reason: String) {
        generic.fullRefresh(reason)
        if (!enabled) return
        runVendor("full-refresh") {
            val root = requireNotNull(rootView) { "BOOX root missing" }
            val modeType = requireNotNull(updateModeClass)
            val repaint = methodsNamed("repaintEveryThing").firstOrNull { method ->
                method.parameterTypes.any(modeType::isAssignableFrom)
            } ?: methodsNamed("repaintEveryThing").firstOrNull()
            if (repaint != null) {
                invokeWithMode(repaint, root, "GC")
            } else {
                invokeViewMode("invalidate", root, "GC")
            }
            Log.i(TAG, "Onyx full refresh mode=GC reason=$reason")
        }
    }

    override fun detach() {
        if (enabled) {
            runVendor("detach") {
                rootView?.let { root ->
                    val reset = methodsNamed("resetViewUpdateMode").firstOrNull()
                        ?: methodsNamed("clearViewUpdateMode").firstOrNull()
                    if (reset != null) invokeWithOptionalView(reset, root)
                }
            }
        }
        rootView = null
        epdControllerClass = null
        updateModeClass = null
        colorDevice = false
        generic.detach()
    }

    private fun detectColorDevice(): Boolean = runCatching {
        val deviceClass = Class.forName(DEVICE_CLASS)
        val device = deviceClass.getMethod("currentDevice").invoke(null) ?: return@runCatching false
        val colorType = device.javaClass.methods
            .firstOrNull { method -> method.name == "getColorType" && method.parameterCount == 0 }
            ?.invoke(device) as? Int
        colorType != null && colorType > 0
    }.getOrDefault(false)

    private fun supportsRegal(): Boolean {
        val method = methodsNamed("supportRegal").firstOrNull() ?: return false
        return runCatching { method.invoke(null) as? Boolean }.getOrNull() == true
    }

    private fun setDefaultMode(view: View, modeName: String) {
        val method = methodsNamed("setViewDefaultUpdateMode").firstOrNull()
            ?: error("Onyx setViewDefaultUpdateMode unavailable")
        invokeWithMode(method, view, modeName)
    }

    private fun invokeViewMode(methodName: String, view: View, modeName: String) {
        val method = methodsNamed(methodName).firstOrNull { method -> method.parameterTypes.any(View::class.java::isAssignableFrom) }
            ?: error("Onyx $methodName unavailable")
        invokeWithMode(method, view, modeName)
    }

    private fun invokeWithMode(method: Method, view: View, modeName: String) {
        val mode = enumMode(modeName)
        val args = method.parameterTypes.map { type ->
            when {
                View::class.java.isAssignableFrom(type) -> view
                type.isInstance(mode) -> mode
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                else -> error("Unsupported Onyx parameter ${type.name}")
            }
        }.toTypedArray()
        method.invoke(if (Modifier.isStatic(method.modifiers)) null else epdControllerInstance(), *args)
    }

    private fun invokeWithOptionalView(method: Method, view: View) {
        val args = method.parameterTypes.map { type ->
            when {
                View::class.java.isAssignableFrom(type) -> view
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                else -> error("Unsupported Onyx parameter ${type.name}")
            }
        }.toTypedArray()
        method.invoke(if (Modifier.isStatic(method.modifiers)) null else epdControllerInstance(), *args)
    }

    private fun methodsNamed(name: String): List<Method> = epdControllerClass?.methods?.filter { it.name == name }.orEmpty()

    private fun enumMode(name: String): Any {
        val type = requireNotNull(updateModeClass)
        return requireNotNull(type.enumConstants).firstOrNull { (it as Enum<*>).name == name }
            ?: error("Onyx mode $name unavailable")
    }

    private fun epdControllerInstance(): Any? = runCatching {
        epdControllerClass?.getDeclaredConstructor()?.newInstance()
    }.getOrNull()

    private inline fun runVendor(operation: String, block: () -> Unit) {
        if (!enabled) return
        runCatching(block).onFailure { error ->
            enabled = false
            Log.w(TAG, "Onyx $operation failed; disabling vendor refresh", error)
        }
    }

    private companion object {
        const val TAG = "CodexBarInk"
        const val EPD_CONTROLLER_CLASS = "com.onyx.android.sdk.api.device.epd.EpdController"
        const val UPDATE_MODE_CLASS = "com.onyx.android.sdk.api.device.epd.UpdateMode"
        const val DEVICE_CLASS = "com.onyx.android.sdk.device.Device"
    }
}
