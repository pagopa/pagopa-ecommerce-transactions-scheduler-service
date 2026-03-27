package it.pagopa.ecommerce.transactions.scheduler.utils

import it.pagopa.ecommerce.transactions.scheduler.configurations.QuerySettings
import java.time.Duration
import java.time.LocalTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** This class models a time based rate */
class TimeBasedRate(
    val from: LocalTime,
    val to: LocalTime,
    val lowRate: Int,
    val highRate: Int,
    val rampUp: Duration
) {

    companion object {
        fun fromQuerySettings(querySettings: QuerySettings): TimeBasedRate =
            TimeBasedRate(
                from = querySettings.burstStartWindow,
                to = querySettings.burstEndWindow,
                lowRate = querySettings.lowRate,
                highRate = querySettings.highRate,
                rampUp = Duration.ofSeconds(querySettings.rampUpDurationSeconds.toLong())
            )
    }

    // the total range duration
    val rangeDuration: Duration

    val logger: Logger = LoggerFactory.getLogger(javaClass)

    init {
        require(from != to) {
            "Invalid parameters -> from: [$from] and to: [$to]. from and to must be different!"
        }
        require(lowRate > 0 && highRate > 0 && lowRate <= highRate) {
            "Invalid parameters -> lowRate: [$lowRate] and highRate: [$highRate]. rates must be positive with high rate >= low rate!"
        }
        rangeDuration = Duration.between(from, to).toPositiveTimeDiff()
        require(rampUp > Duration.ZERO && rangeDuration >= rampUp) {
            "rampUp: [$rampUp] must be positive and lte range duration: [$rangeDuration]!"
        }
    }

    fun calculateRate(at: LocalTime = LocalTime.now()): Int {
        // case where temporal window is in the same day. ex. 09:00 (d) -> 18:00 (d)
        val (rangeElapsedTime, isInRange) =
            if (to > from) {
                val isInRange = at in from..to
                val rangeElapsedTime = Duration.between(from, at)
                Pair(rangeElapsedTime, isInRange)
            }
            // case where temporal window cover different two consecutive days ex. 22:00 (d) ->
            // 05:00 (d+1)
            // in those cases time window duration and check that now is in range is done checking
            // negating above conditions
            else {
                val isInRange = at !in to..from || at == to || at == from
                val rangeElapsedTime =
                    if (at > LocalTime.MIDNIGHT) {
                            Duration.between(from, LocalTime.MIDNIGHT) +
                                Duration.between(LocalTime.MIDNIGHT, at)
                        } else {
                            Duration.between(from, at)
                        }
                        .toPositiveTimeDiff()
                Pair(rangeElapsedTime, isInRange)
            }
        val finalRate: Int =
            if (isInRange) {
                if (rangeElapsedTime > rampUp) {
                    highRate
                } else {
                    lowRate +
                        ((highRate - lowRate) * rangeElapsedTime.seconds / rampUp.seconds).toInt()
                }
            } else {
                lowRate
            }
        logger.info(
            "Dynamic rate configuration: Time window: [{} - {}], ramping up: [{} -> {}]. Calculated rate: [{}]",
            from,
            to,
            lowRate,
            highRate,
            finalRate
        )
        return finalRate
    }

    fun Duration.toPositiveTimeDiff(): Duration =
        if (this.isNegative) {
            Duration.ofHours(24) + this
        } else {
            this
        }
}
