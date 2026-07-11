package com.stocksma.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class SymbolMatch(val symbol: String, val name: String)
data class DailyBar(val epochDay: Long, val close: Double)
data class Quote(val price: Double, val epochDay: Long)

/**
 * Pluggable price data source. All methods are blocking; call them from a background
 * dispatcher (the worker / viewmodel do). Implementations must be stateless.
 */
interface PriceProvider {
    val id: String
    val displayName: String
    val requiresKey: Boolean
    /** Minimum spacing between requests, used to respect free-tier rate limits. */
    val minRequestSpacingMs: Long
    val signupUrl: String? get() = null

    fun searchSymbol(query: String, apiKey: String?): List<SymbolMatch>
    fun getQuote(symbol: String, apiKey: String?): Quote
    fun getDailyHistory(symbol: String, apiKey: String?): List<DailyBar>
}

object Http {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun get(url: String, userAgent: String = "StockSMA/1.0"): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }
}

/** Stooq: no API key, daily OHLC as CSV. Symbols use suffixes, e.g. aapl.us, sap.de. */
class StooqProvider : PriceProvider {
    override val id = "stooq"
    override val displayName = "Stooq (no key)"
    override val requiresKey = false
    override val minRequestSpacingMs = 1000L

    override fun searchSymbol(query: String, apiKey: String?): List<SymbolMatch> =
        listOf(SymbolMatch(query.trim().lowercase(), "Use Stooq suffix notation, e.g. aapl.us, sap.de"))

    override fun getDailyHistory(symbol: String, apiKey: String?): List<DailyBar> =
        Parsers.stooqDaily(Http.get("https://stooq.com/q/d/l/?s=${symbol.trim().lowercase()}&i=d"))

    override fun getQuote(symbol: String, apiKey: String?): Quote =
        Parsers.stooqQuote(Http.get("https://stooq.com/q/l/?s=${symbol.trim().lowercase()}&f=sd2t2ohlcv&h&e=csv"))
}

/** Yahoo Finance chart endpoint: no key, but unofficial and may change without notice. */
class YahooProvider : PriceProvider {
    override val id = "yahoo"
    override val displayName = "Yahoo Finance (unofficial, no key)"
    override val requiresKey = false
    override val minRequestSpacingMs = 1500L

    private val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    override fun searchSymbol(query: String, apiKey: String?): List<SymbolMatch> =
        Parsers.yahooSearch(Http.get("https://query1.finance.yahoo.com/v1/finance/search?q=$query&quotesCount=10&newsCount=0", ua))

