/*
 * Copyright (C) 2026 Scrolless
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.scrolless.app.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.scrolless.app.core.data.database.model.AppForegroundUsageEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface AppForegroundUsageDao {

    @Query("SELECT durationMillis FROM app_foreground_usage WHERE packageName = :packageName AND date = :date")
    suspend fun getUsageSnapshot(packageName: String, date: LocalDate): Long?

    @Query("SELECT durationMillis FROM app_foreground_usage WHERE packageName = :packageName AND date = :date")
    fun observeUsage(packageName: String, date: LocalDate): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(entity: AppForegroundUsageEntity)
}
