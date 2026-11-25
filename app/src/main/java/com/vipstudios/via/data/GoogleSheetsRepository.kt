package com.vipstudios.via.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "GoogleSheetsRepo"

data class SheetTabData(
    val title: String,
    val rows: List<List<String>>
)

data class SpreadsheetSnapshot(
    val title: String,
    val spreadsheetId: String,
    val tabs: List<SheetTabData>,
    val fetchedAtMs: Long
) {
    val totalRows: Int
        get() = tabs.sumOf { it.rows.size }

    fun formattedSyncTime(): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(fetchedAtMs))

    fun toKnowledgeText(maxChars: Int = 14000): String = buildString {
        appendLine("Spreadsheet Title: $title")
        appendLine("Fetched At: ${formattedSyncTime()}")
        if (tabs.isEmpty()) {
            appendLine("No worksheet tabs were found.")
            return@buildString
        }
        for (tab in tabs) {
            if (length >= maxChars) break
            appendLine()
            appendLine("--- Sheet Tab: ${tab.title} (${tab.rows.size} non-empty rows) ---")
            if (tab.rows.isEmpty()) {
                appendLine("(Empty tab)")
                continue
            }
            tab.rows.forEachIndexed { idx, row ->
                if (length >= maxChars) {
                    appendLine("... (additional rows truncated for prompt size)")
                    return@buildString
                }
                val formattedRow = row.joinToString(" | ") { cell ->
                    cell.replace("\n", " ").replace("\r", " ").trim()
                }
                if (idx == 0) {
                    appendLine("Row 1 (Headers/First Row): $formattedRow")
                } else {
                    appendLine("Row ${idx + 1}: $formattedRow")
                }
            }
        }
    }.trim()
}

sealed interface SheetsAuthOutcome {
    data class Authorized(val snapshot: SpreadsheetSnapshot) : SheetsAuthOutcome
    data class NeedsConsent(val intentSender: IntentSender) : SheetsAuthOutcome
    data class Failed(val userMessage: String) : SheetsAuthOutcome
}

sealed interface SheetsFetchOutcome {
    data class Success(val snapshot: SpreadsheetSnapshot) : SheetsFetchOutcome
    data object NeedsReauth : SheetsFetchOutcome
    data class Error(val userMessage: String) : SheetsFetchOutcome
}

/**
 * Strictly read-only Google Sheets repository backed by Google Identity Services AuthorizationClient
 * and HTTPS GET calls to the Google Sheets API v4.
 *
 * Security guarantees:
 * - Requests only https://www.googleapis.com/auth/spreadsheets.readonly.
 * - Performs only HTTP GET requests against the pinned spreadsheet ID.
 * - Keeps OAuth access tokens strictly in memory (never persisted to SharedPreferences, files, or logs).
 */
class GoogleSheetsRepository {

    @Volatile
    private var inMemoryToken: String? = null

    @Volatile
    private var tokenAcquiredAtMs: Long = 0L

    @Volatile
    var latestSnapshot: SpreadsheetSnapshot? = null
        private set

