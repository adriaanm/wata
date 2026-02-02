package com.wata.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ============================================================================
// Request/Response Types
// ============================================================================

// --- Authentication ---

@Serializable
data class LoginRequest(
    val type: String = "m.login.password",
    val identifier: Identifier,
    val password: String,
    val initial_device_display_name: String? = null
)

@Serializable
data class Identifier(
    val type: String = "m.id.user",
    val user: String
)

@Serializable
data class LoginResponse(
    val user_id: String,
    val access_token: String,
    val device_id: String,
    val home_server: String? = null,
    val refresh_token: String? = null,
    val expires_in_ms: Long? = null,
    val well_known: WellKnown? = null
)

@Serializable
data class WellKnown(
    val m_homeserver: MHomeserver? = null
)

@Serializable
data class MHomeserver(
    val base_url: String
)

@Serializable
data class WhoamiResponse(
    val user_id: String,
    val device_id: String? = null,
    val is_guest: Boolean? = null
)

object LogoutRequest {
    // Empty body
}

object LogoutResponse {
    // Empty response
}

// --- Sync ---

@Serializable
data class SyncParams(
    val filter: String? = null,
    val since: String? = null,
    val full_state: Boolean? = null,
    val set_presence: String? = null, // "offline" | "online" | "unavailable"
    val timeout: Int? = null // milliseconds
)

@Serializable
data class SyncResponse(
    val next_batch: String,
    val rooms: Rooms? = null,
    val presence: Presence? = null,
    val account_data: AccountData? = null,
    val to_device: ToDevice? = null
)

@Serializable
data class Rooms(
    val join: Map<String, JoinedRoomSync>? = null,
    val invite: Map<String, InvitedRoomSync>? = null,
    val leave: Map<String, LeftRoomSync>? = null
)

@Serializable
data class RoomSummary(
    val m_heroes: List<String>? = null,
    val m_joined_member_count: Int? = null,
    val m_invited_member_count: Int? = null
)

@Serializable
data class JoinedRoomSync(
    val summary: RoomSummary? = null,
    val state: RoomState? = null,
    val state_after: RoomState? = null,
    val timeline: RoomTimeline? = null,
    val ephemeral: RoomEphemeral? = null,
    val account_data: RoomAccountData? = null,
    val unread_notifications: UnreadNotifications? = null
)

@Serializable
data class RoomState(
    val events: List<MatrixEvent>? = null
)

@Serializable
data class RoomTimeline(
    val events: List<MatrixEvent>,
    val limited: Boolean,
    val prev_batch: String
)

@Serializable
data class RoomEphemeral(
    val events: List<MatrixEvent>? = null
)

@Serializable
data class RoomAccountData(
    val events: List<MatrixEvent>? = null
)

@Serializable
data class UnreadNotifications(
    val highlight_count: Int,
    val notification_count: Int
)

@Serializable
data class InvitedRoomSync(
    val invite_state: InvitedRoomState? = null
)

@Serializable
data class InvitedRoomState(
    val events: List<StrippedStateEvent>
)

@Serializable
data class LeftRoomSync(
    val state: RoomState? = null,
    val timeline: RoomTimeline? = null
)

@Serializable
data class MatrixEvent(
    val type: String,
    val event_id: String? = null,
    val sender: String? = null,
    val origin_server_ts: Long? = null,
    val unsigned: UnsignedData? = null,
    val content: JsonObject,
    val state_key: String? = null,
    val room_id: String? = null
)

@Serializable
data class UnsignedData(
    val age: Long? = null,
    val redacted_because: MatrixEvent? = null,
    val transaction_id: String? = null
)

@Serializable
data class StrippedStateEvent(
    val type: String,
    val state_key: String,
    val content: JsonObject,
    val sender: String,
    val event_id: String? = null,
    val origin_server_ts: Long? = null
)

@Serializable
data class Presence(
    val events: List<MatrixEvent>
)

@Serializable
data class AccountData(
    val events: List<MatrixEvent>
)

@Serializable
data class ToDevice(
    val events: List<MatrixEvent>
)

// --- Rooms ---

@Serializable
data class CreateRoomRequest(
    val visibility: String? = null, // "public" | "private"
    val room_alias_name: String? = null,
    val name: String? = null,
    val topic: String? = null,
    val invite: List<String>? = null,
    val invite_3pid: List<Invite3Pid>? = null,
    val room_version: String? = null,
    val creation_content: JsonObject? = null,
    val initial_state: List<InitialStateEvent>? = null,
    val preset: String? = null, // "private_chat" | "trusted_private_chat" | "public_chat"
    val is_direct: Boolean? = null,
    val power_level_content_override: JsonObject? = null
)

