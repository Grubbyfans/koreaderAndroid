package com.yuezhou.reader.reader.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import com.yuezhou.reader.reader.ReaderWebView
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Main-thread operations for an attached preview. No event listener or progress sink is installed here. */
class WebPageSnapshots(private val view: ReaderWebView) : AutoCloseable {
    private var closed = false

    data class State(val cfi: String, val chapter: Int, val text: String, val atStart: Boolean, val atEnd: Boolean)

    suspend fun committedState(): State = request(
        "Promise.resolve(window.__readerTurnCommit).then(()=>window.__reader.snapshotState())",
    )
    suspend fun state(): State = request("window.__reader.snapshotState()")
    suspend fun locate(cfi: String): State = request(
        "window.__reader.goToCfi(${JSONObject.quote(cfi)}).then(ok=>{if(!ok)throw Error('Preview navigation failed');return window.__reader.snapshotState()})",
    )
    suspend fun adjacent(direction: Int): State {
        require(direction == -1 || direction == 1)
        return request("window.__reader.commitPageTurn($direction).then(()=>window.__reader.snapshotState())")
    }

    suspend fun capture(): Bitmap = withContext(Dispatchers.Main.immediate) {
        awaitVisualState()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    suspend fun awaitVisualState(): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed && view.isAttachedToWindow && view.width > 0 && view.height > 0)
        state()
        withTimeout(5000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                })
                view.invalidate()
            }
        }
        ensureActive()
        check(!closed && view.isAttachedToWindow)
    }

    private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        check(!closed)
        view.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value ?: "null")
        }
    }

    private suspend fun request(expression: String): State = withContext(Dispatchers.Main.immediate) {
        withTimeout(8000) {
            check(!closed)
            val key = JSONObject.quote("snapshot-${UUID.randomUUID()}")
            evaluate("window.__snapshotRequests??={};window.__snapshotRequests[$key]=null;Promise.resolve().then(()=>($expression)).then(value=>{if(Object.hasOwn(window.__snapshotRequests,$key))window.__snapshotRequests[$key]={value}},error=>{if(Object.hasOwn(window.__snapshotRequests,$key))window.__snapshotRequests[$key]={error:String(error)}});true")
            try {
                while (true) {
                    check(!closed)
                    val encoded = evaluate("JSON.stringify(window.__snapshotRequests[$key]??null)")
                    val json = JSONArray("[$encoded]").optString(0, "null")
                    if (json != "null") {
                        val result = JSONObject(json)
                        check(!result.has("error")) { result.optString("error") }
                        val state = result.getJSONObject("value")
                        return@withTimeout State(state.getString("cfi"), state.getInt("index"), state.getString("text"),
                            state.optBoolean("atStart"), state.optBoolean("atEnd"))
                    }
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            } finally {
                if (!closed) view.evaluateJavascript("delete window.__snapshotRequests?.[$key]", null)
            }
        }
    }

    override fun close() { closed = true }
}
