package com.stocksma

import com.stocksma.data.Parsers
import com.stocksma.domain.Sma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmaTest {

    @Test fun plainSma() {
        assertEquals(2.0, Sma.compute(listOf(1.0, 2.0, 3.0), 3)!!, 1e-9)
    }

    @Test fun usesOnlyLastWindowValues() {
        assertEquals(3.0, Sma.compute(listOf(10.0, 2.0, 3.0, 4.0), 3)!!, 1e-9)
    }

    @Test fun seededBlend() {
        // window 5, seed 10, two real closes (12, 14) -> (3*10 + 12 + 14) / 5 = 11.2
        assertEquals(11.2, Sma.compute(listOf(12.0, 14.0), 5, 10.0)!!, 1e-9)
    }

    @Test fun seedIgnoredOnceWindowIsFull() {
        assertEquals(2.0, Sma.compute(listOf(1.0, 2.0, 3.0), 3, 100.0)!!, 1e-9)
    }

    @Test fun seedAloneWhenNoDataYet() {
        assertEquals(10.0, Sma.compute(emptyList(), 5, 10.0)!!, 1e-9)
    }

    @Test fun nullWithoutSeedAndNotEnoughData() {
        assertNull(Sma.compute(listOf(1.0), 3))
    }

    @Test fun thresholdBlocksNearCrossing() {
        // 1% above SMA, threshold 2% -> no notification
        assertNull(Sma.crossingToNotify(101.0, 100.0, 2.0, null, null, 0L, 0L))
    }

    @Test fun firesBeyondThreshold() {
        assertEquals(Sma.Side.ABOVE, Sma.crossingToNotify(103.0, 100.0, 2.0, null, null, 0L, 0L))
    }

    @Test fun deduplicatesSameSide() {
        assertNull(Sma.crossingToNotify(103.0, 100.0, 2.0, Sma.Side.ABOVE, 0L, 1L, 0L))
    }

    @Test fun firesAgainAfterCrossingBack() {
        assertEquals(Sma.Side.BELOW, Sma.crossingToNotify(97.0, 100.0, 2.0, Sma.Side.ABOVE, 0L, 1L, 0L))
    }

    @Test fun cooldownRearmsSameSide() {
        val twoHours = 7_200_000L
        val oneHour = 3_600_000L
        assertEquals(Sma.Side.ABOVE, Sma.crossingToNotify(103.0, 100.0, 2.0, Sma.Side.ABOVE, 0L, twoHours, oneHour))
    }

    @Test fun zeroThresholdNotifiesOnAnyCrossing() {
        assertEquals(Sma.Side.BELOW, Sma.crossingToNotify(99.9, 100.0, 0.0, Sma.Side.ABOVE, 0L, 1L, 0L))
    }
}

class ParserTest {

    @Test fun stooqDailyCsv() {
        val csv = "Date,Open,High,Low,Close,Volume\n2024-01-02,10,11,9,10.5,1000\n2024-01-03,10.5,12,10,11.0,900"
        val bars = Parsers.stooqDaily(csv)
        assertEquals(2, bars.size)
        assertEquals(11.0, bars[1].close, 1e-9)
    }

    @Test fun customCsvWithGermanDateFormat() {
        val csv = "date,close\n02.01.2024,10.5\n03.01.2024,11.25"
        val bars = Parsers.customCsv(csv, 0, 1, true, "dd.MM.yyyy")
        assertEquals(2, bars.size)
        assertEquals(11.25, bars[1].close, 1e-9)
    }

    @Test fun customCsvSkipsUnparsableLines() {
        val csv = "date,close\nnot-a-date,xx\n2024-01-03,11.25"
        val bars = Parsers.customCsv(csv, 0, 1, true, "yyyy-MM-dd")
        assertEquals(1, bars.size)
    }

    @Test fun customJsonWithNestedArrayPath() {
        val json = """{"data":{"values":[{"d":"2024-01-03","c":11.0},{"d":"2024-01-02","c":10.0}]}}"""
        val bars = Parsers.customJson(json, "data.values", "d", "c", "yyyy-MM-dd")
        assertEquals(2, bars.size)
        // sorted ascending by date
        assertEquals(10.0, bars[0].close, 1e-9)
    }

    @Test fun customJsonRootArray() {
        val json = """[{"date":"2024-01-02","close":"10.5"}]"""
        val bars = Parsers.customJson(json, "", "date", "close", "yyyy-MM-dd")
        assertEquals(1, bars.size)
        assertEquals(10.5, bars[0].close, 1e-9)
    }
}
