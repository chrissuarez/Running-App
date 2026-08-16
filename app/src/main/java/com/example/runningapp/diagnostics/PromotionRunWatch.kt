package com.example.runningapp.diagnostics

/**
 * The Run a Promotion is being held for (#310).
 *
 * The same rule as the Strap's release in [strapRunRowIdAfter]: a line names the Run it belongs to,
 * falling back to the one it was held for. It is here for `demoted`, which is the line #310 exists
 * for — a hand-back with a Run still live is the whole of what happened in #309 — and which without
 * this reads `run=-` on every normal stop. `publishRun` clears the live Run when it publishes
 * STOPPED and the Promotion is reconciled afterwards, so by the time the hand-back is written there
 * is no live Run left to name and a journal holding two Runs cannot say whose stop handed the
 * service back.
 *
 * What is remembered is the last Run seen live *during the current Promotion*, and it is forgotten
 * at the hand-back that ends it. Not the Run live when the Promotion was granted — the Promotion is
 * taken before the Run's row lands, so that is null for essentially every Run — and not the last Run
 * ever seen, which would name a finished Run on the unwinding of a later pre-run Acquisition's
 * Promotion. A journal that guesses wrong is worse than one with a gap in it.
 *
 * Read from the thread Promotion is reconciled on and written from the one the Run publishes on,
 * hence the volatile; there is nothing here to make atomic beyond the one field.
 */
class PromotionRunWatch {

    @Volatile
    private var seenLive: Long? = null

    /**
     * What the app has just published. Only a live Run is worth remembering: the null a stop
     * publishes is the very thing being covered for, so it must not erase what it is covering.
     */
    fun observe(liveRunRowId: Long?) {
        if (liveRunRowId != null) seenLive = liveRunRowId
    }

    /**
     * The Run to name on a hand-back, and the end of what this Promotion was held for.
     *
     * The live Run if there is one — the #309 shape, where the foreground state goes back with a Run
     * still recording — and otherwise the Run whose stop is what left nothing to earn the Promotion.
     * Nothing at all once neither holds, which is right for a pre-run Acquisition being unwound and
     * for a start the platform refused being handed back before any Run existed.
     */
    fun handBack(liveRunRowId: Long?): Long? {
        val heldFor = liveRunRowId ?: seenLive
        seenLive = null
        return heldFor
    }
}
