package com.exilon.tides.data

import android.util.Log
import com.exilon.tides.data.local.TideDao
import com.exilon.tides.data.local.entity.LidarZoneEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Zone mapping entry.
 * [zhRef] is the chart-datum elevation in IGN69 metres (SHOM RAM).
 * [pipelineStage] is matched against metadata.pipeline_stage in the cached GeoJSON — a mismatch
 * forces a re-download even when the Room entity version already matches. This catches stale files
 * written during dev runs before the final pipeline output was published (the version number in
 * Room is set at download time, so a premature download can lock in the old geometry indefinitely).
 */
data class LidarZoneInfo(
    val zoneId: String,
    val assetVersion: Int,
    val assetUrl: String,
    val zhRef: Double,
    val pipelineStage: String,
)

sealed interface LidarResult {
    data class Ready(val geoJson: String, val zhRef: Double) : LidarResult
    data object Unavailable : LidarResult
}

/**
 * Downloads and caches LiDAR zone GeoJSON assets from GitHub Releases.
 *
 * Zone lookup is a compile-time map (stationId → [LidarZoneInfo]). Absence = Level-2 fallback,
 * no error. Cache is version-driven: Room entity version + file existence + pipeline_stage content
 * check must all match; any mismatch triggers a fresh download. Dedicated [okHttp] client has no
 * TideCheck key interceptor — GitHub Releases are public and must never receive the API key.
 */
class LidarRepository(
    private val okHttp: OkHttpClient,
    private val dao: TideDao,
    private val filesDir: File,
) {
    suspend fun ensureZone(stationId: String): LidarResult = withContext(Dispatchers.IO) {
        val info = ZONES[stationId] ?: return@withContext LidarResult.Unavailable

        val entity = dao.lidarZone(info.zoneId)
        val file = lidarFile(info)

        Log.d(TAG, "ensureZone(${info.zoneId}): entity.ver=${entity?.assetVersion ?: "null"} expected=${info.assetVersion} file=${file.name} exists=${file.exists()}")

        if (entity != null && entity.assetVersion == info.assetVersion && file.exists()) {
            val content = file.readText()
            val actual = extractPipelineStage(content)
            Log.d(TAG, "Cache hit: pipeline_stage actual='$actual' expected='${info.pipelineStage}'")
            if (actual == info.pipelineStage) {
                return@withContext LidarResult.Ready(content, info.zhRef)
            }
            // Content mismatch: file was written by an earlier dev run before the pipeline was
            // finalised. Delete the stale file so the download path overwrites it correctly.
            Log.w(TAG, "Stale file (stage='$actual' ≠ '${info.pipelineStage}'): deleting ${file.name} and re-downloading")
            file.delete()
        }

        try {
            Log.d(TAG, "Downloading ${info.zoneId} v${info.assetVersion}")
            val geoJson = okHttp.newCall(Request.Builder().url(info.assetUrl).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} for ${info.zoneId}")
                        return@withContext staleOrUnavailable(file, info)
                    }
                    response.body?.string() ?: return@withContext staleOrUnavailable(file, info)
                }

            Log.d(TAG, "Downloaded ${info.zoneId} v${info.assetVersion}: pipeline_stage='${extractPipelineStage(geoJson)}' bytes=${geoJson.length}")
            file.parentFile?.mkdirs()
            file.writeText(geoJson)

            dao.upsertLidarZone(
                LidarZoneEntity(
                    zoneId = info.zoneId,
                    assetVersion = info.assetVersion,
                    fetchedAtMillis = System.currentTimeMillis(),
                    filePath = file.absolutePath,
                ),
            )
            LidarResult.Ready(geoJson, info.zhRef)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            staleOrUnavailable(file, info)
        }
    }

    private fun staleOrUnavailable(file: File, info: LidarZoneInfo): LidarResult =
        if (file.exists()) LidarResult.Ready(file.readText(), info.zhRef)
        else LidarResult.Unavailable

    private fun lidarFile(info: LidarZoneInfo): File =
        File(File(filesDir, "lidar"), "${info.zoneId}_v${info.assetVersion}.geojson")

    /** Extracts metadata.pipeline_stage from a GeoJSON string without a full JSON parse. */
    private fun extractPipelineStage(geoJson: String): String? {
        val marker = "\"pipeline_stage\":"
        val idx = geoJson.indexOf(marker)
        if (idx < 0) return null
        val qStart = geoJson.indexOf('"', idx + marker.length)
        if (qStart < 0) return null
        val qEnd = geoJson.indexOf('"', qStart + 1)
        if (qEnd < 0) return null
        return geoJson.substring(qStart + 1, qEnd)
    }

    companion object {
        private const val TAG = "LidarRepo"

        // Compile-time station→zone mapping. Absence = Level-2 crossfade fallback, no error.
        // zhRef from lidar-plan.md §1: ZH_Ref = −6.2890 m NGF-IGN69 (SHOM RAM record 38).
        private val ZONES = mapOf(
            "saint_malo-410-fra-refmar" to LidarZoneInfo(
                zoneId = "stmalo",
                assetVersion = 6,
                assetUrl = "https://github.com/exilonn/lidar-saint-malo/releases/download/lidar-saint-malo-v1.5/lidar-saint-malo-v1.5.geojson",
                zhRef = -6.2890,
                pipelineStage = "1C-v1.5",
            ),
        )
    }
}
