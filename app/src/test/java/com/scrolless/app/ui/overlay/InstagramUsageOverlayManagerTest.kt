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
package com.scrolless.app.ui.overlay

import junit.framework.TestCase.assertEquals
import org.junit.Test

class InstagramUsageOverlayManagerTest {

    @Test
    fun `calculateDimAlpha at 0 seconds is 0 (completely transparent)`() {
        val alpha = InstagramUsageOverlayManager.calculateDimAlpha(0L)
        assertEquals(0, alpha)
    }

    @Test
    fun `calculateDimAlpha at 15 minutes is halfway to max (approx 30 percent or 76 alpha)`() {
        val alpha = InstagramUsageOverlayManager.calculateDimAlpha(15 * 60 * 1000L)
        // 0.5 * 0.60 * 255 = 76.5 -> 76
        assertEquals(76, alpha)
    }

    @Test
    fun `calculateDimAlpha at 30 minutes reaches max 60 percent (153 alpha)`() {
        val alpha = InstagramUsageOverlayManager.calculateDimAlpha(30 * 60 * 1000L)
        // 1.0 * 0.60 * 255 = 153
        assertEquals(153, alpha)
    }

    @Test
    fun `calculateDimAlpha above 30 minutes stays capped at max (153 alpha)`() {
        val alpha = InstagramUsageOverlayManager.calculateDimAlpha(60 * 60 * 1000L)
        assertEquals(153, alpha)
    }
}
