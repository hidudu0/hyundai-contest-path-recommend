package ai.ontheway.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

const val USER_AGENT = "OnTheWay/0.1 (Hyundai in-car app contest prototype)"

/** GET with an on-disk cache: the same request (same coordinates) is answered offline the next time. */
class Net(private val dir: File) {
    fun get(url: String): String {
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = File(dir, "http-$key")
        if (cached.exists()) return cached.readText()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val ok = c.responseCode in 200..299
            val body = (if (ok) c.inputStream else c.errorStream ?: throw IOException("HTTP ${c.responseCode}"))
                .bufferedReader().use { it.readText() }
            if (ok) cached.writeText(body)
            return body
        } finally {
            c.disconnect()
        }
    }
}
