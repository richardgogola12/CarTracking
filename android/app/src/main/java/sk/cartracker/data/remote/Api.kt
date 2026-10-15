package sk.cartracker.data.remote

import android.os.Build
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import com.google.gson.JsonObject
import retrofit2.http.Path
import sk.cartracker.data.SettingsRepository
import sk.cartracker.security.SessionStore
import java.io.IOException
import java.util.concurrent.TimeUnit

// JSON polia sú na serveri v snake_case (started_at, distance_m …) – rieši FieldNamingPolicy.
data class AuthRequest(val email: String, val password: String, val name: String? = null, val deviceName: String = Build.MODEL)
data class UserDto(val id: Int, val name: String, val email: String)
data class AuthResponse(val token: String, val user: UserDto)
data class MeResponse(val user: UserDto)
data class PointDto(
    val ts: Long, val lat: Double, val lng: Double,
    val speedKmh: Float?, val accuracyM: Float?, val bearing: Float?, val altitudeM: Double?,
    val event: String? = null,
)
data class TripDto(
    val uuid: String, val startedAt: Long, val endedAt: Long?,
    val distanceM: Double, val maxSpeedKmh: Double, val avgSpeedKmh: Double,
    val startLat: Double?, val startLng: Double?, val endLat: Double?, val endLng: Double?,
    val triggerSource: String, val points: List<PointDto>,
    val category: String = "private", val note: String = "",
    val startAddress: String? = null, val endAddress: String? = null,
    val score: Int? = null, val hardBrakes: Int = 0, val hardAccels: Int = 0, val speedingSec: Int = 0,
    val type: String = "car", val steps: Int = 0, val vehicleUuid: String? = null,
    val startPlace: String? = null, val endPlace: String? = null,
)
/** Miesto / tankovanie / pripomienka – na serveri v jednej tabuľke ako JSON. */
data class ItemDto(val kind: String, val uuid: String, val updatedAt: Long, val deleted: Boolean, val data: Any)
data class ItemSyncRequest(val items: List<ItemDto>)
data class VehicleDto(
    val uuid: String, val name: String, val plate: String, val fuelType: String,
    val consumption: Double, val fuelPrice: Double, val odometerKm: Int, val odometerDate: Long,
    val btName: String, val archived: Boolean, val updatedAt: Long,
    val limitUrban: Int = 50, val limitRural: Int = 90, val limitHighway: Int = 130,
    val ratePerKm: Double = 0.0,
    val btAddress: String = "",
)
// ---- obnova údajov po prihlásení (nový telefón / preinštalovanie) ----
// JsonElement (nie JsonObject) – server vracia pre neexistujúce nastavenia null
data class SettingsResponse(val settings: com.google.gson.JsonElement?, val updatedAt: Long)
data class BackupStatus(val trips: Int, val vehicles: Int, val sharedVehicles: Int, val items: Int, val settings: Boolean) {
    val isEmpty get() = trips == 0 && vehicles == 0 && sharedVehicles == 0 && items == 0 && !settings
}
data class SettingsBody(val settings: JsonObject, val updatedAt: Long)
data class RemoteTrip(
    val uuid: String, val startedAt: Long, val endedAt: Long?, val distanceM: Double?,
    val maxSpeedKmh: Double?, val avgSpeedKmh: Double?,
    val startLat: Double?, val startLng: Double?, val endLat: Double?, val endLng: Double?,
    val triggerSource: String?, val category: String?, val note: String?,
    val startAddress: String?, val endAddress: String?, val score: Int?,
    val hardBrakes: Int?, val hardAccels: Int?, val speedingSec: Int?,
    val type: String?, val steps: Int?, val vehicleUuid: String?, val startPlace: String?, val endPlace: String?,
)
data class RemoteTripsResponse(val trips: List<RemoteTrip>)
data class PointsRequest(val uuids: List<String>)
data class PointsResponse(val points: Map<String, List<PointDto>>)
data class RemoteItem(val kind: String, val uuid: String, val data: com.google.gson.JsonElement?, val updatedAt: Long, val deleted: Int)
data class ItemsResponse(val items: List<RemoteItem>)
/** Vozidlo zo servera – vlastné aj zdieľané. */
data class RemoteVehicle(
    val uuid: String, val name: String?, val plate: String?, val fuelType: String?,
    val consumption: Double, val fuelPrice: Double, val odometerKm: Int, val odometerDate: Long,
    val btName: String?, val archived: Boolean, val updatedAt: Long,
    val limitUrban: Int, val limitRural: Int, val limitHighway: Int, val ratePerKm: Double,
    val isOwner: Boolean, val ownerName: String?, val shareCount: Int,
    val btAddress: String? = null,
)
data class VehiclesResponse(val vehicles: List<RemoteVehicle>)
data class ShareUser(val userId: Int, val name: String, val email: String)
data class SharesResponse(val shares: List<ShareUser>)
data class ShareResponse(val share: ShareUser)
data class ShareRequest(val email: String)
/** Jazda zo spoločnej knihy jázd (všetci vodiči vozidla). */
data class SharedTrip(
    val uuid: String, val startedAt: Long, val endedAt: Long?, val distanceM: Double,
    val maxSpeedKmh: Double, val avgSpeedKmh: Double, val category: String?, val note: String?,
    val startAddress: String?, val endAddress: String?, val startPlace: String?, val endPlace: String?,
    val driverName: String?, val mine: Int,
)
data class SharedTripsResponse(val trips: List<SharedTrip>)
data class VehicleSyncRequest(val vehicles: List<VehicleDto>)
data class SyncRequest(val trips: List<TripDto>)
data class SyncResponse(val synced: List<String>)
data class OkResponse(val ok: Boolean?)
private data class ErrorResponse(val error: String?)

