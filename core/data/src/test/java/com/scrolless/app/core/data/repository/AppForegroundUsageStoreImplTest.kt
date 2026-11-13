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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AppForegroundUsageStoreImplTest : BaseTest() {

    private val timeProvider = MutableTimeProvider(LocalDate.of(2026, 11, 13))
    private val dao = mockk<AppForegroundUsageDao>()
    private val store = AppForegroundUsageStoreImpl(timeProvider = timeProvider, appForegroundUsageDao = dao)

    @Test
    fun `getTodayUsageMillis returns 0 when no record found`() = runTest {
        val today = LocalDate.of(2026, 11, 13)
        coEvery { dao.getUsageSnapshot("com.instagram.android", today) } returns null

        val result = store.getTodayUsageMillis("com.instagram.android")
        assertEquals(0L, result)
    }

    @Test
    fun `getTodayUsageMillis returns saved duration`() = runTest {
        val today = LocalDate.of(2026, 11, 13)
        coEvery { dao.getUsageSnapshot("com.instagram.android", today) } returns 45_000L

        val result = store.getTodayUsageMillis("com.instagram.android")
        assertEquals(45_000L, result)
    }

    @Test
    fun `addUsageMillis adds duration and upserts entity`() = runTest {
        val today = LocalDate.of(2026, 11, 13)
        coEvery { dao.getUsageSnapshot("com.instagram.android", today) } returns 30_000L
        val slot = slot<AppForegroundUsageEntity>()
        coEvery { dao.upsertUsage(capture(slot)) } returns Unit

        store.addUsageMillis("com.instagram.android", 15_000L)

        coVerify(exactly = 1) { dao.upsertUsage(any()) }
        assertEquals("com.instagram.android", slot.captured.packageName)
        assertEquals(today, slot.captured.date)
        assertEquals(45_000L, slot.captured.durationMillis)
    }

    @Test
    fun `addUsageMillis with zero or negative does nothing`() = runTest {
        store.addUsageMillis("com.instagram.android", 0L)
        store.addUsageMillis("com.instagram.android", -100L)

        coVerify(exactly = 0) { dao.upsertUsage(any()) }
    }

    private class MutableTimeProvider(var currentDate: LocalDate) : TimeProvider {
        override fun localDateNow(): LocalDate = currentDate
        override fun localDateTimeNow(): LocalDateTime = currentDate.atStartOfDay()
        override fun currentTimeInMillis(): Long = 0L
        override fun systemZoneId(): ZoneId = ZoneId.systemDefault()
    }
}
