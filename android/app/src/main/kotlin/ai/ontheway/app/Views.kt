package ai.ontheway.app

import ai.ontheway.core.Detour
import ai.ontheway.core.LatLon
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object Palette {
    val navy = Color.parseColor("#10285A")
    val blue = Color.parseColor("#2563EB")
    val teal = Color.parseColor("#0B9B83")
    val mint = Color.parseColor("#E3F5EF")
    val yellow = Color.parseColor("#E9A100")
    val yellowBg = Color.parseColor("#FFF5D6")
    val red = Color.parseColor("#DC3B3B")
    val redBg = Color.parseColor("#FDEBEB")
    val line = Color.parseColor("#D8E0EC")
    val muted = Color.parseColor("#4B5563")
    val faint = Color.parseColor("#9AA3B2")
}

fun minutes(d: Detour) = "+${(d.dtSec / 60).roundToInt().coerceAtLeast(0)}분"

/** E–F bar with the red (긴급) and yellow (추천) zones for the current fuel economy. */
class FuelBar(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var pct = 0.0
    private var redFrac = 0.0
    private var yellowFrac = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(pct: Double, redFrac: Double, yellowFrac: Double) {
        this.pct = pct
        this.redFrac = redFrac.coerceIn(0.0, 1.0)
        this.yellowFrac = yellowFrac.coerceIn(this.redFrac, 1.0)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val top = 14f
        val h = 22f
        fun seg(from: Double, to: Double, color: Int) {
            paint.color = color
            c.drawRoundRect(RectF((from * w).toFloat(), top, (to * w).toFloat(), top + h), 8f, 8f, paint)
        }
        seg(0.0, 1.0, Color.parseColor("#CFE7DD"))
        seg(redFrac, yellowFrac, Palette.yellow)
        seg(0.0, redFrac, Palette.red)
        val x = (pct.coerceIn(0.0, 100.0) / 100 * w).toFloat().coerceIn(4f, w - 4f)
        paint.color = Palette.navy
        c.drawRect(x - 3f, 0f, x + 3f, top + h + 10f, paint)
        paint.textSize = 20f
        paint.color = Palette.faint
        c.drawText("E", 0f, top + h + 34f, paint)
        c.drawText("F", w - 14f, top + h + 34f, paint)
    }
}

/**
 * A real map (Leaflet in a WebView, assets/map.html, OpenStreetMap tiles) with the route ahead,
 * where SafeRange ends, every candidate with the minutes it adds, and the picked stop's actual
 * way off the road from the router. Messages cover the map until there is something to draw.
 */
@SuppressLint("SetJavaScriptEnabled")
class MapView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private val web = WebView(context)
    private val msg = TextView(context).apply {
        textSize = 26f
        setTextColor(Palette.faint)
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
    }
    private var ready = false
    private val pending = ArrayList<String>()
    var onPick: (String) -> Unit = {}

    private inner class Bridge {
        @JavascriptInterface
        fun pick(id: String) {
            post { onPick(id) }
        }
    }

    init {
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "OTW")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("OnTheWay", "map: ${m.message()} (line ${m.lineNumber()})")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            // OSM tiles go through the app: an identifying User-Agent and a disk cache, as the tile policy asks
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != "tile.openstreetmap.org") return null
                val file = File(context.cacheDir, "tiles" + url.path)
                return runCatching {
                    if (!file.exists()) {
                        val bytes = (URL(url.toString()).openConnection() as HttpURLConnection).run {
                            setRequestProperty("User-Agent", USER_AGENT)
                            connectTimeout = 10_000
                            readTimeout = 15_000
                            inputStream.use { it.readBytes() }
                        }
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }
                    WebResourceResponse("image/png", null, file.inputStream())
                }.getOrNull()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                ready = true
                pending.forEach { web.evaluateJavascript(it, null) }
                pending.clear()
            }
        }
        web.loadUrl("file:///android_asset/map.html")
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(msg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun js(code: String) {
        if (ready) web.evaluateJavascript(code, null) else pending += code
    }

    fun message(text: String) {
        msg.text = text
        msg.visibility = View.VISIBLE
    }

    fun show(plan: JSONObject) {
        msg.visibility = View.GONE
        js("show($plan)")
    }

    fun car(p: LatLon) = js("car(${p.lat},${p.lon})")

    fun detour(points: List<LatLon>) = js("detour(${points.toJson()})")

    fun focus(p: LatLon) = js("focus(${p.lat},${p.lon})")
}

fun List<LatLon>.toJson() = joinToString(",", "[", "]") { "[%.6f,%.6f]".format(java.util.Locale.ROOT, it.lat, it.lon) }
