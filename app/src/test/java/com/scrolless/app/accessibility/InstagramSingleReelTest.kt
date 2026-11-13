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

import com.scrolless.app.core.model.BlockableApp
import com.scrolless.app.core.model.ResolvedBlockableApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 35], shadows = [IndexedTestNode::class])
class InstagramSingleReelTest {
    private var allowSingleReel = false
    private val service = Robolectric.buildService(TestAccessibilityService::class.java).create().get()
    private val scanner = ContentScanner(
        service = service,
        windowAttachedCover = { false },
        allowVideosSentByDm = { false },
        includeStories = { false },
        allowSingleReelFromFeed = { allowSingleReel },
    )
    private val instagram = ResolvedBlockableApp(BlockableApp.REELS, "com.instagram.android")

    @Test
    fun `when setting disabled, reels viewer is not suppressed`() {
        allowSingleReel = false
        showReelViewer(hasBackButton = true, author = "alice")

        val detected = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detected)
        assertFalse(detected!!.blockingSuppressed)
    }

    @Test
    fun `when setting enabled, reels tab without back button is not suppressed`() {
        allowSingleReel = true
        showReelViewer(hasBackButton = false, author = "alice")

        val detected = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detected)
        assertFalse(detected!!.blockingSuppressed)
    }

    @Test
    fun `when setting enabled, reel opened with back button is suppressed for single reel`() {
        allowSingleReel = true
        showReelViewer(hasBackButton = true, author = "alice", caption = "Great learning tips")

        val detected = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detected)
        assertTrue(detected!!.blockingSuppressed)

        // Staying on the same reel keeps blocking suppressed
        showReelViewer(hasBackButton = true, author = "alice", caption = "Great learning tips")
        val detectedSame = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detectedSame)
        assertTrue(detectedSame!!.blockingSuppressed)
    }

    @Test
    fun `swiping to a different reel immediately unsuppresses blocking`() {
        allowSingleReel = true

        // 1. Initial Reel
        showReelViewer(hasBackButton = true, author = "alice", caption = "Reel 1")
        val first = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(first)
        assertTrue(first!!.blockingSuppressed)

        // 2. Swiped to next Reel (different author)
        showReelViewer(hasBackButton = true, author = "bob", caption = "Reel 2")
        val second = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(second)
        assertFalse(second!!.blockingSuppressed)

        // 3. Staying on subsequent reel remains unsuppressed
        showReelViewer(hasBackButton = true, author = "bob", caption = "Reel 2")
        val third = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(third)
        assertFalse(third!!.blockingSuppressed)
    }

    @Test
    fun `exiting reel viewer resets tracking so next feed reel can be watched`() {
        allowSingleReel = true

        // Watch Reel 1 and scroll to Reel 2 (expired)
        showReelViewer(hasBackButton = true, author = "alice", caption = "Reel 1")
        assertTrue(scanner.findVisibleBlockedContent(instagram)!!.blockingSuppressed)
        showReelViewer(hasBackButton = true, author = "bob", caption = "Reel 2")
        assertFalse(scanner.findVisibleBlockedContent(instagram)!!.blockingSuppressed)

        // User exits back to feed
        scanner.resetSingleReelTracking()

        // User taps a new Reel 3 from feed
        showReelViewer(hasBackButton = true, author = "charlie", caption = "Reel 3")
        val third = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(third)
        assertTrue(third!!.blockingSuppressed)
    }

    @Test
    fun `when setting enabled, modern reel with start action buttons container and clips author username is suppressed`() {
        allowSingleReel = true
        showModernReelViewer(hasBackButton = true, author = "diksha__kadam", caption = "Why Microsoft?")

        val detected = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detected)
        assertTrue(detected!!.blockingSuppressed)

        // Asynchronous caption loading on the same reel remains suppressed
        showModernReelViewer(hasBackButton = true, author = "diksha__kadam", caption = "Why Microsoft? Expanded text...")
        val detectedExpanded = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detectedExpanded)
        assertTrue(detectedExpanded!!.blockingSuppressed)

        // Swiping to next reel expires
        showModernReelViewer(hasBackButton = true, author = "other_creator", caption = "Another reel")
        val detectedNext = scanner.findVisibleBlockedContent(instagram)
        assertNotNull(detectedNext)
        assertFalse(detectedNext!!.blockingSuppressed)
    }

    private fun showModernReelViewer(hasBackButton: Boolean, author: String? = null, caption: String? = null) {
        service.showTestHierarchy(instagram.packageId) { root ->
            val pager = createTestChildNode(service, instagram.packageId, viewId = "clips_viewer_view_pager")
            shadowOf(root).addChild(pager)

            if (hasBackButton) {
                val actionButtonsContainer = createTestChildNode(
                    service,
                    instagram.packageId,
                    viewId = "clips_action_bar_start_action_buttons",
                )
                val backImage = createTestChildNode(service, instagram.packageId, contentDescription = "Back")
                shadowOf(actionButtonsContainer).addChild(backImage)
                shadowOf(root).addChild(actionButtonsContainer)
            }

            if (author != null) {
                val authorNode = createTestChildNode(
                    service,
                    instagram.packageId,
                    viewId = "clips_author_username",
                    text = author,
                )
                shadowOf(root).addChild(authorNode)
            }

            if (caption != null) {
                val captionComponent = createTestChildNode(
                    service,
                    instagram.packageId,
                    viewId = "clips_caption_component",
                )
                val captionText = createTestChildNode(service, instagram.packageId, contentDescription = caption)
                shadowOf(captionComponent).addChild(captionText)
                shadowOf(root).addChild(captionComponent)
            }
        }
    }

    private fun showReelViewer(hasBackButton: Boolean, author: String? = null, caption: String? = null) {
        service.showTestHierarchy(instagram.packageId) { root ->
            val pager = createTestChildNode(service, instagram.packageId, viewId = "clips_viewer_view_pager")
            shadowOf(root).addChild(pager)

            if (hasBackButton) {
                val backButton = createTestChildNode(service, instagram.packageId, viewId = "action_bar_button_back")
                shadowOf(root).addChild(backButton)
            }

            if (author != null) {
                val authorNode = createTestChildNode(service, instagram.packageId, viewId = "clips_author_name", text = author)
                shadowOf(root).addChild(authorNode)
            }

            if (caption != null) {
                val captionNode =
                    createTestChildNode(service, instagram.packageId, viewId = "row_feed_comment_textview_layout", text = caption)
                shadowOf(root).addChild(captionNode)
            }
        }
    }
}
