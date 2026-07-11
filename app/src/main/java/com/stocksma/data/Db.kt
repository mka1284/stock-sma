package com.stocksma.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity
data class Instrument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val name: String,
    /** SMA window in days. */
    val smaWindow: Int = 30,
    /** Notification threshold in percent (0 = notify on any crossing). */
    val thresholdPct: Double = 0.0,
    /** Optional user-entered SMA seed value (stand-in until the window fills with real data). */
    val seedValue: Double? = null,
    /** Epoch day on which the seed was entered. */
    val seedEpochDay: Long? = null,
    /** De-duplication state: side of the last notification (ABOVE/BELOW). */
    val lastNotifiedSide: String? = null,
    val lastNotifiedAt: Long? = null,
    val lastFetchError: String? = null,
    val lastUpdatedAt: Long? = null
)

@Entity(
    indices = [Index(value = ["instrumentId", "epochDay"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = Instrument::class,
        parentColumns = ["id"],
        childColumns = ["instrumentId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PricePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instrumentId: Long,
    /** Local date as epoch day (days since 1970-01-01). */
    val epochDay: Long,
    val close: Double
)

@Dao
interface StockDao {
    @Query("SELECT * FROM Instrument ORDER BY symbol")
    fun instruments(): Flow<List<Instrument>>

    @Query("SELECT * FROM Instrument")
    suspend fun instrumentsOnce(): List<Instrument>

    @Insert
    suspend fun insert(instrument: Instrument): Long

    @Update
    suspend fun update(instrument: Instrument)

    @Delete
    suspend fun delete(instrument: Instrument)

    @Query("SELECT * FROM PricePoint WHERE instrumentId = :id ORDER BY epochDay")
    fun prices(id: Long): Flow<List<PricePoint>>

    @Query("SELECT * FROM PricePoint WHERE instrumentId = :id ORDER BY epochDay")
    suspend fun pricesOnce(id: Long): List<PricePoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrices(points: List<PricePoint>)
}

@Database(entities = [Instrument::class, PricePoint::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StockDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "stocksma.db")
                .build()
                .also { instance = it }
        }
    }
}
