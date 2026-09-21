package org.crolab.chromescribe

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dashboard API + the on-disk mirror that makes summaries readable offline.
 *
 * Cloudflare Access accepts the service-token headers without a redirect and
 * hands back a CF_Authorization cookie. The jar keeps it so later requests
 * reuse the token instead of minting a fresh JWT on every call.
 */
object Api {
    private val cookies = mutableListOf<Cookie>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
                synchronized(cookies) { cookies.addAll(list) }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookies) { cookies.toList() }
        })
        .build()

    private fun builder(path: String) = Request.Builder()
        .url(BuildConfig.API + path)
        .header("CF-Access-Client-Id", BuildConfig.CF_ID)
        .header("CF-Access-Client-Secret", BuildConfig.CF_SECRET)

    private fun exec(request: Request): String =
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: ${body.take(200)}")
            body
        }

    fun get(path: String): String = exec(builder(path).build())

    fun postJson(path: String, json: String): String =
        exec(builder(path).post(json.toRequestBody("application/json".toMediaType())).build())

    /** Queue a URL shared from another app. Returns the API's data object. */
    fun queue(url: String): JSONObject {
        val body = postJson("/api/queue", JSONObject().put("url", url).toString())
        return JSONObject(body).getJSONObject("data")
    }

    // --- offline mirror -------------------------------------------------

    private fun indexFile(ctx: Context) = File(ctx.filesDir, "index.json")
    fun contentFile(ctx: Context, id: String) = File(ctx.filesDir, "$id.md")

    fun cachedIndex(ctx: Context): JSONArray =
        indexFile(ctx).takeIf { it.exists() }?.let { JSONArray(it.readText()) } ?: JSONArray()

    /**
     * Refresh the list, then download the markdown we don't have yet — newest
     * first, capped, so a first run doesn't pull the whole archive over mobile
     * data. Each subsequent open only fetches what arrived since.
     */
    fun sync(ctx: Context, limit: Int = 40): JSONArray {
        val list = JSONArray(JSONObject(get("/api/transcripts")).getJSONArray("data").toString())
        indexFile(ctx).writeText(list.toString())

        var fetched = 0
        for (i in 0 until list.length()) {
            if (fetched >= limit) break
            val t = list.getJSONObject(i)
            val id = t.optInt("id").toString()
            val file = contentFile(ctx, id)
            if (file.exists()) continue
            try {
                file.writeText(get("/api/transcript/$id/content"))
                fetched++
            } catch (e: Exception) {
                // One bad transcript shouldn't abort the whole sync.
            }
        }
        return list
    }
}
