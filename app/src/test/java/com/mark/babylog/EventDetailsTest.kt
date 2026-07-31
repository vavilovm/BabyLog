package com.mark.babylog

import com.mark.babylog.data.EventDetails
import com.mark.babylog.data.EventType
import com.mark.babylog.data.FeedingKind
import com.mark.babylog.data.SleepPosition
import com.mark.babylog.data.parseEventDetails
import com.mark.babylog.data.toStorageValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDetailsTest {
    @Test
    fun parsesAllCurrentEventShapes() {
        assertEquals(
            EventDetails.BreastFeeding(FeedingKind.LEFT),
            parseEventDetails(EventType.FEEDING, "LEFT"),
        )
        assertEquals(EventDetails.Bottle(120), parseEventDetails(EventType.FEEDING, "BOTTLE:120"))
        assertEquals(
            EventDetails.Pumping(FeedingKind.RIGHT, 90),
            parseEventDetails(EventType.PUMPING, "RIGHT:90"),
        )
        assertEquals(
            EventDetails.Sleep(SleepPosition.LEFT),
            parseEventDetails(EventType.SLEEP, "LEFT"),
        )
    }

    @Test
    fun rejectsMalformedRemoteDetails() {
        assertNull(parseEventDetails(EventType.PUMPING, "LEFT"))
        assertNull(parseEventDetails(EventType.PUMPING, "BOTTLE:90"))
        assertNull(parseEventDetails(EventType.FEEDING, "BOTTLE:0"))
        assertNull(parseEventDetails(EventType.SLEEP, "SIDEWAYS"))
    }

    @Test
    fun typedValuesRoundTripToLegacyStorage() {
        assertEquals("RIGHT:75", EventDetails.Pumping(FeedingKind.RIGHT, 75).toStorageValue())
    }
}