    suspend fun beginConnect(activity: Activity): SheetsAuthOutcome {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(READ_ONLY_SCOPE))
                .build()
            val result = Identity.getAuthorizationClient(activity).authorize(request).awaitResult()
            if (result.hasResolution()) {
                val pi = result.pendingIntent
                if (pi != null) {
                    SheetsAuthOutcome.NeedsConsent(pi.intentSender)
                } else {
                    SheetsAuthOutcome.Failed("Google authorization UI was unavailable.")
                }
            } else {
                val token = result.accessToken
                if (token.isNullOrBlank()) {
                    SheetsAuthOutcome.Failed("Google authorization did not return an access token.")
                } else {
                    cacheTokenInMemory(token)
                    when (val fetched = fetchWithRetryOnUnauthorized(activity.applicationContext, token)) {
                        is SheetsFetchOutcome.Success -> SheetsAuthOutcome.Authorized(fetched.snapshot)
                        SheetsFetchOutcome.NeedsReauth -> SheetsAuthOutcome.Failed("Authorization expired. Please try connecting again.")
                        is SheetsFetchOutcome.Error -> SheetsAuthOutcome.Failed(fetched.userMessage)
                    }
                }
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Google authorization ApiException: status=${e.statusCode}")
            SheetsAuthOutcome.Failed("Google sign-in failed (code ${e.statusCode}). Make sure Google Play Services is available.")
        } catch (e: Exception) {
            Log.w(TAG, "Google authorization failed: ${e.javaClass.simpleName}")
            SheetsAuthOutcome.Failed("Unable to authorize Google Sheets right now.")
        }
    }

    suspend fun completeConsentFromIntent(activity: Activity, data: Intent?): SheetsAuthOutcome {
        return try {
            val authResult = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)
            val token = authResult.accessToken
            if (token.isNullOrBlank()) {
                SheetsAuthOutcome.Failed("Google authorization was not granted.")
            } else {
                cacheTokenInMemory(token)
                when (val fetched = fetchWithRetryOnUnauthorized(activity.applicationContext, token)) {
                    is SheetsFetchOutcome.Success -> SheetsAuthOutcome.Authorized(fetched.snapshot)
                    SheetsFetchOutcome.NeedsReauth -> SheetsAuthOutcome.Failed("Authorization could not be verified. Please retry.")
                    is SheetsFetchOutcome.Error -> SheetsAuthOutcome.Failed(fetched.userMessage)
                }
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Consent intent ApiException: status=${e.statusCode}")
            SheetsAuthOutcome.Failed("Google Sheets sign-in was cancelled or denied.")
        } catch (e: Exception) {
            Log.w(TAG, "Consent handling failed: ${e.javaClass.simpleName}")
            SheetsAuthOutcome.Failed("Could not complete Google Sheets authorization.")
        }
    }

    suspend fun fetchLatest(context: Context): SheetsFetchOutcome {
        val appContext = context.applicationContext
        val token = obtainValidTokenSilently(appContext)
            ?: return SheetsFetchOutcome.NeedsReauth
        return fetchWithRetryOnUnauthorized(appContext, token)
    }

    suspend fun disconnect(context: Context) {
        val tokenToClear = inMemoryToken
        inMemoryToken = null
        tokenAcquiredAtMs = 0L
        latestSnapshot = null
        if (!tokenToClear.isNullOrBlank()) {
            try {
                Identity.getAuthorizationClient(context.applicationContext)
                    .clearToken(ClearTokenRequest.builder().setToken(tokenToClear).build())
                    .awaitResult()
            } catch (_: Exception) {
                // Best-effort token cache clear
            }
        }
    }

    private fun cacheTokenInMemory(token: String) {
        inMemoryToken = token
        tokenAcquiredAtMs = System.currentTimeMillis()
    }

    private suspend fun obtainValidTokenSilently(appContext: Context): String? {
        val existing = inMemoryToken
        if (!existing.isNullOrBlank() && (System.currentTimeMillis() - tokenAcquiredAtMs) < TOKEN_TTL_MS) {
            return existing
        }
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(READ_ONLY_SCOPE))
                .build()
            val result = Identity.getAuthorizationClient(appContext).authorize(request).awaitResult()
            if (result.hasResolution()) {
                inMemoryToken = null
                tokenAcquiredAtMs = 0L
                null
            } else {
                result.accessToken?.takeIf { it.isNotBlank() }?.also { cacheTokenInMemory(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent token refresh failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun fetchWithRetryOnUnauthorized(
        appContext: Context,
        initialToken: String
    ): SheetsFetchOutcome {
        val firstAttempt = readSpreadsheetHttp(initialToken)
        if (firstAttempt !is SheetsFetchOutcome.NeedsReauth) {
            return firstAttempt
        }
        // Token may have been revoked or expired early; clear Play Services cache and try once more silently.
        inMemoryToken = null
        tokenAcquiredAtMs = 0L
        try {
            Identity.getAuthorizationClient(appContext)
                .clearToken(ClearTokenRequest.builder().setToken(initialToken).build())
                .awaitResult()
        } catch (_: Exception) {
        }
        val refreshed = obtainValidTokenSilently(appContext) ?: return SheetsFetchOutcome.NeedsReauth
        return readSpreadsheetHttp(refreshed)
    }

    private suspend fun readSpreadsheetHttp(accessToken: String): SheetsFetchOutcome =
        withContext(Dispatchers.IO) {
            try {
                // Step 1: Read spreadsheet title & tab names (strictly GET, pinned spreadsheet ID)
                val metaUrl = "$SHEETS_API_BASE/$SPREADSHEET_ID?fields=properties.title,sheets.properties.title"
                val metaResp = executeGet(metaUrl, accessToken)
                when (metaResp.code) {
                    401 -> return@withContext SheetsFetchOutcome.NeedsReauth
                    403 -> return@withContext SheetsFetchOutcome.Error(
                        "Your Google account does not have read permission for this spreadsheet. Share the sheet with your Google account or reconnect with an authorized account."
                    )
                    404 -> return@withContext SheetsFetchOutcome.Error(
                        "The configured Google Spreadsheet could not be found."
                    )
                    !in 200..299 -> return@withContext SheetsFetchOutcome.Error(
                        "Google Sheets returned HTTP ${metaResp.code}."
                    )
                }

                val metaJson = JSONObject(metaResp.body)
                val docTitle = metaJson.optJSONObject("properties")?.optString("title")
                    ?.takeIf { it.isNotBlank() } ?: "Google Spreadsheet"
                val sheetsArray = metaJson.optJSONArray("sheets")
                val tabTitles = mutableListOf<String>()
                if (sheetsArray != null) {
                    for (i in 0 until sheetsArray.length().coerceAtMost(MAX_TABS)) {
                        val title = sheetsArray.optJSONObject(i)
                            ?.optJSONObject("properties")
                            ?.optString("title")
                        if (!title.isNullOrBlank()) {
                            tabTitles += title
                        }
                    }
                }

                if (tabTitles.isEmpty()) {
                    val emptySnap = SpreadsheetSnapshot(
                        title = docTitle,
                        spreadsheetId = SPREADSHEET_ID,
                        tabs = emptyList(),
                        fetchedAtMs = System.currentTimeMillis()
                    )
                    latestSnapshot = emptySnap
                    return@withContext SheetsFetchOutcome.Success(emptySnap)
                }

                // Step 2: Batch-read formatted values from all tabs (strictly GET)
                val rangeParams = tabTitles.joinToString("&") { title ->
                    val safeTitle = title.replace("'", "''")
                    val range = "'$safeTitle'!A1:Z$MAX_ROWS_PER_TAB"
                    "ranges=${URLEncoder.encode(range, "UTF-8")}"
                }
                val valuesUrl = "$SHEETS_API_BASE/$SPREADSHEET_ID/values:batchGet?majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE&$rangeParams"
                val valuesResp = executeGet(valuesUrl, accessToken)
                when (valuesResp.code) {
                    401 -> return@withContext SheetsFetchOutcome.NeedsReauth
                    403 -> return@withContext SheetsFetchOutcome.Error(
                        "Read permission denied when loading sheet values."
                    )
                    !in 200..299 -> return@withContext SheetsFetchOutcome.Error(
                        "Google Sheets values request returned HTTP ${valuesResp.code}."
                    )
                }

                val valuesJson = JSONObject(valuesResp.body)
                val valueRanges = valuesJson.optJSONArray("valueRanges")
                val tabs = mutableListOf<SheetTabData>()

                for (i in tabTitles.indices) {
                    val title = tabTitles[i]
                    val rangeObj = valueRanges?.optJSONObject(i)
                    val rowsJson = rangeObj?.optJSONArray("values")
                    val parsedRows = mutableListOf<List<String>>()
                    if (rowsJson != null) {
                        for (r in 0 until rowsJson.length()) {
                            val rowArr = rowsJson.optJSONArray(r) ?: continue
                            val cells = (0 until rowArr.length()).map { c ->
                                rowArr.optString(c, "").trim()
                            }
                            if (cells.any { it.isNotEmpty() }) {
                                parsedRows += cells
                            }
                        }
                    }
                    tabs += SheetTabData(title = title, rows = parsedRows)
                }

                val snapshot = SpreadsheetSnapshot(
                    title = docTitle,
                    spreadsheetId = SPREADSHEET_ID,
                    tabs = tabs,
                    fetchedAtMs = System.currentTimeMillis()
                )
                latestSnapshot = snapshot
                SheetsFetchOutcome.Success(snapshot)
            } catch (e: IOException) {
                Log.w(TAG, "Network error reading spreadsheet: ${e.javaClass.simpleName}")
                SheetsFetchOutcome.Error("Unable to reach Google Sheets. Check your internet connection.")
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error parsing spreadsheet: ${e.javaClass.simpleName}")
                SheetsFetchOutcome.Error("Could not read spreadsheet data.")
            }
        }

    private data class HttpResult(val code: Int, val body: String)

    private fun executeGet(urlString: String, accessToken: String): HttpResult {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            doOutput = false
            useCaches = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { ex ->
            if (cont.isActive) cont.resumeWithException(ex)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }

    companion object {
        const val SPREADSHEET_ID = "1cXGsMg9GofHQ4pUelQQL8fzqFM2G3cVanXQ67MHWuKk"
        private const val SHEETS_API_BASE = "https://sheets.googleapis.com/v4/spreadsheets"
        private const val MAX_TABS = 12
        private const val MAX_ROWS_PER_TAB = 500
        private const val TOKEN_TTL_MS = 45L * 60L * 1000L
        val READ_ONLY_SCOPE = Scope("https://www.googleapis.com/auth/spreadsheets.readonly")
    }
}