    override fun getDailyHistory(symbol: String, apiKey: String?): List<DailyBar> =
        Parsers.yahooChart(Http.get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=2y&interval=1d", ua))

    override fun getQuote(symbol: String, apiKey: String?): Quote {
        val bars = Parsers.yahooChart(Http.get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=5d&interval=1d", ua))
        val last = bars.lastOrNull() ?: throw IllegalStateException("No data for $symbol")
        return Quote(last.close, last.epochDay)
    }
}

/** Alpha Vantage: free API key, ~25 requests/day. */
class AlphaVantageProvider : PriceProvider {
    override val id = "alphavantage"
    override val displayName = "Alpha Vantage (free key, ~25 req/day)"
    override val requiresKey = true
    override val minRequestSpacingMs = 13000L
    override val signupUrl = "https://www.alphavantage.co/support/#api-key"

    private fun key(k: String?) = k?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Alpha Vantage requires an API key (see Settings)")

    override fun searchSymbol(query: String, apiKey: String?): List<SymbolMatch> =
        Parsers.alphaSearch(Http.get("https://www.alphavantage.co/query?function=SYMBOL_SEARCH&keywords=$query&apikey=${key(apiKey)}"))

    override fun getDailyHistory(symbol: String, apiKey: String?): List<DailyBar> =
        Parsers.alphaDaily(Http.get("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=$symbol&outputsize=full&apikey=${key(apiKey)}"))

    override fun getQuote(symbol: String, apiKey: String?): Quote =
        Parsers.alphaQuote(Http.get("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=$symbol&apikey=${key(apiKey)}"))
}

/** Twelve Data: free API key, ~800 requests/day. */
class TwelveDataProvider : PriceProvider {
    override val id = "twelvedata"
    override val displayName = "Twelve Data (free key, ~800 req/day)"
    override val requiresKey = true
    override val minRequestSpacingMs = 8000L
    override val signupUrl = "https://twelvedata.com/pricing"

    private fun key(k: String?) = k?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Twelve Data requires an API key (see Settings)")

    override fun searchSymbol(query: String, apiKey: String?): List<SymbolMatch> =
        Parsers.twelveSearch(Http.get("https://api.twelvedata.com/symbol_search?symbol=$query"))

    override fun getDailyHistory(symbol: String, apiKey: String?): List<DailyBar> =
        Parsers.twelveSeries(Http.get("https://api.twelvedata.com/time_series?symbol=$symbol&interval=1day&outputsize=500&apikey=${key(apiKey)}"))

    override fun getQuote(symbol: String, apiKey: String?): Quote =
        Parsers.twelveQuote(Http.get("https://api.twelvedata.com/quote?symbol=$symbol&apikey=${key(apiKey)}"))
}

/** User-defined data source configured entirely in Settings. */
data class CustomConfig(
    val name: String = "Custom",
    /** URL template for daily history; placeholders: {symbol}, {apikey}. */
    val historyUrl: String = "",
    val apiKey: String = "",
    /** "CSV" or "JSON". */
    val format: String = "CSV",
    val csvDateCol: Int = 0,
    val csvCloseCol: Int = 4,
    val csvSkipHeader: Boolean = true,
    /** Dot-separated path to the array inside the JSON response; empty = root array. */
    val jsonArrayPath: String = "",
    val jsonDateField: String = "date",
    val jsonCloseField: String = "close",
    val dateFormat: String = "yyyy-MM-dd"
)

class CustomProvider(private val cfg: CustomConfig) : PriceProvider {
    override val id = "custom"
    override val displayName = cfg.name.ifBlank { "Custom" }
    override val requiresKey = false
    override val minRequestSpacingMs = 1500L

    private fun url(symbol: String) = cfg.historyUrl
        .replace("{symbol}", symbol.trim())
        .replace("{apikey}", cfg.apiKey)

    override fun searchSymbol(query: String, apiKey: String?): List<SymbolMatch> =
        listOf(SymbolMatch(query.trim(), "Custom source symbol"))

    override fun getDailyHistory(symbol: String, apiKey: String?): List<DailyBar> {
        if (cfg.historyUrl.isBlank()) throw IllegalStateException("Custom source is not configured (see Settings)")
        val body = Http.get(url(symbol))
        return if (cfg.format.equals("JSON", ignoreCase = true))
            Parsers.customJson(body, cfg.jsonArrayPath, cfg.jsonDateField, cfg.jsonCloseField, cfg.dateFormat)
        else
            Parsers.customCsv(body, cfg.csvDateCol, cfg.csvCloseCol, cfg.csvSkipHeader, cfg.dateFormat)
    }

    override fun getQuote(symbol: String, apiKey: String?): Quote {
        val last = getDailyHistory(symbol, apiKey).lastOrNull()
            ?: throw IllegalStateException("Custom source returned no data")
        return Quote(last.close, last.epochDay)
    }
}

object Providers {
    fun builtIn(): List<PriceProvider> =
        listOf(StooqProvider(), YahooProvider(), AlphaVantageProvider(), TwelveDataProvider())

    fun byId(id: String, custom: CustomConfig): PriceProvider =
        if (id == "custom") CustomProvider(custom)
        else builtIn().firstOrNull { it.id == id } ?: StooqProvider()
}

/** Pure response parsers, unit-testable without Android. */
object Parsers {

    private fun toEpochDay(s: String, pattern: String = "yyyy-MM-dd"): Long =
        LocalDate.parse(s.trim(), DateTimeFormatter.ofPattern(pattern)).toEpochDay()

    fun stooqDaily(csv: String): List<DailyBar> =
        csv.lineSequence().drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 5) return@mapNotNull null
            val close = c[4].toDoubleOrNull() ?: return@mapNotNull null
            runCatching { DailyBar(toEpochDay(c[0]), close) }.getOrNull()
        }.toList().sortedBy { it.epochDay }

    fun stooqQuote(csv: String): Quote {
        val lines = csv.trim().lines()
        if (lines.size < 2) throw IllegalStateException("No quote data")
        val c = lines[1].split(",")
        val close = c.getOrNull(6)?.toDoubleOrNull()
            ?: throw IllegalStateException("No price returned (unknown symbol? Stooq uses suffixes like aapl.us)")
        val day = runCatching { toEpochDay(c[1]) }.getOrElse { LocalDate.now().toEpochDay() }
        return Quote(close, day)
    }

