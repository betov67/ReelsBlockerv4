package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService — v4 (crash-hardened + detection fixes)
 *
 * WHAT v3 TESTING FOUND
 * -----------------------
 *  1. CRASH: an unhandled exception (thrown while looking up an "unknown"
 *     view id) propagated out of onAccessibilityEvent and killed the whole
 *     process — Accessibility *and* the Foreground Service share a
 *     process, so one uncaught exception took both down.
 *  2. OVER-BLOCKING: Stories were blocked outright, and DM/Profile Reels
 *     never got to open. Both symptoms trace back to the same cause —
 *     v3's Story/DM/Profile detection relied ONLY on a speculative list of
 *     view-id strings. Whenever none of those ids actually matched the
 *     installed Instagram build, classification silently fell through to
 *     "unknown origin", which v3 treats as "block it". A guess that misses
 *     doesn't fail loudly, it fails by over-blocking — exactly what was
 *     reported.
 *  3. BACKGROUND SURVIVAL: covered in BlockerForegroundService (watchdog +
 *     onTaskRemoved recovery), not in this file.
 *
 * WHAT CHANGED IN v4
 * -------------------
 *  A. EVERY entry point and EVERY tree/traversal call is wrapped in its
 *     own try/catch. Nothing is allowed to propagate out of this class.
 *     A failure in one id lookup logs a warning and moves on to the next
 *     id — it no longer aborts the whole check, let alone the service.
 *  B. The Story check is now the literal first thing onAccessibilityEvent
 *     does, before the event is even routed by type, exactly as requested.
 *     If it's a Story, the method returns immediately and touches no
 *     state at all — no back action, no session bookkeeping, nothing.
 *  C. Detection no longer relies on view ids alone. Every category (Story,
 *     Reel viewer, DM thread, Profile) is matched by id OR by a className
 *     substring heuristic, so a single wrong guess can no longer zero out
 *     a whole category. The specific ids/classes supplied after testing
 *     (`reel_tag`, `direct_private_share`, `com.instagram.reels.fragment`)
 *     are included below.
 *  D. The Story check specifically fails *toward* "treat as Story" if
 *     classification itself throws — the whole point of rule 2 is that a
 *     Story must never be touched, so an error while checking must never
 *     be allowed to result in a wrongful BACK.
 *
 * RULES IMPLEMENTED (unchanged from v3's intent)
 * ------------------------------------------------
 *  1. Bottom-nav Reels tab tap             -> BACK, immediately.
 *  2. Reel opened from the main/home feed  -> BACK, immediately.
 *  3. Reel opened from a user profile      -> ALLOWED (first Reel only).
 *  4. Reel opened from Direct Messages     -> ALLOWED (first Reel only).
 *  5. A vertical swipe inside an allowed Reel (trying to reach the NEXT
 *     Reel)                                -> BACK, immediately, session
 *                                              locked down.
 *  6. Stories are 100% exempt — checked first, before anything else, on
 *     every single event.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    private enum class ScreenType { REEL_VIEWER, DM_THREAD, PROFILE, OTHER }

    companion object {
        private const val TAG = "ReelsBlockerA11y"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Minimum gap between two performGlobalAction(BACK) calls. */
        private const val BACK_ACTION_COOLDOWN_MS = 600L

        /**
         * True while the system is actively bound to this service (i.e. it
         * is enabled in Settings > Accessibility and alive). Read by
         * BlockerForegroundService's watchdog to detect a disconnection it
         * cannot itself fix (only the user, in Settings, can re-enable an
         * Accessibility Service — see BlockerForegroundService for why).
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        // ── Story-exclusive signals (ids) — checked first, before the
        // ambiguous Reel-viewer containers Stories and Reels both share.
        private val STORY_EXCLUSIVE_IDS = setOf(
            "story_reply_composer", "reply_composer", "story_progress_bar",
            "story_duration_bar", "story_progress_container", "stories_tray",
            "reel_viewer_segmented_progress_bar", "story_viewer_swipe_container",
            "story_viewer_reply_box", "quick_reaction_emoji_picker",
            "stories_header", "story_viewer_fragment_container",
            // Story tag/mention/location sticker overlay — exists only on
            // Stories, never on the Reels player.
            "reel_tag"
        )

        // ── Story-exclusive signals (className substrings) — "stor" is
        // distinctive enough to never collide with Instagram's Reels
        // class names, which use "clips"/"reel", never "stor".
        private val STORY_CLASS_HINTS = listOf("stor")

        // ── Reel/Clips player — ids. Deliberately broad: a false POSITIVE
        // here just means we re-run the (cheap) origin check again; a
        // false NEGATIVE lets a Reel through completely unblocked, which
        // is the worse failure.
        private val REEL_VIEWER_IDS = setOf(
            "clips_viewer_view_pager", "clips_swipe_refresh_container",
            "reel_viewer_root", "unified_reel_view", "clips_video_container",
            "clips_fragment_root", "reels_viewer_fragment_root",
            "clips_caption_text_view", "reel_viewer_like_button",
            "clips_tab_fragment_container", "reel_viewer_pager",
            "ad_unit_root", "sponsored_reel_root", "reel_viewer"
        )

        // ── Reel/Clips player — className substrings.
        private val REEL_CLASS_HINTS = listOf(
            "reels.fragment", "reelviewer", "clipsviewer", "clips"
        )

        // ── Bottom navigation bar's Reels/Clips tab button.
        private val REELS_TAB_IDS = setOf(
            "clips_tab", "tab_icon_clips", "reels_tab", "tab_clips",
            "bottom_nav_clips", "bottom_navigation_clips"
        )

        // ── DM thread (conversation view) — ids. One of the two valid
        // origins for an allowed Reel.
        private val DM_THREAD_IDS = setOf(
            "direct_thread_fragment", "direct_thread_recycler_view",
            "thread_view_messages_recycler_view", "row_thread_composer_edittext",
            "direct_thread_toolbar", "direct_text_message_text_view",
            "direct_private_share"
        )

        // ── DM thread — className substring. Instagram's own internal
        // name for the DM feature is "Direct", so its fragment/activity
        // classes are very likely to contain this.
        private val DM_CLASS_HINTS = listOf("direct")

        // ── Profile page — ids. The other valid origin for an allowed Reel.
        private val PROFILE_IDS = setOf(
            "profile_header", "profile_tab_layout", "action_bar_profile_name",
            "profile_coordinator_layout", "user_detail_header",
            "profile_header_bio_text"
        )

        // ── Profile page — className substring.
        private val PROFILE_CLASS_HINTS = listOf("profile")

        // Only structural tree changes can possibly mean a Reel viewer
        // just appeared or disappeared. A single integer AND against this
        // mask — no timer, no debounce — filters out high-frequency noise
        // (progress ticks, caption redraws, like-count updates).
        private val STRUCTURAL_CHANGE_MASK: Int = run {
            var mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
            }
            mask
        }
    }

    // ── Mutable session state ──
    @Volatile private var lastNonReelScreenType: ScreenType = ScreenType.OTHER
    @Volatile private var inReelSession = false
    @Volatile private var reelOriginAllowed = false
    @Volatile private var lastBackActionMs = 0L

    // ─── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            lastNonReelScreenType = ScreenType.OTHER
            inReelSession = false
            reelOriginAllowed = false
            isConnected = true
            Log.i(TAG, "onServiceConnected — fresh state, isConnected=true")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        Log.w(TAG, "onUnbind — isConnected=false")
        return try {
            super.onUnbind(intent)
        } catch (e: Exception) {
            Log.e(TAG, "super.onUnbind failed", e)
            false
        }
    }

    override fun onDestroy() {
        isConnected = false
        Log.w(TAG, "onDestroy — isConnected=false")
        try {
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "super.onDestroy failed", e)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    // ─── Top-level event dispatch ───────────────────────────────────────
    //
    // Everything below this point runs inside ONE outer try/catch. No
    // exception thrown anywhere in this class — from a stale node, an
    // unrecognised id, a null field, anything — is allowed to escape this
    // method and reach the Android runtime.

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            // Final backstop. Every call below already catches its own
            // exceptions, so reaching this is already a sign something
            // unexpected happened — log it and move on. The service keeps
            // running either way.
            Log.e(TAG, "Unhandled exception in onAccessibilityEvent — swallowed to protect the service", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = try {
            event.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.packageName", e)
            null
        }
        if (pkg != INSTAGRAM_PACKAGE) return

        val root = safeRoot()

        // ── RULE 6, HIGHEST PRIORITY: Stories are 100% exempt. ──────────
        // This runs before the event is routed by type, for every single
        // event. If this is a Story, we do nothing at all — no back
        // action, no session-state change, no further checks of any kind.
        if (root != null && isStoryScreen(root)) {
            return
        }

        val eventType = try {
            event.eventType
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.eventType", e)
            return
        }

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                evaluateCurrentScreen(root)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isStructuralChange(event)) evaluateCurrentScreen(root)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(root)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, root)

            else -> { /* not relevant to any rule */ }
        }
    }

    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        Log.w(TAG, "rootInActiveWindow threw", e)
        null
    }

    private fun isStructuralChange(event: AccessibilityEvent): Boolean = try {
        (event.contentChangeTypes and STRUCTURAL_CHANGE_MASK) != 0
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read event.contentChangeTypes", e)
        false
    }

    // ─── Core evaluation — window-state changes and structural content changes ──

    private fun evaluateCurrentScreen(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return
            val screenType = classifyScreen(root) ?: return // couldn't classify safely; leave state untouched

            when (screenType) {
                ScreenType.REEL_VIEWER -> {
                    if (!inReelSession) {
                        // Brand-new Reel session — decide its origin exactly once.
                        inReelSession = true
                        reelOriginAllowed =
                            lastNonReelScreenType == ScreenType.DM_THREAD ||
                            lastNonReelScreenType == ScreenType.PROFILE
                    }
                    // Ad-Reel swaps, caption changes, like-button animations
                    // all re-fire this with the SAME session still active.
                    // reelOriginAllowed is only ever set above (on a
                    // brand-new session), so an allowed Reel stays allowed
                    // and a blocked one stays blocked straight through them.
                    if (!reelOriginAllowed) fireBack()
                }

                else -> {
                    // DM_THREAD, PROFILE, or OTHER: a genuine non-Reel screen.
                    lastNonReelScreenType = screenType
                    inReelSession = false
                    reelOriginAllowed = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateCurrentScreen failed", e)
        }
    }

    /** The only way to move to the NEXT Reel — this is what rule 5 blocks. */
    private fun handleScroll(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return

            // Defense in depth: the top-level dispatcher already excluded
            // Stories for this event, but re-check independently in case
            // this is ever reached by a different path in the future.
            if (isStoryScreen(root)) return

            val type = classifyScreen(root) ?: return
            if (type != ScreenType.REEL_VIEWER) return // scrolling somewhere else entirely

            if (!inReelSession) {
                // Inside a Reel viewer but we never got a window-state/
                // content event for it (some OEM fragment-swap paths skip
                // both). Fail safe: treat it as an un-allowed session.
                inReelSession = true
                reelOriginAllowed = false
            }

            if (reelOriginAllowed) {
                // First swipe away from an allowed DM/Profile Reel — the
                // user is trying to move on to the next Reel. Lock it.
                reelOriginAllowed = false
            }
            fireBack()
        } catch (e: Exception) {
            Log.e(TAG, "handleScroll failed", e)
        }
    }

    /** Taps — primarily the bottom-nav Reels tab. */
    private fun handleClick(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        try {
            val clickedId = try {
                event.source?.viewIdResourceName
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read clicked view id", e)
                null
            }

            if (clickedId != null && containsIdFragment(clickedId, REELS_TAB_IDS)) {
                // Fastest possible path: no tree fetch, just the clicked
                // view's own id. Immediate back on tab entry (rule 1).
                fireBack()
                return
            }

            // Defensive fallback only when we're not already tracking a
            // session: some Reel entry points (an inline thumbnail in the
            // feed) register only as a click with no window-state change.
            if (!inReelSession) evaluateCurrentScreen(root)
        } catch (e: Exception) {
            Log.e(TAG, "handleClick failed", e)
        }
    }

    // ─── Screen classification ───────────────────────────────────────────

    /**
     * Returns null (meaning "skip this cycle, don't touch any state") if
     * classification itself throws. A transient error here must never be
     * allowed to reset or corrupt an in-progress Reel session.
     */
    private fun classifyScreen(root: AccessibilityNodeInfo): ScreenType? = try {
        when {
            isReelViewerScreen(root) -> ScreenType.REEL_VIEWER
            isDmThreadScreen(root) -> ScreenType.DM_THREAD
            isProfileScreen(root) -> ScreenType.PROFILE
            else -> ScreenType.OTHER
        }
    } catch (e: Exception) {
        Log.e(TAG, "classifyScreen failed", e)
        null
    }

    /**
     * Unlike classifyScreen, this fails TOWARD "yes, it's a Story" on any
     * internal error. Rule 6 says Stories must never be touched, so if we
     * can't be sure, the safe default is to do nothing — the cost of a
     * false positive here is zero (we just skip one evaluation cycle); the
     * cost of a false negative is a wrongful BACK inside a real Story.
     */
    private fun isStoryScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, STORY_EXCLUSIVE_IDS) || classNameContainsAny(root, STORY_CLASS_HINTS)
    } catch (e: Exception) {
        Log.w(TAG, "isStoryScreen failed — defaulting to 'is a Story' to avoid ever blocking one", e)
        true
    }

    private fun isReelViewerScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, REEL_VIEWER_IDS) || classNameContainsAny(root, REEL_CLASS_HINTS)
    } catch (e: Exception) {
        Log.w(TAG, "isReelViewerScreen failed", e)
        false
    }

    private fun isDmThreadScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, DM_THREAD_IDS) || classNameContainsAny(root, DM_CLASS_HINTS)
    } catch (e: Exception) {
        Log.w(TAG, "isDmThreadScreen failed", e)
        false
    }

    private fun isProfileScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, PROFILE_IDS) || classNameContainsAny(root, PROFILE_CLASS_HINTS)
    } catch (e: Exception) {
        Log.w(TAG, "isProfileScreen failed", e)
        false
    }

    /**
     * Uses the platform's own indexed id lookup instead of a manual
     * recursive tree walk — faster, and short-circuits on the first match.
     * Each id is looked up in its OWN try/catch: if Instagram ever returns
     * something unexpected for one id (this is exactly what crashed v3),
     * we log it and keep checking the rest instead of aborting the whole
     * lookup.
     */
    private fun hasAnyId(root: AccessibilityNodeInfo, shortIds: Set<String>): Boolean {
        for (shortId in shortIds) {
            try {
                val fullId = "$INSTAGRAM_PACKAGE:id/$shortId"
                val matches = root.findAccessibilityNodeInfosByViewId(fullId)
                if (matches != null && matches.isNotEmpty()) return true
            } catch (e: Exception) {
                Log.w(TAG, "hasAnyId: lookup failed for id='$shortId'", e)
                // Keep checking the remaining ids — one bad id must not
                // abort the rest of the check.
            }
        }
        return false
    }

    private fun classNameContainsAny(root: AccessibilityNodeInfo, fragments: List<String>): Boolean = try {
        val cls = root.className?.toString()
        cls != null && fragments.any { cls.contains(it, ignoreCase = true) }
    } catch (e: Exception) {
        Log.w(TAG, "classNameContainsAny failed", e)
        false
    }

    private fun containsIdFragment(fullId: String, shortIds: Set<String>): Boolean = try {
        val shortName = fullId.substringAfterLast('/')
        shortIds.any { it.equals(shortName, ignoreCase = true) }
    } catch (e: Exception) {
        Log.w(TAG, "containsIdFragment failed", e)
        false
    }

    // ─── Back action ──────────────────────────────────────────────────────

    private fun fireBack() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastBackActionMs < BACK_ACTION_COOLDOWN_MS) return
            lastBackActionMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) failed", e)
        }
    }
}
