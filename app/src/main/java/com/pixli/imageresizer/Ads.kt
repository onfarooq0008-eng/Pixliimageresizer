package com.pixli.imageresizer

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.yandex.mobile.ads.nativeads.NativeAd
import com.yandex.mobile.ads.nativeads.NativeAdLoadListener
import com.yandex.mobile.ads.nativeads.NativeAdLoader
import com.yandex.mobile.ads.nativeads.NativeAdView
import com.yandex.mobile.ads.nativeads.NativeAdViewBinder

private const val TAG = "PixliAds"

/** Yandex advises against reloading immediately after a failure: retry a few times with growing delays. */
private const val MAX_FAILURES = 3
private val RETRY_DELAYS_MS = longArrayOf(30_000L, 60_000L, 120_000L)

/**
 * Owns one native ad and its loader. Lives in the ViewModel so that the loader and the ad are strongly
 * referenced for the lifetime of the screen (as the SDK docs require) and survive screen rotation.
 * Uses the application context only, so no Activity is ever leaked. All SDK calls happen on the main thread.
 */
class NativeAdSlot(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = NativeAdLoader(context.applicationContext)
    private var loading = false
    private var wanted = false
    private var failures = 0

    var ad by mutableStateOf<NativeAd?>(null)
        private set

    fun ensureLoaded() {
        wanted = true
        if (ad != null || loading || failures >= MAX_FAILURES) return
        load()
    }

    private fun load() {
        loading = true
        loader.loadAd(
            AdRequest.Builder(BuildConfig.NATIVE_ID).build(),
            object : NativeAdLoadListener {
                override fun onAdLoaded(nativeAd: NativeAd) {
                    loading = false
                    failures = 0
                    if (wanted) ad = nativeAd
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    loading = false
                    failures++
                    Log.w(TAG, "Native ad failed to load: $error (failure $failures of $MAX_FAILURES)")
                    scheduleRetry()
                }
            }
        )
    }

    private fun scheduleRetry() {
        if (!wanted || failures <= 0 || failures >= MAX_FAILURES) return
        handler.postDelayed({ if (wanted && ad == null && !loading) load() }, RETRY_DELAYS_MS[failures - 1])
    }

    /** Called when the screen using the ad is really gone (not on rotation). */
    fun discard() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        ad = null
    }

    fun onBindFailed() {
        handler.post {
            ad = null
            failures++
            scheduleRetry()
        }
    }
}

/** Native ad card. Takes no space until an ad has loaded; a no-fill or network error simply shows nothing. */
@Composable
fun NativeAdCard(slot: NativeAdSlot, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    LaunchedEffect(slot) { slot.ensureLoaded() }
    DisposableEffect(slot) {
        onDispose { if (activity?.isChangingConfigurations != true) slot.discard() }
    }

    val nativeAd = slot.ad
    if (nativeAd != null) {
        key(nativeAd) {
            AndroidView(
                modifier = modifier.fillMaxWidth(),
                factory = { c ->
                    val view = LayoutInflater.from(c).inflate(R.layout.native_ad, null) as NativeAdView
                    if (!bindNativeAd(view, nativeAd)) slot.onBindFailed()
                    view
                }
            )
        }
    }
}

/** Returns false when the SDK reports a binding failure. */
private fun bindNativeAd(view: NativeAdView, ad: NativeAd): Boolean {
    val binder = NativeAdViewBinder.Builder(view)
        .setIconView(view.findViewById<ImageView>(R.id.ad_icon))
        .setTitleView(view.findViewById<TextView>(R.id.ad_title))
        .setBodyView(view.findViewById<TextView>(R.id.ad_body))
        .setSponsoredView(view.findViewById<TextView>(R.id.ad_sponsored))
        .setWarningView(view.findViewById<TextView>(R.id.ad_warning))
        .setCallToActionView(view.findViewById<Button>(R.id.ad_cta))
        .build()
    // bindNativeAd() returns AdBindingResult.Success or AdBindingResult.Failure.
    // The result type is matched by name so this file does not depend on its package.
    val result = ad.bindNativeAd(binder)
    val failed = result.javaClass.simpleName == "Failure"
    if (failed) Log.w(TAG, "Native ad could not be bound: $result")
    return !failed
}

/**
 * Preloads one interstitial and shows it every [everyN] successful saves
 * (every save in debug builds so it is easy to test, every 3rd save in release builds).
 * Lives in the ViewModel; uses the application context for loading and the Activity only to show.
 */
class InterstitialManager(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val loader = InterstitialAdLoader(context.applicationContext)
    private var ad: InterstitialAd? = null
    private var loading = false
    private var failures = 0
    private var destroyed = false
    private val everyN: Int get() = if (BuildConfig.DEBUG) 1 else 3

    init {
        // Do not compete with the first frame: preload shortly after startup.
        handler.postDelayed({ load() }, 2_000L)
    }

    private fun load() {
        if (destroyed || loading || ad != null) return
        loading = true
        loader.loadAd(
            AdRequest.Builder(BuildConfig.INTERSTITIAL_ID).build(),
            object : InterstitialAdLoadListener {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    loading = false
                    failures = 0
                    if (destroyed) return
                    ad = interstitialAd
                }

                override fun onAdFailedToLoad(adRequestError: AdRequestError) {
                    loading = false
                    failures++
                    Log.w(TAG, "Interstitial failed to load: $adRequestError (failure $failures of $MAX_FAILURES)")
                    scheduleRetry()
                }
            }
        )
    }

    private fun scheduleRetry() {
        if (destroyed || failures <= 0 || failures >= MAX_FAILURES) return
        handler.postDelayed({ load() }, RETRY_DELAYS_MS[failures - 1])
    }

    /** Called once after the [saveCount]-th successful save. */
    fun onSaved(activity: Activity, saveCount: Int) {
        if (destroyed || saveCount % everyN != 0) return
        val current = ad
        if (current == null) {
            failures = 0      // a new save is a fresh chance to fill the ad
            load()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        ad = null             // a loaded interstitial can only be shown once
        current.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() = Unit
            override fun onAdFailedToShow(adError: AdError) {
                Log.w(TAG, "Interstitial failed to show: $adError")
                finish(current)
            }
            override fun onAdDismissed() = finish(current)
            override fun onAdClicked() = Unit
            override fun onAdImpression(impressionData: ImpressionData?) = Unit
        })
        try {
            current.show(activity)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Interstitial show() threw", e)
            finish(current)
        }
    }

    private fun finish(shown: InterstitialAd) {
        shown.setAdEventListener(null)
        load()
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        ad?.setAdEventListener(null)
        ad = null
    }
}
