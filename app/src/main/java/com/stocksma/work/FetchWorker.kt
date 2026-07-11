package com.stocksma.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stocksma.MainActivity
import com.stocksma.R
import com.stocksma.data.AppDb
import com.stocksma.data.Instrument
import com.stocksma.data.PricePoint
import com.stocksma.data.Providers
import com.stocksma.data.SettingsRepo
import com.stocksma.domain.Sma
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Periodic background job: fetches the current price for every tracked instrument,
 * stores it, recomputes the SMA and fires crossing notifications.
 * Requests are spaced out per provider to respect free-tier rate limits.
 */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = AppDb.get(applicationContext).dao()
        val settingsRepo = SettingsRepo(applicationContext)
        val settings = settingsRepo.settings.first()
        val provider = Providers.byId(settings.providerId, settings.custom)
        val apiKey = settingsRepo.apiKey(provider.id)
        val instruments = dao.instrumentsOnce()

        for ((index, inst) in instruments.withIndex()) {
            try {
                val quote = provider.getQuote(inst.symbol, apiKey)
                dao.upsertPrices(listOf(PricePoint(instrumentId = inst.id, epochDay = quote.epochDay, close = quote.price)))

                val prices = dao.pricesOnce(inst.id)
                val closes = closesForSma(prices, inst)
                val sma = Sma.compute(closes, inst.smaWindow, inst.seedValue)

                var updated = inst.copy(lastUpdatedAt = System.currentTimeMillis(), lastFetchError = null)
                if (sma != null) {
                    val side = Sma.crossingToNotify(
                        price = quote.price,
                        sma = sma,
                        thresholdPct = inst.thresholdPct,
                        lastNotifiedSide = inst.lastNotifiedSide?.let { runCatching { Sma.Side.valueOf(it) }.getOrNull() },
                        lastNotifiedAtMillis = inst.lastNotifiedAt,
                        nowMillis = System.currentTimeMillis(),
                        cooldownMillis = settings.cooldownHours * 3_600_000L
                    )
                    if (side != null) {
                        notifyCrossing(applicationContext, inst, quote.price, sma, side)
                        updated = updated.copy(lastNotifiedSide = side.name, lastNotifiedAt = System.currentTimeMillis())
                    } else if (inst.lastNotifiedSide != null && Sma.side(quote.price, sma).name != inst.lastNotifiedSide) {
                        // Price crossed back over the SMA: re-arm for the next crossing.
                        updated = updated.copy(lastNotifiedSide = null)
                    }
                }
                dao.update(updated)
            } catch (e: Exception) {
                dao.update(inst.copy(lastFetchError = e.message ?: "Fetch failed"))
            }
            if (index < instruments.size - 1) delay(provider.minRequestSpacingMs)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "sma_alerts"
        private const val WORK_NAME = "price-fetch"

        /** Closes usable for the SMA: when a seed exists, only closes since the seed date count. */
        fun closesForSma(prices: List<PricePoint>, inst: Instrument): List<Double> {
            val seedDay = inst.seedEpochDay
            val usable = if (inst.seedValue != null && seedDay != null) prices.filter { it.epochDay >= seedDay } else prices
            return usable.sortedBy { it.epochDay }.map { it.close }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SMA crossing alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        fun notifyCrossing(context: Context, inst: Instrument, price: Double, sma: Double, side: Sma.Side) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val direction = if (side == Sma.Side.ABOVE) "above" else "below"
            val dev = Sma.deviationPct(price, sma)
            val text = String.format(
                Locale.US,
                "%s has passed %s its %d-day average: price %.2f vs SMA %.2f (%+.2f%%)",
                inst.symbol, direction, inst.smaWindow, price, sma, dev
            )
            val pi = PendingIntent.getActivity(
                context, inst.id.toInt(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("${inst.symbol} crossed $direction its SMA")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(inst.id.toInt(), notification)
        }

        /** (Re-)schedules the periodic fetch. Android enforces a 15-minute minimum. */
        fun schedule(context: Context, fetchesPerDay: Int) {
            val minutes = (24 * 60 / fetchesPerDay.coerceIn(1, 96)).toLong().coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<FetchWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun fetchNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<FetchWorker>().build())
        }
    }
}
