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
package com.scrolless.app.core.data.repository

import com.scrolless.app.core.blocking.time.TimeProvider
import com.scrolless.app.core.data.database.dao.AppForegroundUsageDao
import com.scrolless.app.core.data.database.model.AppForegroundUsageEntity
import com.scrolless.app.core.repository.AppForegroundUsageStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForegroundUsageStoreImpl @Inject constructor(
    private val timeProvider: TimeProvider,
    private val appForegroundUsageDao: AppForegroundUsageDao,
) : AppForegroundUsageStore {

    override suspend fun getTodayUsageMillis(packageName: String): Long {
        val today = timeProvider.localDateNow()
        return appForegroundUsageDao.getUsageSnapshot(packageName, today) ?: 0L
    }

    override suspend fun addUsageMillis(packageName: String, additionalMillis: Long) {
        if (additionalMillis <= 0L) return
        val today = timeProvider.localDateNow()
        val current = appForegroundUsageDao.getUsageSnapshot(packageName, today) ?: 0L
        appForegroundUsageDao.upsertUsage(
            AppForegroundUsageEntity(
                packageName = packageName,
                date = today,
                durationMillis = current + additionalMillis,
            ),
        )
    }
}