interface ApiService {
    @POST("register") suspend fun register(@Body body: AuthRequest): AuthResponse
    @POST("login") suspend fun login(@Body body: AuthRequest): AuthResponse
    @POST("logout") suspend fun logout(): OkResponse
    @GET("me") suspend fun me(): MeResponse
    @POST("trips/sync") suspend fun sync(@Body body: SyncRequest): SyncResponse
    @POST("items/sync") suspend fun syncItems(@Body body: ItemSyncRequest): SyncResponse
    @POST("vehicles/sync") suspend fun syncVehicles(@Body body: VehicleSyncRequest): SyncResponse
    @DELETE("trips/{uuid}") suspend fun deleteTrip(@Path("uuid") uuid: String): OkResponse
    @GET("vehicles") suspend fun vehicles(): VehiclesResponse
    @GET("settings") suspend fun settings(): SettingsResponse
    @GET("backup/status") suspend fun backupStatus(): BackupStatus
    @PUT("settings") suspend fun saveSettings(@Body body: SettingsBody): OkResponse
    @GET("trips") suspend fun trips(@Query("limit") limit: Int, @Query("offset") offset: Int): RemoteTripsResponse
    @POST("trips/points") suspend fun tripPoints(@Body body: PointsRequest): PointsResponse
    @GET("items") suspend fun items(): ItemsResponse
    @GET("vehicles/{uuid}/shares") suspend fun shares(@Path("uuid") uuid: String): SharesResponse
    @POST("vehicles/{uuid}/shares") suspend fun share(@Path("uuid") uuid: String, @Body body: ShareRequest): ShareResponse
    @DELETE("vehicles/{uuid}/shares/{user}") suspend fun unshare(@Path("uuid") uuid: String, @Path("user") user: Int): OkResponse
    @GET("vehicles/{uuid}/trips") suspend fun vehicleTrips(
        @Path("uuid") uuid: String, @retrofit2.http.Query("from") from: Long, @retrofit2.http.Query("to") to: Long,
    ): SharedTripsResponse
}

class ApiProvider(private val settings: SettingsRepository, private val session: SessionStore) {
    /** Rovnaké pomenovanie polí ako na serveri (snake_case) – aj pre JSON položiek (miesta, tankovania…). */
    val gson: Gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    @Volatile private var cached: Pair<String, ApiService>? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = session.token()
            val req = chain.request().newBuilder().header("Accept", "application/json")
            if (token != null) req.header("Authorization", "Bearer $token")
            chain.proceed(req.build())
        }
        .build()

    suspend fun get(): ApiService {
        val url = normalize(settings.current().serverUrl)
        cached?.let { if (it.first == url) return it.second }
        val service = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
        cached = url to service
        return service
    }

    /** Overí, že na adrese beží API Trasníka. Vráti null = OK, inak text chyby. */
    suspend fun testConnection(rawUrl: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = normalize(rawUrl)
        if (!url.startsWith("https://") || url.length < 12) return@withContext "Adresa musí začínať https:// (nešifrované spojenie nie je povolené)"
        try {
            val req = okhttp3.Request.Builder().url(url + "me").header("Accept", "application/json").build()
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    // Bez tokenu musí API vrátiť 401 s JSON {"error": ...}
                    if ((res.code == 401 || res.code == 200) && body.trimStart().startsWith("{")) null
                    else if (res.code == 500 && body.contains("databáz")) "Server beží, ale nevie sa pripojiť k MySQL – skontrolujte config.php"
                    else "Na adrese nebeží API Trasníka (HTTP ${res.code}). Skontrolujte cestu, napr. …/cartracking/api/"
                }
        } catch (e: IllegalArgumentException) {
            "Neplatná adresa servera"
        } catch (e: IOException) {
            "Server je nedostupný: ${e.message ?: "chyba siete"}"
        }
    }

    fun errorMessage(e: Throwable): String = when (e) {
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            runCatching { gson.fromJson(body, ErrorResponse::class.java)?.error }.getOrNull()
                ?: "Chyba servera (${e.code()})"
        }
        is java.net.UnknownServiceException -> "Server musí používať HTTPS (nešifrované spojenie nie je povolené)"
        is javax.net.ssl.SSLException -> "Chyba zabezpečeného spojenia – skontrolujte HTTPS certifikát servera"
        is IOException -> "Server je nedostupný. Skontrolujte pripojenie a adresu servera v Nastaveniach."
        is IllegalArgumentException -> "Neplatná adresa servera"
        else -> e.message ?: "Neznáma chyba"
    }

    companion object {
        fun normalize(url: String): String = url.trim().let { if (it.endsWith("/")) it else "$it/" }
    }
}