@Serializable
data class Invite3Pid(
    val id_server: String,
    val id_access_token: String,
    val medium: String,
    val address: String
)

@Serializable
data class InitialStateEvent(
    val type: String,
    val state_key: String? = null,
    val content: JsonObject
)

@Serializable
data class CreateRoomResponse(
    val room_id: String
)

@Serializable
data class JoinRoomRequest(
    val third_party_signed: ThirdPartySigned? = null
)

@Serializable
data class ThirdPartySigned(
    val sender: String,
    val mxid: String,
    val token: String,
    val signatures: Map<String, Map<String, String>>
)

@Serializable
data class JoinRoomResponse(
    val room_id: String
)

@Serializable
data class InviteRequest(
    val user_id: String,
    val reason: String? = null
)

object InviteResponse {
    // Empty response
}

@Serializable
data class RoomAliasResponse(
    val room_id: String,
    val servers: List<String>
)

// --- Messages ---

@Serializable
data class SendMessageRequest(
    val msgtype: String? = null,
    val body: String? = null,
    val url: String? = null,
    val info: JsonObject? = null
)

@Serializable
data class SendMessageResponse(
    val event_id: String
)

@Serializable
data class RedactRequest(
    val reason: String? = null
)

@Serializable
data class RedactResponse(
    val event_id: String
)

@Serializable
data class ReceiptRequest(
    val thread_id: String? = null
)

object ReceiptResponse {
    // Empty response
}

// --- Media ---

@Serializable
data class UploadResponse(
    val content_uri: String // mxc:// URL
)

// Download returns raw bytes

// --- Profile & Account Data ---

@Serializable
data class ProfileResponse(
    val avatar_url: String? = null,
    val displayname: String? = null,
    val m_tz: String? = null
)

@Serializable
data class SetDisplayNameRequest(
    val displayname: String
)

object SetDisplayNameResponse {
    // Empty response
}

@Serializable
data class AccountDataResponse(
    val content: JsonObject
)

@Serializable
data class SetAccountDataRequest(
    val content: JsonObject
)

object SetAccountDataResponse {
    // Empty response
}

// ============================================================================
// Matrix API Exception
// ============================================================================

class MatrixApiException(message: String, val errorCode: String? = null) : IOException(message)

// ============================================================================
// Matrix API Client
// ============================================================================

/**
 * Typed Matrix Client-Server API implementation
 *
 * This class provides a minimal, typed HTTP client for the Matrix Client-Server API.
 * It covers only the endpoints needed by WataClient.
 *
 * Authentication:
 * - After login, the access token is stored and included in all subsequent requests
 * - All authenticated endpoints use Authorization: Bearer {token} header
 */
