package com.example.runningapp.navigation

/**
 * The back stack as the closing rules see it: one filled address per page, oldest first, and a way
 * to take pages off the top (#476).
 *
 * The rules in this file decide *how many* pages come off. A [PageStack] only reports what is there
 * and does the taking. That split is the seam: in the app it is the NavController
 * ([NavControllerPageStack]); in a JVM test it is a plain list, so where a runner lands after a
 * delete is proved without a phone.
 */
interface PageStack {
    /**
     * Every page on the stack by its **filled** address — `session_detail/9`, never the pattern
     * `session_detail/{sessionId}` — oldest first. The bottom page is the start destination.
     *
     * Filled, because only a filled address names the page about one thing: a pattern is the same
     * for every Run, so it could not tell the page about the Run that has gone from the page about
     * the Run the runner went on to open.
     */
    val addresses: List<String>

    /** Takes the top [count] pages off. */
    fun takeOff(count: Int)
}

/**
 * Closing every page about one thing that has gone — a deleted Run, a deleted Segment, a group of
 * matched Runs that no longer exists, a Record this app cannot name.
 *
 * Every page is reached by stacking on the one before, and several of them link back into each
 * other: a Run's page links to its group, the group to every Run in it, including the Run it was
 * opened from; a Segment's page lists the Runs over it while a Run's page lists its Segments. So
 * History → Run A → its group → Run A leaves two `session_detail/A` pages, and Segments → X → one
 * of its Runs → that Run's card for X leaves two `segment_detail/X`.
 *
 * When the thing itself is gone, taking one copy is not enough. The copy left underneath is a page
 * for something that no longer exists, and Back walks the runner onto it: the read behind it can
 * never answer, so it holds its loading state for ever. So this takes the **lowest** copy and
 * everything stacked on it. Whatever sits between two copies was opened from a page about this
 * thing, and goes with it.
 *
 * A thing with no page on the stack takes nothing, which is right: the runner has already left the
 * page there was to correct. The bottom page is never taken. It is the start destination, which no
 * thing is addressed by, and taking it would leave the app showing nothing at all.
 */
fun PageStack.closeEveryPageOf(address: String) {
    val lowest = addresses.indexOf(address)
    if (lowest < 1) return
    takeOff(addresses.size - lowest)
}

/**
 * Leaving the page for a Run: **while that Run is on its way out, leaving its page leaves every
 * page of that Run** (#414).
 *
 * A Run's page can be on the stack more than once — `History → Run A → its group → Run A` — and
 * [closeEveryPageOf] is what the delete landing will do to it, so it takes the lower copy of A and
 * everything stacked on top of that copy. One step back from the top copy would put the runner on
 * the page in between: the group of Runs matched to A is not a page *about* A, so it carries no
 * "Deleting this run…" of its own and stays fully live. Anything the runner then opened from it —
 * another Run's page — sits above a doomed page, and the landing would sweep it away unasked.
 * Leaving by the same call the landing uses cannot leave anything above anything: the runner lands
 * where they first opened that Run from, which is exactly where the landing would have put them.
 *
 * Back is still Back. It stays open throughout — a delete that never lands must let the runner walk
 * away rather than trap them — and for a Run that is not going anywhere it is one step back the way
 * they came.
 */
fun PageStack.leaveRunPage(runId: Long?, runIsGoing: Boolean) {
    if (runIsGoing && runId != null) {
        closeEveryPageOf(Routes.sessionDetail(runId))
    } else {
        takeOff(1)
    }
}

/**
 * The Runs whose delete has landed take their pages with them, and only then are acknowledged
 * (#414).
 *
 * The page for a Run that no longer exists comes off the stack rather than being covered over, so
 * Back can never walk back onto it. Where the runner lands is wherever they opened it from —
 * History for a Run opened from History, a Record for one opened from a Record. By the deleted Run,
 * not by whatever page is on top: the delete lands after a wait the runner can walk away during.
 *
 * [landed] is kept state, not a one-off message: a landing that arrived while nobody was listening
 * is still there to be read. [acknowledge] is what clears it, so it is called only after that Run's
 * pages are off — acknowledged first, a landing interrupted between the two would be forgotten with
 * its page still standing on "Deleting this run…" for ever.
 */
fun PageStack.landDeletedRuns(landed: Set<Long>, acknowledge: (Long) -> Unit) {
    landed.forEach { runId ->
        closeEveryPageOf(Routes.sessionDetail(runId))
        acknowledge(runId)
    }
}

/**
 * A page's address spelled out from the pattern it was declared with and the arguments it was
 * opened with — `session_detail/{sessionId}` and `sessionId = 9` make `session_detail/9`, the same
 * string [Routes.sessionDetail] builds. A placeholder with no argument to fill it is left as it is,
 * so that page matches no filled address and is never taken by one.
 */
internal fun fillAddress(pattern: String, arguments: Map<String, String>): String =
    PLACEHOLDER.replace(pattern) { match -> arguments[match.groupValues[1]] ?: match.value }

private val PLACEHOLDER = Regex("""\{([^}]+)\}""")
