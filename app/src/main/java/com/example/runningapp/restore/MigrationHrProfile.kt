package com.example.runningapp.restore

import com.example.runningapp.HrProfile
import com.example.runningapp.UserSettings
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.archive.historyHrProfile
import com.example.runningapp.historyHrProfile

/**
 * The heart rates the v12 → v13 zone recompute re-bands finished runs on.
 *
 * Whichever pair belongs to the history being opened: [restored] is the settings a restore brought
 * with it, and is null for every ordinary launch and for a bare `.db` pick, which carries no
 * provenance at all and so can only be joining this phone's history.
 *
 * Both sides answer with `historyMaxHr` — see [com.example.runningapp.historyHrProfile] for why a
 * correction leaves finished runs where they are. Reaching for the live maximum here would hand one
 * file two different restored histories depending on what the Settings field said the day it was
 * picked (#267).
 *
 * One function rather than the same line at each site: the trial open (#201) has to migrate the
 * staged file exactly the way the launch will migrate the real one, or it is proving something
 * about a database the runner will never have.
 */
fun migrationHrProfile(restored: ArchivedSettings?, phone: UserSettings): HrProfile =
    restored?.historyHrProfile ?: phone.historyHrProfile
