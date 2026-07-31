package com.mark.babylog.data

sealed interface EventDetails {
    data class BreastFeeding(val side: FeedingKind) : EventDetails {
        init {
            require(side == FeedingKind.LEFT || side == FeedingKind.RIGHT)
        }
    }

    data class Bottle(val volumeMl: Int?) : EventDetails

    data class Pumping(val side: FeedingKind, val volumeMl: Int) : EventDetails {
        init {
            require(side == FeedingKind.LEFT || side == FeedingKind.RIGHT)
            require(volumeMl > 0)
        }
    }

    data class Sleep(val position: SleepPosition) : EventDetails
}

fun parseEventDetails(type: EventType, raw: String): EventDetails? =
    when (type) {
        EventType.FEEDING ->
            when (val kind = raw.substringBefore(':').toEnumOrNull<FeedingKind>()) {
                FeedingKind.LEFT,
                FeedingKind.RIGHT -> EventDetails.BreastFeeding(kind)
                FeedingKind.BOTTLE -> {
                    val volume = raw.substringAfter(':', "").toIntOrNull()
                    if (volume == null || volume > 0) EventDetails.Bottle(volume) else null
                }
                null -> null
            }
        EventType.PUMPING -> {
            val side = raw.substringBefore(':').toEnumOrNull<FeedingKind>()
            val volume = raw.substringAfter(':', "").toIntOrNull()
            if (
                (side == FeedingKind.LEFT || side == FeedingKind.RIGHT) &&
                    volume != null &&
                    volume > 0
            ) {
                EventDetails.Pumping(side, volume)
            } else {
                null
            }
        }
        EventType.SLEEP -> raw.toEnumOrNull<SleepPosition>()?.let(EventDetails::Sleep)
    }

fun EventDetails.toStorageValue(): String =
    when (this) {
        is EventDetails.BreastFeeding -> side.name
        is EventDetails.Bottle -> volumeMl?.let { "BOTTLE:$it" } ?: "BOTTLE"
        is EventDetails.Pumping -> "${side.name}:$volumeMl"
        is EventDetails.Sleep -> position.name
    }

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }
