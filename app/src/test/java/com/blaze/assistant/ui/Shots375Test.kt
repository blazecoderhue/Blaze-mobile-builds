package com.blaze.assistant.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design workflow check (HQ 2026-10-22): every ShotsTest screen again at 375 dp wide (360 dp is ShotsTest itself), so
 * squished or clipped layouts show at both small-phone widths. PNGs land in build/shots/Shots375Test. BLAZE_SHOTS=1 only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w375dp-h812dp-xhdpi", application = android.app.Application::class)
class Shots375Test : ShotsTest()
