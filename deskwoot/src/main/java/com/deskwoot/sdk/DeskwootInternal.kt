package com.deskwoot.sdk

import android.content.Context
import android.content.pm.PackageManager
import java.net.URI
import java.net.URISyntaxException

/**
 * Hooks for Deskwoot's own tests and example apps, not part of the public
 * API. The base URL is `https://deskwoot.com`; another origin (a local
 * server, a staging host) is named in the host app's manifest:
 *
 * ```xml
 * <meta-data android:name="com.deskwoot.sdk.BASE_URL" android:value="http://10.0.2.2:3000" />
 * ```
 *
 * The key is read once at [Deskwoot.setup]; unit tests set [baseUrl] directly.
 */
internal object DeskwootInternal {
    const val DEFAULT_BASE_URL: String = "https://deskwoot.com"
    const val BASE_URL_META_DATA: String = "com.deskwoot.sdk.BASE_URL"

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL

    /** The manifest's origin, or null when the key is missing or not a bare http(s) origin. */
    fun manifestBaseUrl(context: Context, logger: Logger = AndroidLogger): String? {
        val raw = try {
            // The older call works on every API level; the API 33 variant would raise the
            // compileSdk an app needs to use the library (aarMetadata.minCompileSdk).
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            info.metaData?.getString(BASE_URL_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        val origin = normalizeOrigin(raw)
        if (origin == null) logger.error("The manifest value of $BASE_URL_META_DATA is not a bare http or https origin; it is ignored.")
        return origin
    }

    /** `scheme://host[:port]` without user, path, query or fragment (a trailing slash is fine). */
    fun normalizeOrigin(raw: String): String? {
        val uri = try {
            URI(raw.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host ?: return null
        if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://${host.lowercase()}$port"
    }
}
