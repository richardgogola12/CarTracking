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
)
data class TripDto(
    val uuid: String, val startedAt: Long, val endedAt: Long?,
    val distanceM: Double, val maxSpeedKmh: Double, val avgSpeedKmh: Double,
    val startLat: Double?, val startLng: Double?, val endLat: Double?, val endLng: Double?,
    val triggerSource: String, val points: List<PointDto>,
)
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
    @DELETE("trips/{uuid}") suspend fun deleteTrip(@Path("uuid") uuid: String): OkResponse
}

class ApiProvider(private val settings: SettingsRepository, private val session: SessionStore) {
    private val gson: Gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
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

    fun errorMessage(e: Throwable): String = when (e) {
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            runCatching { gson.fromJson(body, ErrorResponse::class.java)?.error }.getOrNull()
                ?: "Chyba servera (${e.code()})"
        }
        is IOException -> "Server je nedostupný. Skontrolujte pripojenie a adresu servera v Nastaveniach."
        is IllegalArgumentException -> "Neplatná adresa servera"
        else -> e.message ?: "Neznáma chyba"
    }

    companion object {
        fun normalize(url: String): String = url.trim().let { if (it.endsWith("/")) it else "$it/" }
    }
}
