package com.vipstudios.via.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class WorldCityZone(
    val id: String,
    val label: String,
    val flag: String,
    val zoneId: String,
    val aliases: List<String>
)

data class CityTimeSnapshot(
    val label: String,
    val flag: String,
    val zoneId: String,
    val formattedTime: String,
    val formattedDate: String,
    val shortZoneName: String,
    val utcOffset: String,
    val isDst: Boolean
)

sealed interface TimeQueryResult {
    data class Handled(val reply: String) : TimeQueryResult
    data object NotATimeQuery : TimeQueryResult
}

sealed interface NetworkTimeSyncResult {
    data class Synced(val utcIso: String, val offsetSeconds: Long) : NetworkTimeSyncResult
    data class Error(val message: String) : NetworkTimeSyncResult
}

/**
 * Offline-first Real-Time Clock & World Time engine backed by java.time and IANA time zones.
 * Automatically handles Daylight Saving Time (DST) transitions and never uses hardcoded times.
 */
object WorldTimeEngine {

    const val INDIA_ZONE_ID = "Asia/Kolkata"

    val featuredCities: List<WorldCityZone> = listOf(
        WorldCityZone(
            id = "india",
            label = "India",
            flag = "\uD83C\uDDEE\uD83C\uDDF3",
            zoneId = "Asia/Kolkata",
            aliases = listOf("india", "bharat", "kolkata", "calcutta", "delhi", "new delhi", "mumbai", "bombay", "bengaluru", "bangalore", "chennai", "hyderabad", "mysuru", "mysore", "pune", "ist")
        ),
        WorldCityZone(
            id = "new_york",
            label = "New York",
            flag = "\uD83C\uDDFA\uD83C\uDDF8",
            zoneId = "America/New_York",
            aliases = listOf("new york", "nyc", "manhattan", "boston", "washington", "miami", "atlanta", "eastern time", "est", "edt")
        ),
        WorldCityZone(
            id = "los_angeles",
            label = "Los Angeles",
            flag = "\uD83C\uDDFA\uD83C\uDDF8",
            zoneId = "America/Los_Angeles",
            aliases = listOf("los angeles", "san francisco", "california", "seattle", "pacific time", "pst", "pdt")
        ),
        WorldCityZone(
            id = "london",
            label = "London",
            flag = "\uD83C\uDDEC\uD83C\uDDE7",
            zoneId = "Europe/London",
            aliases = listOf("london", "uk", "u.k.", "united kingdom", "england", "britain", "great britain", "manchester", "bst")
        ),
        WorldCityZone(
            id = "dubai",
            label = "Dubai",
            flag = "\uD83C\uDDE6\uD83C\uDDEA",
            zoneId = "Asia/Dubai",
            aliases = listOf("dubai", "uae", "u.a.e.", "abu dhabi", "emirates", "sharjah")
        ),
        WorldCityZone(
            id = "tokyo",
            label = "Tokyo",
            flag = "\uD83C\uDDEF\uD83C\uDDF5",
            zoneId = "Asia/Tokyo",
            aliases = listOf("tokyo", "japan", "osaka", "kyoto", "jst")
        ),
        WorldCityZone(
            id = "singapore",
            label = "Singapore",
            flag = "\uD83C\uDDF8\uD83C\uDDEC",
            zoneId = "Asia/Singapore",
            aliases = listOf("singapore", "sg", "sgt")
        ),
        WorldCityZone(
            id = "sydney",
            label = "Sydney",
            flag = "\uD83C\uDDE6\uD83C\uDDFA",
            zoneId = "Australia/Sydney",
            aliases = listOf("sydney", "melbourne", "australia", "canberra", "aest", "aedt")
        )
    )

