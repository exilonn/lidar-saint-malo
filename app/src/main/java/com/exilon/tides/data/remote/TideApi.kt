package com.exilon.tides.data.remote

import com.exilon.tides.data.remote.dto.StationDto
import com.exilon.tides.data.remote.dto.TidesResponseDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TideCheck endpoints. The `X-API-Key` header is added by [ApiKeyInterceptor], so it is not
 * declared here. This interface is the ONLY network surface in the app.
 */
interface TideApi {

    /**
     * Stations near a coordinate. Returns a JSON **array** ordered by proximity; the repository
     * selects the nearest. Called only when the user has moved appreciably.
     */
    @GET("stations/nearest")
    suspend fun nearestStations(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
    ): List<StationDto>

    /**
     * Free-text station search (e.g. by place or station name). Returns a JSON **array** of
     * matches. Backs the "view other places" search screen; the user picks one to save.
     */
    @GET("stations/search")
    suspend fun searchStations(
        @Query("q") query: String,
    ): List<StationDto>

    /** A multi-day tide forecast for one station. One call fills the whole Room cache. */
    @GET("station/{id}/tides")
    suspend fun tides(
        @Path("id") stationId: String,
        @Query("days") days: Int = 7,
        @Query("datum") datum: String = "LAT",
    ): TidesResponseDto
}
