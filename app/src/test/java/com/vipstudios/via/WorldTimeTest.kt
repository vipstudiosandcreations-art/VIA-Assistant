package com.vipstudios.via

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.Store
import com.vipstudios.via.time.TimeQueryResult
import com.vipstudios.via.time.WorldTimeEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldTimeTest {

    // Fixed reference instant in winter (DST off in US/UK) and summer (DST active in US/UK)
    private val winterInstant: Instant = Instant.parse("2026-11-25T19:15:30Z")
    private val summerInstant: Instant = Instant.parse("2026-07-15T15:00:00Z")

    @Test
    fun test1_heyViaWhatTimeInIndia() {
        val res = WorldTimeEngine.resolveQuery(
            "Hey VIA, what time is it in India?",
            use24HourDefault = false,
            instant = winterInstant
        )
        assertTrue(res is TimeQueryResult.Handled)
        val reply = (res as TimeQueryResult.Handled).reply
        // 19:15:30 UTC + 5:30 = 00:45 (next day, Thursday, 26 November 2026 in Asia/Kolkata)
        assertTrue("Expected India/Asia/Kolkata in reply: $reply", reply.contains("Asia/Kolkata"))
        assertTrue("Expected 12:45 AM in reply: $reply", reply.contains("12:45 AM"))
        assertTrue("Expected Thursday, 26 November 2026 in reply: $reply", reply.contains("Thursday, 26 November 2026"))
    }

    @Test
    fun test2_whatTimeInNewYork_handlesDstAndStandard() {
        val winter = WorldTimeEngine.resolveQuery(
            "What time is it in New York?",
            use24HourDefault = false,
            instant = winterInstant
        ) as TimeQueryResult.Handled
        // 19:15 UTC - 5h (EST) = 2:15 PM
        assertTrue("Expected 2:15 PM EST in winter: ${winter.reply}", winter.reply.contains("2:15 PM"))
        assertFalse("DST should be off in November: ${winter.reply}", winter.reply.contains("DST active"))

        val summer = WorldTimeEngine.resolveQuery(
            "What time is it in New York?",
            use24HourDefault = false,
            instant = summerInstant
        ) as TimeQueryResult.Handled
        // 15:00 UTC - 4h (EDT) = 11:00 AM
        assertTrue("Expected 11:00 AM EDT in summer: ${summer.reply}", summer.reply.contains("11:00 AM"))
        assertTrue("Expected DST active in July: ${summer.reply}", summer.reply.contains("DST active"))
    }

    @Test
    fun test3_showIndiaAndLondonTime() {
        val res = WorldTimeEngine.resolveQuery(
            "Show India and London time.",
            use24HourDefault = false,
            instant = winterInstant
        ) as TimeQueryResult.Handled
        assertTrue("Expected India in reply: ${res.reply}", res.reply.contains("India") && res.reply.contains("Asia/Kolkata"))
        assertTrue("Expected London in reply: ${res.reply}", res.reply.contains("London") && res.reply.contains("Europe/London"))
        assertTrue("Expected 7:15 PM in London: ${res.reply}", res.reply.contains("7:15 PM"))
    }

    @Test
    fun test4_whatsTodaysDate() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = CommandEngine(ctx, Store(ctx))
        val res = engine.execute("What's today's date?", "en")
        assertTrue(res is CommandResult.Handled)
        val reply = (res as CommandResult.Handled).reply
        assertTrue("Expected date phrase in reply: $reply", reply.startsWith("Today is "))
    }

    @Test
    fun test5_tellMeCurrentTime_andWithSeconds24h() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = CommandEngine(ctx, Store(ctx))
        val res = engine.execute("Tell me the current time.", "en")
        assertTrue(res is CommandResult.Handled)
        val reply = (res as CommandResult.Handled).reply
        assertTrue("Expected current time reply: $reply", reply.startsWith("It is currently "))

        val secRes = WorldTimeEngine.resolveQuery(
            "What time is it in India with seconds in 24 hour?",
            use24HourDefault = false,
            instant = winterInstant
        ) as TimeQueryResult.Handled
        assertTrue("Expected 00:45:30 in 24h with seconds: ${secRes.reply}", secRes.reply.contains("00:45:30"))
    }

    @Test
    fun unknownCityHandledGracefully_andNormalSentencesNotHijacked() {
        val unknown = WorldTimeEngine.resolveQuery("What time is it in Atlantis?", instant = winterInstant)
        assertTrue(unknown is TimeQueryResult.Handled)
        assertTrue((unknown as TimeQueryResult.Handled).reply.contains("couldn't find a time zone"))

        val nonTime = WorldTimeEngine.resolveQuery("I have a full time job and need screen time limits")
        assertTrue(nonTime is TimeQueryResult.NotATimeQuery)
    }
}
