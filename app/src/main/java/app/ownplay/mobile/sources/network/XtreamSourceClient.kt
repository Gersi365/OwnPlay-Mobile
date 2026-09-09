package app.ownplay.mobile.sources.network

import app.ownplay.mobile.sources.CatalogType
import app.ownplay.mobile.sources.ProviderCategory
import app.ownplay.mobile.sources.ProviderItem
import app.ownplay.mobile.sources.SourceDescriptor
import app.ownplay.mobile.sources.SourceSnapshot
import app.ownplay.mobile.sources.XtreamCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class XtreamSourceClient(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SourceCatalogClient {
    override suspend fun fetch(
        source: SourceDescriptor,
        credentials: XtreamCredentials?,
    ): SourceSnapshot {
        requireNotNull(credentials) { "Xtream credentials are required" }
        val categories = buildList {
            addAll(fetchCategories(source, credentials, "get_live_categories", CatalogType.LIVE))
            addAll(fetchCategories(source, credentials, "get_vod_categories", CatalogType.MOVIE))
            addAll(fetchCategories(source, credentials, "get_series_categories", CatalogType.SERIES))
        }
        val items = buildList {
            addAll(fetchItems(source, credentials, "get_live_streams", CatalogType.LIVE))
            addAll(fetchItems(source, credentials, "get_vod_streams", CatalogType.MOVIE))
            addAll(fetchItems(source, credentials, "get_series", CatalogType.SERIES))
        }
        return SourceSnapshot(categories = categories, items = items)
    }

    private fun fetchCategories(
        source: SourceDescriptor,
        credentials: XtreamCredentials,
        action: String,
        type: CatalogType,
    ): List<ProviderCategory> = requestArray(source, credentials, action).mapIndexedNotNull { index, element ->
        val objectValue = element as? JsonObject ?: return@mapIndexedNotNull null
        val key = objectValue.string("category_id") ?: return@mapIndexedNotNull null
        ProviderCategory(
            catalogType = type,
            providerKey = key,
            name = objectValue.string("category_name").orEmpty().ifBlank { "Uncategorized" },
            providerOrder = index,
        )
    }

    private fun fetchItems(
        source: SourceDescriptor,
        credentials: XtreamCredentials,
        action: String,
        type: CatalogType,
    ): List<ProviderItem> = requestArray(source, credentials, action).mapIndexedNotNull { index, element ->
        val objectValue = element as? JsonObject ?: return@mapIndexedNotNull null
        val keyField = if (type == CatalogType.SERIES) "series_id" else "stream_id"
        val providerKey = objectValue.string(keyField) ?: return@mapIndexedNotNull null
        ProviderItem(
            catalogType = type,
            providerKey = providerKey,
            categoryKey = objectValue.string("category_id"),
            name = objectValue.string("name").orEmpty().ifBlank { "Untitled" },
            artworkUrl = when (type) {
                CatalogType.SERIES -> objectValue.string("cover")
                else -> objectValue.string("stream_icon")
            },
            streamLocator = null,
            containerExtension = objectValue.string("container_extension"),
            rating = objectValue.double("rating_5based") ?: objectValue.double("rating"),
            providerOrder = objectValue.int("num") ?: index,
        )
    }

    private fun requestArray(
        source: SourceDescriptor,
        credentials: XtreamCredentials,
        action: String,
    ): JsonArray {
        val endpoint = source.locator.trimEnd('/') + "/player_api.php"
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .addQueryParameter("action", action)
            .build()
        val request = Request.Builder().url(url).get().build()
        val body = httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Xtream request failed with HTTP ${response.code}" }
            response.body.string()
        }
        return json.parseToJsonElement(body).jsonArray
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() || it == "null" }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
}
