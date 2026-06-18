package com.exilon.tides.data.remote

import com.exilon.tides.data.remote.dto.OverpassResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * OpenStreetMap Overpass API — fetches intertidal/substrate polygons (beach, tidal flat, rock, …)
 * around a station so the tide map can recolour them. Absolute URL (overrides the placeholder base);
 * runs on its own client with an identifying User-Agent (no TideCheck key). The QL query goes in the
 * `data` form field. Free, no key; © OpenStreetMap contributors. Heavily cached — see
 * [com.exilon.tides.data.EstranRepository] (Overpass is a shared free resource).
 */
interface OverpassApi {

    @FormUrlEncoded
    @POST("https://overpass-api.de/api/interpreter")
    suspend fun query(@Field("data") query: String): OverpassResponse
}