class MatrixApi(
    baseUrl: String,
    private val logger: Logger? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true  // Include fields with default values (including nulls)
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
    private var accessToken: String? = null
    private var txnCounter = 0

    init {
        // Normalize base URL (remove trailing slash)
        this.baseUrl = baseUrl.removeSuffix("/")
    }

    /**
     * Generate a unique transaction ID for send/redact operations
     */
    private fun generateTxnId(): String {
        return "wata-${System.currentTimeMillis()}-${txnCounter++}"
    }

    /**
     * Serialize any serializable object to JSON string
     * Uses reified type parameter to preserve type information for serialization
     * Filters out null values from the output JSON.
     */
    private inline fun <reified T : Any> toJsonBody(obj: T): String {
        return when (obj) {
            is String -> obj
            is ByteArray -> throw IllegalArgumentException("ByteArray should be handled separately")
            is kotlinx.serialization.json.JsonObject -> json.encodeToString(JsonObject.serializer(), obj)
            else -> {
                val result = json.encodeToString(obj)
                // Filter out null values like "field":null
                val filtered = result.replace(Regex(",?\"[^\"]+\":null"), "")
                logger?.log("[MatrixApi] Serialized JSON: $filtered")
                filtered
            }
        }
    }

    /**
     * Create RequestOptions with a pre-serialized JSON body
     */
    private inline fun <reified T : Any> jsonBody(body: T): RequestOptions {
        return RequestOptions(body = toJsonBody(body))
    }

    /**
     * Make an HTTP request with a pre-serialized JSON body
     */
    private inline fun <reified T : Any> request(
        method: String,
        path: String,
        options: RequestOptions = RequestOptions()
    ): T {
        // Build URL with query parameters
        val urlBuilder = this.baseUrl.toHttpUrl().newBuilder()
        val pathSegments = path.removePrefix("/").split("/")
        pathSegments.forEach { urlBuilder.addPathSegment(it) }
        options.params?.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value.toString())
        }
        val url = urlBuilder.build()

        // Build request
        val requestBuilder = Request.Builder().url(url)

        // Add auth header if required
        if (options.requireAuth != false) {
            val token = accessToken
            if (token == null) {
                throw MatrixApiException("Not authenticated - access token required")
            }
            requestBuilder.header("Authorization", "Bearer $token")
        }

        // Determine content type and body
        val contentType: String?
        val body: RequestBody?

        when (val b = options.body) {
            is ByteArray -> {
                contentType = options.contentType ?: "application/octet-stream"
                body = b.toRequestBody(contentType.toMediaType())
            }
            is kotlinx.serialization.json.JsonObject -> {
                contentType = options.contentType ?: "application/json"
                val jsonBody = json.encodeToString(JsonObject.serializer(), b)
                body = jsonBody.toRequestBody(contentType.toMediaType())
            }
            is String -> {
                contentType = options.contentType ?: "application/json"
                body = b.toRequestBody(contentType.toMediaType())
            }
            null -> {
                contentType = null
                body = null
            }
            else -> {
                // This shouldn't happen if callers use jsonBody() for data classes
                throw IllegalArgumentException("Body must be String, JsonObject, or ByteArray. Use jsonBody() helper for @Serializable data classes.")
            }
        }

        // Set content type header
        if (contentType != null) {
            requestBuilder.header("Content-Type", contentType)
        }

        // Set method and body
        if (body != null) {
            requestBuilder.method(method, body)
        } else {
            requestBuilder.method(method, null)
        }

        // Execute request
        val response = try {
            httpClient.newCall(requestBuilder.build()).execute()
        } catch (e: IOException) {
            throw MatrixApiException("Network error: ${e.message}")
        }

        // Handle errors
        if (!response.isSuccessful) {
            val errorMessage = buildString {
                append("HTTP ${response.code}: ${response.message}")
                try {
                    val errorBody = response.body?.string()
                    if (!errorBody.isNullOrBlank()) {
                        val errorJson = json.parseToJsonElement(errorBody)
                        val errorObj = errorJson.jsonObject
                        val error = errorObj["error"]?.jsonPrimitive?.content
                        val errcode = errorObj["errcode"]?.jsonPrimitive?.content
                        if (errcode != null) {
                            append(" - $errcode: $error")
                        } else if (error != null) {
                            append(" - $error")
                        }
                    }
                } catch (e: Exception) {
                    // Failed to parse error body
                }
            }
            throw MatrixApiException(errorMessage)
        }

        // Parse response
        val responseBody = response.body
        val contentTypeHeader = response.header("Content-Type")

        return when {
            responseBody == null -> Unit as T
            contentTypeHeader?.contains("application/json") == true -> {
                val responseString = responseBody.string()
                @Suppress("UNCHECKED_CAST")
                when (T::class) {
                    Unit::class -> Unit as T
                    String::class -> responseString as T
                    ByteArray::class -> responseString.toByteArray() as T
                    else -> json.decodeFromString(responseString)
                }
            }
            contentTypeHeader?.startsWith("audio/") == true ||
            contentTypeHeader?.startsWith("video/") == true ||
            contentTypeHeader?.startsWith("image/") == true ||
            contentTypeHeader == "application/octet-stream" -> {
                @Suppress("UNCHECKED_CAST")
                responseBody.bytes() as T
            }
            else -> @Suppress("UNCHECKED_CAST") Unit as T
        }
    }

    // ==========================================================================
    // Authentication
    // ==========================================================================

    /**
     * Login with username and password
     * Stores access token for subsequent requests
     */
    fun login(
        username: String,
        password: String,
        deviceDisplayName: String? = null
    ): LoginResponse {
        logger?.log("[MatrixApi] Logging in as $username")

        val loginRequest = LoginRequest(
            type = "m.login.password",
            identifier = Identifier(type = "m.id.user", user = username),
            password = password,
            initial_device_display_name = deviceDisplayName
        )

        val response = request<LoginResponse>(
            method = "POST",
            path = "/_matrix/client/v3/login",
            options = jsonBody(loginRequest).copy(requireAuth = false)
        )

        // Store access token for future requests
        accessToken = response.access_token

        logger?.log("[MatrixApi] Login successful: ${response.user_id}")
        return response
    }

    /**
     * Logout and invalidate access token
     */
    fun logout() {
        logger?.log("[MatrixApi] Logging out")

        request<Unit>(
            method = "POST",
            path = "/_matrix/client/v3/logout",
            options = RequestOptions(body = "{}")
        )

        // Clear access token
        accessToken = null
    }

    /**
     * Get information about the owner of an access token
     */
    fun whoami(): WhoamiResponse {
        return request<WhoamiResponse>(
            method = "GET",
            path = "/_matrix/client/v3/account/whoami"
        )
    }

    /**
     * Set access token manually (for resuming sessions)
     */
    fun setAccessToken(token: String) {
        this.accessToken = token
    }

    /**
     * Get current access token
     */
    fun getAccessToken(): String? = accessToken

    // ==========================================================================
    // Sync
    // ==========================================================================

    /**
     * Long-poll for events and state changes
     */
    fun sync(params: SyncParams = SyncParams()): SyncResponse {
        val queryParams = mutableMapOf<String, Any>()
        params.filter?.let { queryParams["filter"] = it }
        params.since?.let { queryParams["since"] = it }
        params.full_state?.let { queryParams["full_state"] = it }
        params.set_presence?.let { queryParams["set_presence"] = it }
        params.timeout?.let { queryParams["timeout"] = it }

        return request<SyncResponse>(
            method = "GET",
            path = "/_matrix/client/v3/sync",
            options = RequestOptions(params = queryParams)
        )
    }

    // ==========================================================================
    // Rooms
    // ==========================================================================

    /**
     * Create a new room
     */
    fun createRoom(request: CreateRoomRequest): CreateRoomResponse {
        return request<CreateRoomResponse>(
            method = "POST",
            path = "/_matrix/client/v3/createRoom",
            options = jsonBody(request)
        )
    }

    /**
     * Join a room by ID or alias
     */
    fun joinRoom(
        roomIdOrAlias: String,
        request: JoinRoomRequest = JoinRoomRequest()
    ): JoinRoomResponse {
        // For join requests with no third_party_signed, send empty JSON object {}
        // This ensures consistent serialization that Conduit accepts
        val body = if (request.third_party_signed == null) {
            "{}"
        } else {
            toJsonBody(request)
        }
        return request<JoinRoomResponse>(
            method = "POST",
            path = "/_matrix/client/v3/join/${roomIdOrAlias.urlEncode()}",
            options = RequestOptions(body = body)
        )
    }

    /**
     * Invite a user to a room
     */
    fun inviteToRoom(roomId: String, request: InviteRequest) {
        request<Unit>(
            method = "POST",
            path = "/_matrix/client/v3/rooms/${roomId.urlEncode()}/invite",
            options = jsonBody(request)
        )
    }

    /**
     * Resolve room alias to room ID
     */
    fun getRoomIdForAlias(roomAlias: String): RoomAliasResponse {
        return request<RoomAliasResponse>(
            method = "GET",
            path = "/_matrix/client/v3/directory/room/${roomAlias.urlEncode()}"
        )
    }

    // ==========================================================================
    // Messages
    // ==========================================================================

    /**
     * Send a message event to a room
     */
    fun sendMessage(
        roomId: String,
        eventType: String,
        content: JsonObject,
        txnId: String? = null
    ): SendMessageResponse {
        val txn = txnId ?: generateTxnId()
        return request<SendMessageResponse>(
            method = "PUT",
            path = "/_matrix/client/v3/rooms/${roomId.urlEncode()}/send/${eventType.urlEncode()}/${txn.urlEncode()}",
            options = RequestOptions(body = content)
        )
    }

    /**
     * Redact (delete) an event
     */
    fun redactEvent(
        roomId: String,
        eventId: String,
        reason: String? = null,
        txnId: String? = null
    ): RedactResponse {
        val txn = txnId ?: generateTxnId()
        return request<RedactResponse>(
            method = "PUT",
            path = "/_matrix/client/v3/rooms/${roomId.urlEncode()}/redact/${eventId.urlEncode()}/${txn.urlEncode()}",
            options = jsonBody(RedactRequest(reason))
        )
    }

    /**
     * Send a read receipt for an event
     */
    fun sendReadReceipt(roomId: String, eventId: String, threadId: String? = null) {
        val body = if (threadId != null) {
            """{"thread_id":"$threadId"}"""
        } else {
            "{}"
        }
        request<Unit>(
            method = "POST",
            path = "/_matrix/client/v3/rooms/${roomId.urlEncode()}/receipt/m.read/${eventId.urlEncode()}",
            options = RequestOptions(body = body)
        )
    }

    // ==========================================================================
    // Media
    // ==========================================================================

    /**
     * Upload a file to the media repository
     * @param data - File data as ByteArray
     * @param contentType - MIME type (e.g., 'audio/mp4')
     * @param filename - Optional filename
     */
    fun uploadMedia(
        data: ByteArray,
        contentType: String,
        filename: String? = null
    ): UploadResponse {
        val path = if (filename != null) {
            "/_matrix/media/v3/upload?filename=${filename.urlEncode()}"
        } else {
            "/_matrix/media/v3/upload"
        }

        return request<UploadResponse>(
            method = "POST",
            path = path,
            options = RequestOptions(
                body = data,
                contentType = contentType
            )
        )
    }

    /**
     * Download a file from the media repository
     * @param mxcUrl - MXC URL (mxc://server/mediaId)
     * @returns File data as ByteArray
     */
    fun downloadMedia(mxcUrl: String): ByteArray {
        // Parse mxc:// URL
        val match = Regex("^mxc://([^/]+)/(.+)$").find(mxcUrl)
            ?: throw IllegalArgumentException("Invalid MXC URL: $mxcUrl")

        val (_, serverName, mediaId) = match.groupValues

        return request<ByteArray>(
            method = "GET",
            path = "/_matrix/client/v1/media/download/${serverName.urlEncode()}/${mediaId.urlEncode()}"
        )
    }

    // ==========================================================================
    // Profile & Account Data
    // ==========================================================================

    /**
     * Get user profile (display name and avatar)
     */
    fun getProfile(userId: String): ProfileResponse {
        return request<ProfileResponse>(
            method = "GET",
            path = "/_matrix/client/v3/profile/${userId.urlEncode()}"
        )
    }

    /**
     * Set display name for current user
     */
    fun setDisplayName(userId: String, displayName: String) {
        val body = """{"displayname":"$displayName"}"""
        request<Unit>(
            method = "PUT",
            path = "/_matrix/client/v3/profile/${userId.urlEncode()}/displayname",
            options = RequestOptions(body = body)
        )
    }

    /**
     * Set avatar URL for current user
     */
    fun setAvatarUrl(userId: String, avatarUrl: String) {
        val body = """{"avatar_url":"$avatarUrl"}"""
        request<Unit>(
            method = "PUT",
            path = "/_matrix/client/v3/profile/${userId.urlEncode()}/avatar_url",
            options = RequestOptions(body = body)
        )
    }

    /**
     * Get account data for current user
     */
    fun getAccountData(userId: String, type: String): AccountDataResponse {
        return request<AccountDataResponse>(
            method = "GET",
            path = "/_matrix/client/v3/user/${userId.urlEncode()}/account_data/${type.urlEncode()}"
        )
    }

    /**
     * Set account data for current user
     */
    fun setAccountData(userId: String, type: String, content: JsonObject) {
        request<Unit>(
            method = "PUT",
            path = "/_matrix/client/v3/user/${userId.urlEncode()}/account_data/${type.urlEncode()}",
            options = RequestOptions(body = content)
        )
    }

    /**
     * Get room-specific account data for current user
     */
    fun getRoomAccountData(userId: String, roomId: String, type: String): AccountDataResponse {
        return request<AccountDataResponse>(
            method = "GET",
            path = "/_matrix/client/v3/user/${userId.urlEncode()}/rooms/${roomId.urlEncode()}/account_data/${type.urlEncode()}"
        )
    }

    /**
     * Set room-specific account data for current user
     */
    fun setRoomAccountData(userId: String, roomId: String, type: String, content: JsonObject) {
        request<Unit>(
            method = "PUT",
            path = "/_matrix/client/v3/user/${userId.urlEncode()}/rooms/${roomId.urlEncode()}/account_data/${type.urlEncode()}",
            options = RequestOptions(body = content)
        )
    }
}

// ============================================================================
// RequestOptions
// ============================================================================

data class RequestOptions(
    val body: Any? = null,
    val params: Map<String, Any>? = null,
    val requireAuth: Boolean? = null,
    val contentType: String? = null
)

// ============================================================================
// Extensions
// ============================================================================

private fun String.urlEncode(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
