package com.example.runningapp.restore

import com.example.runningapp.HrProfile
import com.example.runningapp.UserSettings
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.archive.historyHrProfile
import com.example.runningapp.historyHrProfile

/**
 * The heart rates a picked backup's finished runs are re-banded on by the v12 → v13 recompute.
 *
 * Whichever pair belongs to *that* history: the archive's own if the file brought settings with it,
 * and this phone's otherwise, because a bare `.db` carries no provenance and this phone's history
 * is the only history it can be joining.
 *
 * `historyMaxHr` on both sides, never `maxHr` — the recompute re-bands finished runs, and those two
 * numbers part company on purpose (#112, #172). A runner who stated 181 and later corrected to 195
 * has history banded on 181 and live zones on 195, because a correction must not rewrite runs
 * already read. Reaching for the live number would hand a restored file a different history
 * depending on what was in the Settings field the day it was picked (#267).
 *
 * One function rather than the same line at each site: the trial open (#201) has to migrate the
 * staged file exactly the way the launch will migrate the real one, or it is proving something
 * about a database the runner will never have.
 */
fun restoredHistoryHrProfile(archived: ArchivedSettings?, phone: UserSettings): HrProfile =
    archived?.historyHrProfile ?: phone.historyHrProfile
