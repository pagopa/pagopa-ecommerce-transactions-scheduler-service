package it.pagopa.ecommerce.transactions.scheduler.utils

import java.time.Duration
import java.time.LocalTime

/** This class models a time based rate */
class TimeBasedRate(
    val from: LocalTime,
    val to: LocalTime,
    val lowRate: Int,
    val highRate: Int,
    val rampUp: Duration
) {
    // the total range duration
    val rangeDuration: Duration

    init {
        require(from != to) {
            "Invalid parameters -> from: [$from] and to: [$to]. from and to must be different!"
        }
        require(lowRate > 0 && highRate > 0) {
            "Invalid parameters -> lowRate: [$lowRate] and highRate: [$highRate]. rates must be positive!"
        }
        rangeDuration =
            if (to > from) {
                Duration.between(from, to)
            } else {
                Duration.ofHours(24) - Duration.between(to, from)
            }
        require(rampUp > Duration.ZERO && rangeDuration >= rampUp) {
            "rampUp: [$rampUp] must be positive and lte range duration: [$rangeDuration]!"
        }
    }

    fun actualRate(): Int {
        val now = LocalTime.now()
        // case where temporal window is in the same day. ex. 09:00 (d) -> 18:00 (d)
        val (rangeElapsedTime, isInRange) =
            if (to > from) {
                val isInRange = now in from..to
                val rangeElapsedTime = Duration.between(from, now)
                Pair(rangeElapsedTime, isInRange)
            }
            // case where temporal window cover different two consecutive days ex. 22:00 (d) ->
            // 05:00 (d+1)
            // in those cases time window duration and check that now is in range is done checking
            // negating above conditions
            else {
                val isInRange = now !in to..from
                val rangeElapsedTime =
                    if (now > LocalTime.MIDNIGHT) {
                        Duration.between(from, LocalTime.MAX) +
                                Duration.between(LocalTime.MIDNIGHT, now)
                    } else {
                        Duration.between(from, now)
                    }
                Pair(rangeElapsedTime, isInRange)
            }
        val finalRate: Int =
            if (isInRange) {
                if (rangeElapsedTime > rampUp) {
                    highRate
                } else {
                    lowRate + ((highRate - lowRate) * rangeElapsedTime.seconds / rampUp.seconds).toInt()
                }
            } else {
                lowRate
            }
        return finalRate
    }
}
