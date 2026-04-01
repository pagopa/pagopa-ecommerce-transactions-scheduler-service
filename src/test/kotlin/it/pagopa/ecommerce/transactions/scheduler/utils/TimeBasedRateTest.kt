package it.pagopa.ecommerce.transactions.scheduler.utils

import java.time.Duration
import java.time.LocalTime
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class TimeBasedRateTest {

    companion object {

        @JvmStatic
        fun `Should calculate rate correctly for in same day range`(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LocalTime.of(9, 0), 100, "before range start"),
                Arguments.of(LocalTime.of(10, 0), 100, "at range start"),
                Arguments.of(LocalTime.of(10, 15), 125, "at 1/4 ramp up period"),
                Arguments.of(LocalTime.of(10, 30), 150, "at 1/2 ramp up period"),
                Arguments.of(LocalTime.of(10, 45), 175, "at 3/4 ramp up period"),
                Arguments.of(LocalTime.of(11, 0), 200, "ramp up end"),
                Arguments.of(LocalTime.of(11, 0), 200, "at range end"),
                Arguments.of(LocalTime.of(13, 0), 100, "after range end"),
            )

        @JvmStatic
        fun `Should calculate rate correctly for on two day range with rump up end at midnight`():
            Stream<Arguments> =
            Stream.of(
                Arguments.of(LocalTime.of(22, 0), 100, "before range start"),
                Arguments.of(LocalTime.of(23, 0), 100, "at range start"),
                Arguments.of(LocalTime.of(23, 15), 125, "at 1/4 ramp up period"),
                Arguments.of(LocalTime.of(23, 30), 150, "at 1/2 ramp up period"),
                Arguments.of(LocalTime.of(23, 45), 175, "at 3/4 ramp up period"),
                Arguments.of(LocalTime.of(0, 0), 200, "ramp up end"),
                Arguments.of(LocalTime.of(2, 0), 200, "at range end"),
                Arguments.of(LocalTime.of(3, 0), 100, "after range end"),
            )

        @JvmStatic
        fun `Should calculate rate correctly for on two day range with rump up end after midnight`():
            Stream<Arguments> =
            Stream.of(
                Arguments.of(LocalTime.of(22, 0), 100, "before range start"),
                Arguments.of(LocalTime.of(23, 0), 100, "at range start"),
                Arguments.of(LocalTime.of(23, 30), 125, "at 1/4 ramp up period"),
                Arguments.of(LocalTime.of(0, 0), 150, "at 1/2 ramp up period"),
                Arguments.of(LocalTime.of(0, 30), 175, "at 3/4 ramp up period"),
                Arguments.of(LocalTime.of(1, 0), 200, "ramp up end"),
                Arguments.of(LocalTime.of(2, 0), 200, "at range end"),
                Arguments.of(LocalTime.of(3, 0), 100, "after range end"),
            )

        @JvmStatic
        fun `Should fail initialization for invalid ramp up parameter`(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    Duration.ZERO,
                    "rampUp: [PT0S] must be positive and lte range duration: [PT1H]!"
                ),
                Arguments.of(
                    Duration.ofHours(2),
                    "rampUp: [PT2H] must be positive and lte range duration: [PT1H]!"
                ),
                Arguments.of(
                    Duration.ofHours(-1),
                    "rampUp: [PT-1H] must be positive and lte range duration: [PT1H]!"
                )
            )

        @JvmStatic
        fun `Should fail initialization for invalid low or high rate parameter`():
            Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    0,
                    0,
                    "Invalid parameters -> lowRate: [0] and highRate: [0]. rates must be positive with high rate >= low rate!"
                ),
                Arguments.of(
                    -1,
                    1,
                    "Invalid parameters -> lowRate: [-1] and highRate: [1]. rates must be positive with high rate >= low rate!"
                ),
                Arguments.of(
                    1,
                    -1,
                    "Invalid parameters -> lowRate: [1] and highRate: [-1]. rates must be positive with high rate >= low rate!"
                ),
                Arguments.of(
                    2,
                    1,
                    "Invalid parameters -> lowRate: [2] and highRate: [1]. rates must be positive with high rate >= low rate!"
                ),
            )
    }

    @ParameterizedTest
    @MethodSource
    fun `Should calculate rate correctly for in same day range`(
        calculateRateAt: LocalTime,
        expectedRate: Int,
        description: String
    ) {
        // pre-conditions
        val timeBaseRate =
            TimeBasedRate(
                from = LocalTime.of(10, 0),
                to = LocalTime.of(12, 0),
                lowRate = 100,
                highRate = 200,
                rampUp = Duration.ofHours(1)
            )

        // test
        val result = timeBaseRate.calculateRate(calculateRateAt.fromItalyToSystemDefault())

        // assertions
        assertEquals(expectedRate, result, description)
        assertEquals(timeBaseRate.rangeDuration, Duration.ofHours(2))
    }

    @ParameterizedTest
    @MethodSource
    fun `Should calculate rate correctly for on two day range with rump up end at midnight`(
        calculateRateAt: LocalTime,
        expectedRate: Int,
        description: String
    ) {
        // pre-conditions
        val timeBaseRate =
            TimeBasedRate(
                from = LocalTime.of(23, 0),
                to = LocalTime.of(2, 0),
                lowRate = 100,
                highRate = 200,
                rampUp = Duration.ofHours(1)
            )

        // test
        val result = timeBaseRate.calculateRate(calculateRateAt.fromItalyToSystemDefault())

        // assertions
        assertEquals(expectedRate, result, description)
        assertEquals(timeBaseRate.rangeDuration, Duration.ofHours(3))
    }

    @ParameterizedTest
    @MethodSource
    fun `Should calculate rate correctly for on two day range with rump up end after midnight`(
        calculateRateAt: LocalTime,
        expectedRate: Int,
        description: String
    ) {
        // pre-conditions
        val timeBaseRate =
            TimeBasedRate(
                from = LocalTime.of(23, 0),
                to = LocalTime.of(2, 0),
                lowRate = 100,
                highRate = 200,
                rampUp = Duration.ofHours(2)
            )

        // test
        val result = timeBaseRate.calculateRate(calculateRateAt.fromItalyToSystemDefault())

        // assertions
        assertEquals(expectedRate, result, description)
        assertEquals(timeBaseRate.rangeDuration, Duration.ofHours(3))
    }

    @Test
    fun `Should fail initialization for wrong from and to parameters`() {
        // test
        val exception =
            assertThrows<IllegalArgumentException> {
                TimeBasedRate(
                    from = LocalTime.of(23, 0),
                    to = LocalTime.of(23, 0),
                    lowRate = 100,
                    highRate = 200,
                    rampUp = Duration.ofHours(2)
                )
            }
        assertEquals(
            exception.message!!,
            "Invalid parameters -> from: [23:00] and to: [23:00]. from and to must be different!"
        )
    }

    @ParameterizedTest
    @MethodSource
    fun `Should fail initialization for invalid ramp up parameter`(
        rampUp: Duration,
        expectedErrorMessage: String
    ) {
        // test

        val exception =
            assertThrows<IllegalArgumentException> {
                TimeBasedRate(
                    from = LocalTime.of(23, 0),
                    to = LocalTime.of(0, 0),
                    lowRate = 100,
                    highRate = 200,
                    rampUp = rampUp
                )
            }
        assertEquals(exception.message!!, expectedErrorMessage)
    }

    @ParameterizedTest
    @MethodSource
    fun `Should fail initialization for invalid low or high rate parameter`(
        lowRate: Int,
        highRate: Int,
        expectedErrorMessage: String
    ) {
        // test

        val exception =
            assertThrows<IllegalArgumentException> {
                TimeBasedRate(
                    from = LocalTime.of(23, 0),
                    to = LocalTime.of(0, 0),
                    lowRate = lowRate,
                    highRate = highRate,
                    rampUp = Duration.ofMinutes(1)
                )
            }
        assertEquals(exception.message!!, expectedErrorMessage)
    }
}
