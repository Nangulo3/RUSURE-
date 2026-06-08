package com.rusure.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.rusure.app.data.local.entity.AppTargetConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface AppTargetConfigDao {

    @Query("SELECT * FROM app_target_config ORDER BY displayName ASC")
    fun observeAll(): Flow<List<AppTargetConfig>>

    @Query("SELECT * FROM app_target_config WHERE enabled = 1")
    fun observeEnabled(): Flow<List<AppTargetConfig>>

    @Query("SELECT * FROM app_target_config WHERE enabled = 1")
    suspend fun getEnabled(): List<AppTargetConfig>

    @Query("SELECT * FROM app_target_config WHERE catalogKey = :catalogKey LIMIT 1")
    suspend fun getByCatalogKey(catalogKey: String): AppTargetConfig?

    @Query("SELECT COUNT(*) FROM app_target_config")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(config: AppTargetConfig)

    @Update
    suspend fun update(config: AppTargetConfig)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(configs: List<AppTargetConfig>)
}
