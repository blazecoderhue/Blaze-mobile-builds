package com.blaze.store

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.blaze.store.data.Device

class BlazeStoreApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Device.isTv = getSystemService(android.app.UiModeManager::class.java)?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }

    /** Every remote image fades in instead of popping. */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context).crossfade(150).build()
}
