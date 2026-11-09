package com.defaultuninstaller.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

enum class ActionType {
    UNINSTALL,
    UNINSTALL_SYSTEM,
    UNINSTALL_UPDATES,
    RESTORE,
    DISABLE,
    ENABLE,
    BLOCK_NOTIFICATIONS,
    UNBLOCK_NOTIFICATIONS,
}

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val action: ActionType,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean,
    val output: String?,
)

class Converters {
    @TypeConverter fun fromAction(value: ActionType): String = value.name
    @TypeConverter fun toAction(value: String): ActionType = ActionType.valueOf(value)
}

@Dao
interface HistoryDao {
    @Insert suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}
