package app.ownplay.mobile.sources.data

data class ProviderResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: String,
) {
    override fun toString(): String =
        "ProviderResponse(statusCode=$statusCode, contentType=$contentType, body=<redacted>)"
}

interface ProviderTransport {
    suspend fun get(url: String): ProviderResponse
}

class ProviderTransportException(
    val category: ProviderTransportFailureCategory,
    cause: Throwable? = null,
) : Exception(category.name, cause)

enum class ProviderTransportFailureCategory {
    INVALID_REQUEST,
    NETWORK,
    RESPONSE_TOO_LARGE,
    HTTP_ERROR,
}
