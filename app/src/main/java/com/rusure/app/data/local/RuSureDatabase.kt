package com.rusure.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.rusure.app.data.local.dao.AppTargetConfigDao
import com.rusure.app.data.local.dao.UsageSessionDao
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.local.entity.UsageSession

@Database(
    entities = [AppTargetConfig::class, UsageSession::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RuSureDatabase : RoomDatabase() {
    abstract fun appTargetConfigDao(): AppTargetConfigDao
    abstract fun usageSessionDao(): UsageSessionDao

    companion object {
        const val NAME = "rusure.db"
    }
}
