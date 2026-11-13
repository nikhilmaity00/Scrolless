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
package com.scrolless.app.accessibility

import androidx.annotation.VisibleForTesting

/**
 * Tracks the active single-Reel viewing state when the user opens an individual Reel
 * from their feed, story, or explore grid.
 *
 * Allows the initial Reel to be watched completely, but detects when the user
 * swipes or navigates to a subsequent Reel so blocking can be enforced immediately.
 */
internal class SingleReelTracker {

    @VisibleForTesting
    internal var activeAuthor: String? = null
        private set

    @VisibleForTesting
    internal var activeCaption: String? = null
        private set

    @VisibleForTesting
    internal var isExpired: Boolean = false
        private set

    /**
     * Resets tracker state when the user exits the Reel viewer or navigates away.
     */
    fun reset() {
        activeAuthor = null
        activeCaption = null
        isExpired = false
    }

    /**
     * Evaluates whether the current screen qualifies for single-Reel allowance.
     *
     * @param hasBackButton True if the viewer was opened with back navigation (e.g. from feed/story/explore).
     * @param currentAuthor The creator/author identifier of the visible reel, or null if loading.
     * @param currentCaption The caption or secondary identifier of the visible reel, or null if not yet loaded.
     * @return True if blocking should be suppressed to let the user watch this Reel; false to enforce blocking.
     */
    fun evaluateAllowance(hasBackButton: Boolean, currentAuthor: String?, currentCaption: String? = null): Boolean {
        if (!hasBackButton) {
            reset()
            return false
        }

        if (isExpired) {
            return false
        }

        val active = activeAuthor
        if (active == null) {
            if (!currentAuthor.isNullOrBlank()) {
                activeAuthor = currentAuthor
                activeCaption = currentCaption?.takeIf { it.isNotBlank() }
            }
            return true
        }

        if (!currentAuthor.isNullOrBlank() && !currentAuthor.equals(active, ignoreCase = true)) {
            // User scrolled to a new reel by a different author!
            isExpired = true
            return false
        }

        // Same author (or author temporarily not resolved during layout animation).
        // Check caption evolution if available
        if (activeCaption == null && !currentCaption.isNullOrBlank()) {
            activeCaption = currentCaption
        } else if (activeCaption != null && !currentCaption.isNullOrBlank()) {
            val act = activeCaption!!
            val cur = currentCaption
            if (!cur.contains(act, ignoreCase = true) && !act.contains(cur, ignoreCase = true)) {
                // Different reel by the same author!
                isExpired = true
                return false
            }
        }

        return true
    }
}