    fun yahooChart(json: String): List<DailyBar> {
        val result = JSONObject(json).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = result.getJSONArray("timestamp")
        val closes = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
        val out = ArrayList<DailyBar>()
        for (i in 0 until ts.length()) {
            if (closes.isNull(i)) continue
            out.add(DailyBar(ts.getLong(i) / 86400L, closes.getDouble(i)))
        }
        return out.sortedBy { it.epochDay }
    }

    fun yahooSearch(json: String): List<SymbolMatch> {
        val arr = JSONObject(json).optJSONArray("quotes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val q = arr.getJSONObject(i)
            val symbol = q.optString("symbol")
            if (symbol.isBlank()) null
            else SymbolMatch(symbol, q.optString("shortname", q.optString("longname", symbol)))
        }
    }

    fun alphaDaily(json: String): List<DailyBar> {
        val o = JSONObject(json)
        val ts = o.optJSONObject("Time Series (Daily)")
            ?: throw IllegalStateException(o.optString("Note", o.optString("Error Message", "No data (rate limit?)")))
        val out = ArrayList<DailyBar>()
        for (dateKey in ts.keys()) {
            val close = ts.getJSONObject(dateKey).optString("4. close").toDoubleOrNull() ?: continue
            runCatching { out.add(DailyBar(toEpochDay(dateKey), close)) }
        }
        return out.sortedBy { it.epochDay }
    }

    fun alphaQuote(json: String): Quote {
        val q = JSONObject(json).optJSONObject("Global Quote") ?: throw IllegalStateException("No quote (rate limit?)")
        val price = q.optString("05. price").toDoubleOrNull() ?: throw IllegalStateException("No price in quote")
        val day = runCatching { toEpochDay(q.optString("07. latest trading day")) }.getOrElse { LocalDate.now().toEpochDay() }
        return Quote(price, day)
    }

    fun alphaSearch(json: String): List<SymbolMatch> {
        val arr = JSONObject(json).optJSONArray("bestMatches") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            SymbolMatch(m.optString("1. symbol"), m.optString("2. name"))
        }
    }

    fun twelveSeries(json: String): List<DailyBar> {
        val o = JSONObject(json)
        val values = o.optJSONArray("values")
            ?: throw IllegalStateException(o.optString("message", "No data"))
        val out = ArrayList<DailyBar>()
        for (i in 0 until values.length()) {
            val v = values.getJSONObject(i)
            val close = v.optString("close").toDoubleOrNull() ?: continue
            runCatching { out.add(DailyBar(toEpochDay(v.optString("datetime")), close)) }
        }
        return out.sortedBy { it.epochDay }
    }

    fun twelveQuote(json: String): Quote {
        val o = JSONObject(json)
        val price = o.optString("close").toDoubleOrNull()
            ?: throw IllegalStateException(o.optString("message", "No price"))
        val day = runCatching { toEpochDay(o.optString("datetime")) }.getOrElse { LocalDate.now().toEpochDay() }
        return Quote(price, day)
    }

    fun twelveSearch(json: String): List<SymbolMatch> {
        val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            SymbolMatch(m.optString("symbol"), m.optString("instrument_name"))
        }
    }

    fun customCsv(text: String, dateCol: Int, closeCol: Int, skipHeader: Boolean, dateFormat: String): List<DailyBar> =
        text.lineSequence().drop(if (skipHeader) 1 else 0).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val c = line.split(",", ";").map { it.trim().removeSurrounding("\"") }
            val close = c.getOrNull(closeCol)?.toDoubleOrNull() ?: return@mapNotNull null
            val dateStr = c.getOrNull(dateCol) ?: return@mapNotNull null
            runCatching { DailyBar(toEpochDay(dateStr, dateFormat), close) }.getOrNull()
        }.toList().sortedBy { it.epochDay }

    fun customJson(text: String, arrayPath: String, dateField: String, closeField: String, dateFormat: String): List<DailyBar> {
        val arr: JSONArray = if (arrayPath.isBlank()) {
            JSONArray(text)
        } else {
            var node = JSONObject(text)
            val parts = arrayPath.split(".")
            for (i in 0 until parts.size - 1) node = node.getJSONObject(parts[i])
            node.getJSONArray(parts.last())
        }
        val out = ArrayList<DailyBar>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val close = o.opt(closeField)?.toString()?.toDoubleOrNull() ?: continue
            val day = runCatching { toEpochDay(o.getString(dateField), dateFormat) }.getOrNull() ?: continue
            out.add(DailyBar(day, close))
        }
        return out.sortedBy { it.epochDay }
    }
}
