package com.example.runningapp.diagnostics

/**
 * The Run something was being held for, for as long as the holding lasts (#310).
 *
 * One rule, kept once, for the two things the journal has to name after a Run that is already gone:
 * the Promotion, whose `demoted` line is what #310 exists for, and the Strap, whose release closes
 * every strapped Run. Both are let go of *after* `publishRun` has cleared the live Run on a stop, so
 * both would otherwise write `run=-` on an ordinary Run and a journal holding two Runs could say
 * neither whose stop handed the service back nor whose stop let go of the Strap. Two copies of this
 * rule is why the same fault was found three times over.
 *
 * What is remembered is the last Run seen live *during the current holding*, forgotten at both ends
 * of it. Not the Run live when the holding began — the Promotion is taken before the Run's row
 * lands, and a Strap is often worn before START, so that is null for essentially every Run — and not
 * the last Run ever seen, which would name a finished Run on the next holding. A journal that
 * guesses wrong is worse than one with a gap in it.
 *
 * [begins] is why the forgetting happens at both ends. A Strap connecting has no hand-back behind it
 * to have cleared the Run before: a Run that wore no Strap at all leaves a Run remembered and
 * nothing to release it, and the next Strap to arrive would be named for a Run that was over before
 * it was put on. Promotion needs no such call and does not make one: its holdings are strictly
 * sequential, the hand-back that ends one being what precedes the next, and a
 * `promoteForStartCommand` landing mid-Run is unconditional — a [begins] there would drop the Run
 * that a demote in that same window has to name, which is #309's line and the one line that must
 * never read `run=-`.
 *
 * Read from the thread Promotion is reconciled on and written from the one the Run publishes on,
 * hence the volatile; there is nothing here to make atomic beyond the one field.
 */
class RunHeldFor {

    @Volatile
    private var seenLive: Long? = null

    /**
     * The Run seen live since the holding began, for a caller that has to name a line without
     * ending the holding — the Strap's release is decided inside `journalEntriesFor`, which stays a
     * pure function of what was published.
     */
    val runRowId: Long? get() = seenLive

    /**
     * A holding begins, and whatever was remembered belonged to the one before it. See the class
     * KDoc for why the Strap calls this and the Promotion must not.
     */
    fun begins() {
        seenLive = null
    }

    /**
     * What the app has just published. Only a live Run is worth remembering: the null a stop
     * publishes is the very thing being covered for, so it must not erase what it is covering.
     */
    fun observe(liveRunRowId: Long?) {
        if (liveRunRowId != null) seenLive = liveRunRowId
    }

    /**
     * The Run to name as the holding ends, and the end of it.
     *
     * The live Run if there is one — the #309 shape, where the foreground state goes back with a Run
     * still recording — and otherwise the Run this was being held for, whose stop is what left
     * nothing to hold it. Nothing at all once neither holds, which is right for a pre-run
     * Acquisition's Promotion being unwound, for a start the platform refused being handed back
     * before any Run existed, and for a Strap taken off with no Run in sight.
     */
    fun ends(liveRunRowId: Long?): Long? {
        val heldFor = liveRunRowId ?: seenLive
        seenLive = null
        return heldFor
    }
}
