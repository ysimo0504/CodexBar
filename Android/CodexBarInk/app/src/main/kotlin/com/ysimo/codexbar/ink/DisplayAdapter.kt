package com.ysimo.codexbar.ink

import android.util.Log
import android.view.View
import com.ysimo.codexbar.ink.core.RegionKey
import com.ysimo.codexbar.ink.core.SemanticChangeSet

interface DisplayAdapter {
    val capabilityLabel: String

    fun attach(rootView: View)

    fun render(changes: SemanticChangeSet, regions: Map<RegionKey, View>)

    fun fullRefresh(reason: String)

    fun detach()
}

class GenericDisplayAdapter : DisplayAdapter {
    private var rootView: View? = null

    override val capabilityLabel: String = "Generic Android refresh"

    override fun attach(rootView: View) {
        this.rootView = rootView
    }

    override fun render(changes: SemanticChangeSet, regions: Map<RegionKey, View>) {
        if (changes.regions.contains(RegionKey.Root)) {
            rootView?.invalidate()
            return
        }
        changes.regions.mapNotNull(regions::get).distinct().forEach(View::invalidate)
    }

    override fun fullRefresh(reason: String) {
        rootView?.invalidate()
    }

    override fun detach() {
        rootView = null
    }
}

object DisplayAdapterFactory {
    fun create(): DisplayAdapter {
        val generic = GenericDisplayAdapter()
        if (BuildConfig.DISPLAY_KIND != "boox") return generic
        return runCatching {
            val type = Class.forName("com.ysimo.codexbar.ink.OnyxDisplayAdapter")
            type.getConstructor(DisplayAdapter::class.java).newInstance(generic) as DisplayAdapter
        }.getOrElse { error ->
            Log.w(TAG, "Onyx adapter unavailable; using generic display", error)
            generic
        }
    }

    private const val TAG = "CodexBarInk"
}
