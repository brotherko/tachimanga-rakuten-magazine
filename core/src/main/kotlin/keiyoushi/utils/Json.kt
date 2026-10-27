package keiyoushi.utils

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

val jsonInstance: Json = Injekt.get()

inline fun <reified T> String.parseAs(json: Json = jsonInstance): T = json.decodeFromString(this)

inline fun <reified T> Response.parseAs(json: Json = jsonInstance): T = use { json.decodeFromStream(it.body.byteStream()) }

inline fun <reified T> JsonElement.parseAs(json: Json = jsonInstance): T = json.decodeFromJsonElement(this)

inline fun <reified T> InputStream.parseAs(json: Json = jsonInstance): T = use { json.decodeFromStream(it) }

inline fun <reified T> T.toJsonString(json: Json = jsonInstance): String = json.encodeToString(this)

inline fun <reified T> T.toJsonRequestBody(json: Json = jsonInstance): RequestBody = toJsonString(json).toRequestBody("application/json".toMediaType())