    private val extraCities: List<WorldCityZone> = listOf(
        WorldCityZone("chicago", "Chicago", "\uD83C\uDDFA\uD83C\uDDF8", "America/Chicago", listOf("chicago", "dallas", "houston", "texas", "central time")),
        WorldCityZone("paris", "Paris", "\uD83C\uDDEB\uD83C\uDDF7", "Europe/Paris", listOf("paris", "france")),
        WorldCityZone("berlin", "Berlin", "\uD83C\uDDE9\uD83C\uDDEA", "Europe/Berlin", listOf("berlin", "germany", "frankfurt", "munich")),
        WorldCityZone("toronto", "Toronto", "\uD83C\uDDE8\uD83C\uDDE6", "America/Toronto", listOf("toronto", "canada", "ottawa", "montreal")),
        WorldCityZone("seoul", "Seoul", "\uD83C\uDDF0\uD83C\uDDF7", "Asia/Seoul", listOf("seoul", "korea", "south korea")),
        WorldCityZone("hong_kong", "Hong Kong", "\uD83C\uDDED\uD83C\uDDF0", "Asia/Hong_Kong", listOf("hong kong")),
        WorldCityZone("riyadh", "Riyadh", "\uD83C\uDDF8\uD83C\uDDE6", "Asia/Riyadh", listOf("riyadh", "saudi", "saudi arabia")),
        WorldCityZone("doha", "Doha", "\uD83C\uDDF6\uD83C\uDDE6", "Asia/Qatar", listOf("doha", "qatar")),
        WorldCityZone("auckland", "Auckland", "\uD83C\uDDF3\uD83C\uDDFF", "Pacific/Auckland", listOf("auckland", "new zealand", "wellington"))
    )

    private val allKnownCities = featuredCities + extraCities

