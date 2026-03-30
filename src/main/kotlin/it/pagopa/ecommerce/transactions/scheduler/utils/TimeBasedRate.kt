package it.pagopa.ecommerce.transactions.scheduler.utils

import it.pagopa.ecommerce.transactions.scheduler.configurations.QuerySettings
import java.time.Duration
import java.time.LocalTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * This class models a dynamic time based rate calculator utility. The rate will be automatically
 * calculated based on input time window configuration and current time. This class is configured by
 * the following parameters:
 * - [lowRate] & [highRate] respectively the min and max rate that will be returned by the
 * [calculateRate] method. both must be positive numbers with highRate >= lowRate
 * - [from] & [to] define the time window where the [highRate] or linear increase from the [lowRate]
 * will be returned.
 * - [rampUp] the ramping up duration. Ramping up period cannot be longer than the time window
 * defined by [from] and [to] parameters
 *
 * The rate is calculated as lowRate outside the defined window, highRate inside the time window
 * with linear ramp up strategy starting from the window [from] for the configured [rampUp] duration
 *
 * see [calculateRate] method for more details on rate calculation
 */
class TimeBasedRate(
    val from: LocalTime,
    val to: LocalTime,
    val lowRate: Int,
    val highRate: Int,
    val rampUp: Duration
) {

    companion object {
        /** Construct [TimeBasedRate] class reading parameters from [QuerySettings] object */
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

    /**
     * This is the method that calculate the rate based on the [at] parameter (defaulted to now) and
     * the [TimeBasedRate] configuration. The rate will be calculated as [lowRate] outside of the
     * time window, [highRate] inside time window with linear ramping up strategy
     *
     * Supposing to have the following configuration:
     * - from: 22:00, to: 02:00
     * - lowRate: 100, highRate: 200
     * - rampUp: 2h
     *
     * with this configuration this method will return the following values for the [at] parameter:
     * - at in [[02:00 - 22:00]] -> 100
     * - at = 23:00 -> 150
     * - at = 00:00 -> 200
     * - at in [[00:00 - 02:00]] -> 200
     * - and here the cycle repeat
     */
    fun calculateRate(at: LocalTime = LocalTime.now()): Int {
        // case where temporal window is in the same day. ex. 09:00 (d) -> 18:00 (d)
        val (rangeElapsedTime, isInBurstRange) =
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
            if (isInBurstRange) {
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
