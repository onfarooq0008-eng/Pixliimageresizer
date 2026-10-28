package com.pixli.imageresizer

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** Native ad card. Takes no space until an ad has loaded. */
@Composable
fun NativeAdCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var ad by remember { mutableStateOf<NativeAd?>(null) }
    val loader = remember { NativeAdLoader(ctx) }

    LaunchedEffect(loader) {
        loader.loadAd(
            AdRequest.Builder(BuildConfig.NATIVE_ID).build(),
            object : NativeAdLoadListener {
                override fun onAdLoaded(nativeAd: NativeAd) { ad = nativeAd }
                override fun onAdFailedToLoad(error: AdRequestError) { ad = null }
            }
        )
    }

    ad?.let { nativeAd ->
        key(nativeAd) {
            AndroidView(
                modifier = modifier.fillMaxWidth(),
                factory = { c ->
                    val view = LayoutInflater.from(c).inflate(R.layout.native_ad, null) as NativeAdView
                    bindNativeAd(view, nativeAd)
                    view
                }
            )
        }
    }
}

private fun bindNativeAd(view: NativeAdView, ad: NativeAd) {
    val binder = NativeAdViewBinder.Builder(view)
        .setIconView(view.findViewById<ImageView>(R.id.ad_icon))
        .setTitleView(view.findViewById<TextView>(R.id.ad_title))
        .setBodyView(view.findViewById<TextView>(R.id.ad_body))
        .setSponsoredView(view.findViewById<TextView>(R.id.ad_sponsored))
        .setWarningView(view.findViewById<TextView>(R.id.ad_warning))
        .setCallToActionView(view.findViewById<Button>(R.id.ad_cta))
        .build()
    try {
        ad.bindNativeAd(binder)
    } catch (e: Exception) {
        // Ad could not be bound; the card simply stays empty.
    }
}

/** Preloads one interstitial and shows it after every [EVERY_N_SAVES]th successful save. */
class InterstitialManager(context: Context) {
    private var ad: InterstitialAd? = null
    private var saves = 0

    private val loader = InterstitialAdLoader(context)

    init { load() }

    private fun load() {
        loader.loadAd(
            AdRequest.Builder(BuildConfig.INTERSTITIAL_ID).build(),
            object : InterstitialAdLoadListener {
                override fun onAdLoaded(interstitialAd: InterstitialAd) { ad = interstitialAd }
                override fun onAdFailedToLoad(adRequestError: AdRequestError) { ad = null }
            }
        )
    }

    fun onSaved(activity: Activity) {
        saves++
        if (saves % EVERY_N_SAVES != 0) return
        val current = ad ?: return
        current.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() {}
            override fun onAdFailedToShow(adError: AdError) { release() }
            override fun onAdDismissed() { release() }
            override fun onAdClicked() {}
            override fun onAdImpression(impressionData: ImpressionData?) {}
        })
        current.show(activity)
    }

    private fun release() {
        ad?.setAdEventListener(null)
        ad = null
        load()
    }

    private companion object { const val EVERY_N_SAVES = 3 }
}
