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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleReelTrackerTest {

    private val tracker = SingleReelTracker()

    @Test
    fun `initial state is empty and unexpired`() {
        assertNull(tracker.activeAuthor)
        assertNull(tracker.activeCaption)
        assertFalse(tracker.isExpired)
    }

    @Test
    fun `reels tab without back button is rejected and resets state`() {
        assertFalse(tracker.evaluateAllowance(hasBackButton = false, currentAuthor = "creator", currentCaption = "cool video"))
        assertNull(tracker.activeAuthor)
        assertNull(tracker.activeCaption)
        assertFalse(tracker.isExpired)
    }

    @Test
    fun `initial reel without immediate author is allowed and author locks when loaded`() {
        // First frame: author and caption not yet populated
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = null, currentCaption = null))
        assertNull(tracker.activeAuthor)

        // Second frame: author populated
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "alice", currentCaption = null))
        assertEquals("alice", tracker.activeAuthor)
        assertFalse(tracker.isExpired)

        // Subsequent frames on same reel remain allowed
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "alice", currentCaption = null))
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = null, currentCaption = null))
        assertFalse(tracker.isExpired)
    }

    @Test
    fun `asynchronous caption loading on the same reel does not prematurely expire`() {
        // Frame 1: author appears first, caption is null
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "alice", currentCaption = null))
        assertEquals("alice", tracker.activeAuthor)
        assertNull(tracker.activeCaption)

        // Frame 2 (a moment later): caption finishes loading
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "alice", currentCaption = "Why Microsoft?"))
        assertEquals("alice", tracker.activeAuthor)
        assertEquals("Why Microsoft?", tracker.activeCaption)
        assertFalse(tracker.isExpired)

        // Frame 3: user expands caption (...more)
        assertTrue(
            tracker.evaluateAllowance(
                hasBackButton = true,
                currentAuthor = "alice",
                currentCaption = "Why Microsoft? Full story here...",
            ),
        )
        assertFalse(tracker.isExpired)
    }

    @Test
    fun `swiping to next reel expires allowance immediately`() {
        // Start on first reel
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "alice", currentCaption = "first reel"))

        // User swipes up to second reel by different creator
        assertFalse(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "bob", currentCaption = "second reel"))
        assertTrue(tracker.isExpired)

        // Subsequent calls while on second reel remain rejected
        assertFalse(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "bob", currentCaption = "second reel"))
    }

    @Test
    fun `swiping to next reel with same author but distinct caption expires allowance`() {
        assertTrue(
            tracker.evaluateAllowance(
                hasBackButton = true,
                currentAuthor = "alice",
                currentCaption = "Tip 1: Learn Kotlin",
            ),
        )

        // User swipes to another reel by same author with different caption
        assertFalse(
            tracker.evaluateAllowance(
                hasBackButton = true,
                currentAuthor = "alice",
                currentCaption = "Tip 2: Learn Android",
            ),
        )
        assertTrue(tracker.isExpired)
    }

    @Test
    fun `reset clears expired state allowing a new single reel to be viewed`() {
        // Watch and scroll to expire
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "alice", currentCaption = "first reel"))
        assertFalse(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "bob", currentCaption = "second reel"))
        assertTrue(tracker.isExpired)

        // User exits back to feed
        tracker.reset()
        assertNull(tracker.activeAuthor)
        assertNull(tracker.activeCaption)
        assertFalse(tracker.isExpired)

        // User clicks another reel from feed
        assertTrue(tracker.evaluateAllowance(hasBackButton = true, currentAuthor = "charlie", currentCaption = "new reel"))
        assertEquals("charlie", tracker.activeAuthor)
        assertFalse(tracker.isExpired)
    }
}