    fun snapshotForZone(
        zoneIdStr: String,
        label: String,
        flag: String = "",
        use24Hour: Boolean = false,
        includeSeconds: Boolean = false,
        instant: Instant = Instant.now(),
        locale: Locale = Locale.ENGLISH
    ): CityTimeSnapshot {
        val zone = ZoneId.of(zoneIdStr)
        val zdt: ZonedDateTime = instant.atZone(zone)
        val timePattern = when {
            use24Hour && includeSeconds -> "HH:mm:ss"
            use24Hour -> "HH:mm"
            includeSeconds -> "h:mm:ss a"
            else -> "h:mm a"
        }
        val timeFmt = DateTimeFormatter.ofPattern(timePattern, locale)
        val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)
        val isDst = zone.rules.isDaylightSavings(instant)
        val shortZone = zone.getDisplayName(TextStyle.SHORT, locale)
        val offsetId = zdt.offset.id.let { if (it == "Z") "UTC+00:00" else "UTC$it" }
        return CityTimeSnapshot(
            label = label,
            flag = flag,
            zoneId = zoneIdStr,
            formattedTime = zdt.format(timeFmt),
            formattedDate = zdt.format(dateFmt),
            shortZoneName = shortZone,
            utcOffset = offsetId,
            isDst = isDst
        )
    }

    fun featuredSnapshots(
        use24Hour: Boolean,
        includeSeconds: Boolean,
        instant: Instant = Instant.now()
    ): List<CityTimeSnapshot> = featuredCities.map { city ->
        snapshotForZone(
            zoneIdStr = city.zoneId,
            label = city.label,
            flag = city.flag,
            use24Hour = use24Hour,
            includeSeconds = includeSeconds,
            instant = instant
        )
    }

    fun resolveQuery(
        rawInput: String,
        use24HourDefault: Boolean = false,
        instant: Instant = Instant.now()
    ): TimeQueryResult {
        val trimmed = rawInput.trim()
            .replace(Regex("^(?:hey|hi|ok|okay|hello)[,\\s]+via\\b[,:\\s-]*", RegexOption.IGNORE_CASE), "")
            .trim()
        if (trimmed.isEmpty()) return TimeQueryResult.NotATimeQuery
        val lower = trimmed.lowercase(Locale.ROOT)

        // Check format overrides in user's query
        val includeSeconds = Regex("\\bseconds?\\b|with seconds").containsMatchIn(lower)
        val use24Hour = when {
            Regex("\\b24[-\\s]?h(?:ou)?r\\b|military time").containsMatchIn(lower) -> true
            Regex("\\b12[-\\s]?h(?:ou)?r\\b").containsMatchIn(lower) -> false
            else -> use24HourDefault
        }

        // 1. Date queries
        val isDateQuery = Regex(
            "(?:what(?:'s| is)?\\s+(?:today'?s|the|current)?\\s*date|what\\s+day\\s+is\\s+(?:it|today)|today'?s\\s+date|tell\\s+me\\s+(?:today'?s|the)\\s+date|तारीख|आज\\s+की\\s+तारीख|ದಿನಾಂಕ)"
        ).containsMatchIn(lower)

        // 2. Time / World time queries (strict enough not to hijack unrelated sentences like "full time job")
        val isExplicitTimeQuery = Regex(
            "(?:what\\s+time\\s+is\\s+it|what(?:'s| is)\\s+(?:the\\s+)?(?:current|local|exact|present)?\\s*time|" +
                "tell\\s+me\\s+(?:the\\s+)?(?:current|local|exact)?\\s*time|" +
                "(?:current|local|world)\\s+time|time\\s+now|" +
                "time\\s+in\\s+.+|show\\s+.+\\s+time\\b|" +
                "कितने\\s+बजे|समय\\s+क्या|ಸಮಯ\\s+ಎಷ್ಟು)"
        ).containsMatchIn(lower)

        if (!isExplicitTimeQuery && !isDateQuery) {
            return TimeQueryResult.NotATimeQuery
        }

        // Check if user mentioned one or more countries/cities (e.g. "Show India and London time", "What time is it in USA?")
        val matchedZones = extractMentionedZones(lower)

        if (matchedZones.isNotEmpty()) {
            val formattedList = matchedZones.map { (label, flag, zoneId) ->
                val snap = snapshotForZone(
                    zoneIdStr = zoneId,
                    label = label,
                    flag = flag,
                    use24Hour = use24Hour,
                    includeSeconds = includeSeconds,
                    instant = instant
                )
                val dstNote = if (snap.isDst) ", DST active" else ""
                val prefix = if (snap.flag.isNotEmpty()) "${snap.label} ${snap.flag}" else snap.label
                "$prefix (${snap.zoneId}): ${snap.formattedTime} on ${snap.formattedDate} (${snap.shortZoneName}, ${snap.utcOffset}$dstNote)"
            }
            val reply = if (formattedList.size == 1) {
                "In ${formattedList.first()}."
            } else {
                "Here are the current times:\n" + formattedList.joinToString("\n") { "• $it" }
            }
            return TimeQueryResult.Handled(reply)
        }

        // If user asked for time in a specific unknown place (e.g. "What time is it in Atlantis?")
        val requestedPlace = extractUnknownPlacePhrase(trimmed)
        if (requestedPlace != null) {
            return TimeQueryResult.Handled(
                "I couldn't find a time zone for \"$requestedPlace\". " +
                    "Try a country or major city such as India, New York, Los Angeles, London, Dubai, Tokyo, Singapore, or Sydney."
            )
        }

        // Date-only query (local device time zone)
        if (isDateQuery && !isExplicitTimeQuery) {
            val localZone = ZoneId.systemDefault()
            val snap = snapshotForZone(
                zoneIdStr = localZone.id,
                label = "Local",
                flag = "",
                use24Hour = use24Hour,
                includeSeconds = includeSeconds,
                instant = instant
            )
            return TimeQueryResult.Handled("Today is ${snap.formattedDate} (${snap.zoneId}).")
        }

        // Local device time query ("Hey VIA, what time is it?" / "Tell me the current time.")
        val localZone = ZoneId.systemDefault()
        val localSnap = snapshotForZone(
            zoneIdStr = localZone.id,
            label = "Local",
            flag = "",
            use24Hour = use24Hour,
            includeSeconds = includeSeconds,
            instant = instant
        )
        val dstNote = if (localSnap.isDst) ", DST active" else ""
        return TimeQueryResult.Handled(
            "It is currently ${localSnap.formattedTime} on ${localSnap.formattedDate} (${localSnap.zoneId}, ${localSnap.shortZoneName}$dstNote)."
        )
    }

    private fun extractMentionedZones(lower: String): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        val seenZoneIds = mutableSetOf<String>()

        fun addIfNew(label: String, flag: String, zoneId: String) {
            if (seenZoneIds.add(zoneId)) {
                results += Triple(label, flag, zoneId)
            }
        }

        // Special handling for generic "USA" / "United States" / "America" when not narrowed to a specific US city
        val hasUsCity = Regex("\\b(?:new york|nyc|manhattan|boston|washington|miami|atlanta|los angeles|san francisco|california|seattle|chicago|dallas|houston|texas)\\b")
            .containsMatchIn(lower)
        val mentionsUsaGeneric = Regex("\\b(?:usa|u\\.s\\.a?\\.?|united states|america)\\b").containsMatchIn(lower)

        // Order matches by appearance in query so "India and London" stays India then London
        val hits = mutableListOf<Pair<Int, WorldCityZone>>()
        for (city in allKnownCities) {
            var earliestIdx = Int.MAX_VALUE
            for (alias in city.aliases) {
                val pattern = Regex("\\b${Regex.escape(alias)}\\b")
                val match = pattern.find(lower)
                if (match != null && match.range.first < earliestIdx) {
                    earliestIdx = match.range.first
                }
            }
            if (earliestIdx != Int.MAX_VALUE) {
                hits += earliestIdx to city
            }
        }

        if (mentionsUsaGeneric && !hasUsCity) {
            val usMatch = Regex("\\b(?:usa|u\\.s\\.a?\\.?|united states|america)\\b").find(lower)
            val usIdx = usMatch?.range?.first ?: Int.MAX_VALUE
            val ny = featuredCities.first { it.id == "new_york" }.copy(label = "USA (New York)")
            val la = featuredCities.first { it.id == "los_angeles" }.copy(label = "USA (Los Angeles)")
            hits += usIdx to ny
            hits += (usIdx + 1) to la
        }

        hits.sortedBy { it.first }.forEach { (_, city) ->
            addIfNew(city.label, city.flag, city.zoneId)
        }

        if (results.isNotEmpty()) return results

        // Fallback: check full IANA ZoneId database for exact city name (e.g. "Bangkok", "Jakarta", "Zurich")
        val placeCandidate = extractUnknownPlacePhrase(lower)?.lowercase(Locale.ROOT)
        if (!placeCandidate.isNullOrBlank()) {
            val token = placeCandidate.replace(' ', '_')
            val matchedIana = ZoneId.getAvailableZoneIds().firstOrNull { id ->
                val cityPart = id.substringAfterLast('/').lowercase(Locale.ROOT)
                cityPart == token || id.lowercase(Locale.ROOT) == token
            }
            if (matchedIana != null) {
                val pretty = matchedIana.substringAfterLast('/').replace('_', ' ')
                addIfNew(pretty, "", matchedIana)
            }
        }

        return results
    }

    private fun extractUnknownPlacePhrase(raw: String): String? {
        val m1 = Regex(
            "(?:time\\s+is\\s+it\\s+in|time\\s+in|date\\s+in)\\s+([A-Za-z][A-Za-z\\s.-]{1,40}?)\\s*(?:\\?|$|\\bwith\\b|\\bin\\s+\\d)",
            RegexOption.IGNORE_CASE
        ).find(raw)
        val m2 = Regex(
            "show\\s+([A-Za-z][A-Za-z\\s.-]{1,40}?)\\s+time\\b",
            RegexOption.IGNORE_CASE
        ).find(raw)
        val candidate = (m1 ?: m2)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '?', '!')
        if (candidate.isNullOrBlank()) return null
        val ignoreWords = setOf("my", "local", "the", "current", "now", "real", "world", "12-hour", "24-hour", "12 hour", "24 hour")
        return if (candidate.lowercase(Locale.ROOT) in ignoreWords) null else candidate
    }

    /**
     * Optional on-demand network time verification against worldtimeapi.org (HTTPS GET).
     * Never required for normal operation; returns clear NetworkTimeSyncResult on failure.
     */
    suspend fun verifyNetworkTimeUtc(): NetworkTimeSyncResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://worldtimeapi.org/api/timezone/Etc/UTC").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                useCaches = false
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext NetworkTimeSyncResult.Error("Time service returned HTTP $code.")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val utcStr = json.optString("utc_datetime", "")
            val unixSec = json.optLong("unixtime", -1L)
            if (unixSec <= 0L) {
                return@withContext NetworkTimeSyncResult.Error("Time service returned an unexpected response.")
            }
            val deviceSec = Instant.now().epochSecond
            NetworkTimeSyncResult.Synced(utcIso = utcStr, offsetSeconds = unixSec - deviceSec)
        } catch (e: Exception) {
            NetworkTimeSyncResult.Error("Offline or time server unreachable (${e.javaClass.simpleName}). Using device clock.")
        } finally {
            conn?.disconnect()
        }
    }
}
