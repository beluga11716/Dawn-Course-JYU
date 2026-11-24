package com.dawncourse.feature.import_module

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 WebView 的慢资源回归：课表 DOM 可读取时，不必等待整页的图片完成。 */
@RunWith(AndroidJUnit4::class)
class ZhengFangDomReadinessTest {
    @Test fun metadataIsReadableBeforeSlowImageAndPageFinished() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val finished = AtomicBoolean(false)
        val releaseImage = CountDownLatch(1)
        val imageStarted = CountDownLatch(1)
        val metadata = AtomicReference<ZhengFangLoginMetadata?>()
        val script = instrumentation.targetContext.assets.open("jyu_login_metadata.js").bufferedReader().use { it.readText() }
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { finished.set(true) }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!request.url.path.orEmpty().endsWith("slow.png")) return null
                        imageStarted.countDown()
                        releaseImage.await(15, TimeUnit.SECONDS)
                        return WebResourceResponse("image/png", null, ByteArrayInputStream(byteArrayOf()))
                    }
                }
                loadDataWithBaseURL(ZhengFangWebVpn.BASE_URL + "/kbcx/xskbcx_cxXskbcxIndex.html",
                    """<html><body><select id="xnm"><option selected>2026</option></select>
                        <select id="xqm"><option selected>3</option></select>
                        <img src="${ZhengFangWebVpn.BASE_URL}/slow.png"></body></html>""", "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("slow resource was requested", imageStarted.await(10, TimeUnit.SECONDS))
            repeat(12) {
                if (metadata.get() == null) {
                    val evaluated = CountDownLatch(1)
                    instrumentation.runOnMainSync { view.evaluateJavascript(script) {
                        metadata.set(ZhengFangLoginMetadata.parse(it))
                        evaluated.countDown()
                    } }
                    evaluated.await(500, TimeUnit.MILLISECONDS)
                }
            }
            assertNotNull("term can be read while image is loading", metadata.get())
            assertFalse("no page-finished signal is needed", finished.get())
        } finally {
            releaseImage.countDown()
            instrumentation.runOnMainSync { view.stopLoading(); view.destroy() }
        }
    }
}
