package com.example.runningapp

import android.Manifest
import com.example.runningapp.run.AcquisitionPhase
import com.example.runningapp.run.RunLifecycle
import com.example.runningapp.run.RunMode
import com.example.runningapp.run.StartRunRequest
import com.example.runningapp.run.RunRoute
import com.example.runningapp.run.runModeCanSetOutOnARoute
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.Flow
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RunPaceRow
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.navigation.Routes
import com.example.runningapp.ui.RoutePickerCard
import com.example.runningapp.ui.routeSuggestionSinceMillis
import com.example.runningapp.ui.suggestedRouteDistanceMeters
import com.example.runningapp.ui.runRouteSetOutAlong
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.debriefHeading
import com.example.runningapp.ui.workout.todayCardUiState
import androidx.compose.foundation.background
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.flow.emptyFlow
import com.example.runningapp.ui.ProgressViewModel

@Composable
fun MainScreen(
    hrService: HrForegroundService?,
    userSettings: UserSettings,
    coachPrescriptions: CoachPrescriptions,
    sessionRepository: SessionRepository,
    /**
     * The runner's library of courses, for the pre-run picker (#56).
     *
     * The whole library rather than a picked row, because the card has to be able to say there is
     * nothing to pick, and because a Route deleted while this screen sits open must drop out of the
     * pick rather than be started on.
     */
    routes: List<RouteHeader>,
    /**
     * The course picked for the next Run, and which way round — null for none picked (#56).
     *
     * Held above this screen rather than in it, because this screen does not survive being left:
     * another screen on top of it disposes it, and `navigateHome` clears the state saved for it as
     * well, so a walk to the Routes library to check which course is which can build a fresh
     * MainScreen — and a pick kept here would be silently dropped by exactly the trip a runner
     * makes to make it.
     */
    routeChoice: RunRoute?,
    onRouteChoiceChange: (RunRoute?) -> Unit,
    /** The picked course's shape, null while it is drawn or where it has none (#496). */
    pickedRouteThumbnail: com.example.runningapp.analysis.RouteThumbnail?,
    /** Opening the Routes screens to pick a course, sorted towards today's distance (#496). */
    onChooseRoute: (targetMeters: Double?, targetIsFixed: Boolean) -> Unit,
    /**
     * The phone changing zone, so the Today card's "Test due" answer arrives when the runner lands
     * rather than at the midnight of the zone they took off from (#320).
     *
     * Required, not defaulted to [emptyFlow]: see the same parameter on `ProgressViewModel`.
     */
    zoneChanges: Flow<Unit>,
    paddingValues: PaddingValues = PaddingValues(0.dp),
    onRequestPermissions: () -> Unit,
    onStartRun: (StartRunRequest) -> Unit,
    onRetryStrap: () -> Unit,
    onTogglePause: () -> Unit,
    onStopSession: () -> Unit,
    onConnectToDevice: (String, Boolean) -> Unit,
    onTestCue: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenManageDevices: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenSegments: () -> Unit,
    onOpenFullScreenMap: () -> Unit,
    onToggleSimulation: (Boolean, StartRunRequest) -> Unit,
    onRunModeChange: (String) -> Unit
) {
    // Skip today's plan (#107): a today-only choice that runs open-ended without touching the plan.
    // Defaults off every time the screen loads, so the plan is always queued unless actively skipped.
    var skipPlanToday by rememberSaveable { mutableStateOf(false) }

    // The selected run mode, held locally so a tap takes effect instantly for both the toggle
    // highlight and START — the settings write behind onRunModeChange is async, so reading it back
    // (via userSettings.runMode) would lag a just-made choice. Synced from settings when they change
    // externally; the toggle updates this and persists in the same tap.
    var selectedRunMode by rememberSaveable { mutableStateOf(userSettings.runMode) }
    LaunchedEffect(userSettings.runMode) { selectedRunMode = userSettings.runMode }

    // Which of the stage's Workouts today is (#174). Screen state, saved the same way the skip
    // choice is so a rotation doesn't undo the tap — and nowhere else, ever. Nothing writes a
    // position in the Plan down, because the Plan is a menu and has no position to write.
    var pickedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }


    val state = hrService?.hrState?.collectAsState()?.value ?: HrState()
    val activeStage = TrainingPlanProvider.resolveActiveStage(
        userSettings.activePlanId,
        userSettings.activeStageId
    )
    val stageWorkouts = activeStage?.workouts.orEmpty()
    // Whether this screen is actually in front of the runner. Two things need it: the strap chase
    // below, which may only reach for a foreground service from the foreground (#193), and the
    // Test-due read, which has to re-ask the calendar what day it is when the runner comes back.
    val screenLifecycle = LocalLifecycleOwner.current.lifecycle
    var screenIsResumed by remember(screenLifecycle) {
        mutableStateOf(screenLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(screenLifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            screenIsResumed = screenLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        screenLifecycle.addObserver(observer)
        onDispose { screenLifecycle.removeObserver(observer) }
    }
    // Whether the Test is due (#292). Asked of every Test the plan holds, so a Test run under the
    // Stage the runner has just been graduated out of still counts as the last one — and asked at
    // all only where the Stage in front of them offers a Test to pick. False until the first read
    // comes back: a prompt that flickers in is better than one that flickers out.
    val planTests = if (activeStage?.testWorkout == null) {
        emptyList()
    } else {
        TrainingPlanProvider.getPlanById(userSettings.activePlanId.orEmpty())?.tests.orEmpty()
    }
    // Collected only while the screen is in front of the runner, and restarted each time it comes
    // back (Codex P2). The rule's answer depends on the calendar day, and the day cannot be waited
    // for from inside a coroutine: `delay` on the main dispatcher counts uptime and stops counting
    // in deep sleep, so a phone asleep from Friday night to Monday wakes with hours still to run on
    // a sleep that should have ended at midnight. Resuming re-reads the day directly, which is the
    // one reading that cannot be late.
    //
    // Held in a var across the restart rather than re-collected into a fresh state, so the answer
    // already on screen stays there while the new read comes back: a prompt that flickers in is
    // better than one that flickers out.
    var testDue by remember(sessionRepository, planTests) { mutableStateOf(false) }
    LaunchedEffect(sessionRepository, planTests, screenIsResumed) {
        if (!screenIsResumed) return@LaunchedEffect
        // The zone goes in as a stream, not as a reading: the day this answer is about moves when the
        // runner flies, and the sleep to midnight already running cannot re-aim itself (#320).
        sessionRepository.testDueFlow(planTests, zoneChanges = zoneChanges)
            .collect { testDue = it }
    }
    // No testing-mode check: turning testing mode on erases the debrief, and the coach is refused
    // the write while it stays on, so there is nothing left to filter out on read (#113).
    val debrief = userSettings.latestDebrief?.takeIf { it.isNotBlank() }
    // The card resolves today's workout itself (adaptation included) so the screen and the run
    // read the same numbers — see withCoachPrescription (#111).
    // One reading of the clock for the card and for the route suggestion below it, so the seconds
    // the picker multiplies are the seconds the card is promising and not a moment later's (#422).
    val nowMillis = System.currentTimeMillis()
    val todayCard = todayCardUiState(
        stageTitle = activeStage?.title,
        stageWorkouts = stageWorkouts,
        pickedWorkoutId = pickedWorkoutId,
        settings = userSettings,
        prescriptions = coachPrescriptions,
        nowEpochMillis = nowMillis,
        runMode = selectedRunMode,
        skippedToday = skipPlanToday,
        testDue = testDue
    )

    // Taken from the card rather than from the pick itself, so START runs exactly what the card is
    // showing — including where a stale pick has already fallen back to the stage's first (#174).
    val todaysWorkoutId = todayCard.workouts.firstOrNull { it.picked }?.workoutId

    // Today's Run as it will actually be run, prescription applied — taken by the card's own answer
    // so the seconds the suggestion multiplies are the seconds the card promises (#422). Null on a
    // skipped or plan-less day, which is an open run and has no planned time to derive from.
    val todaysWorkout = todaysWorkoutId
        ?.let { id -> stageWorkouts.firstOrNull { it.id == id } }
        ?.withCoachPrescription(coachPrescriptions, nowMillis)

    // Declared here rather than beside the strap effect below because the route suggestion's read
    // is keyed on it: a Run ending is one of the moments that read has to be re-taken at.
    val isSessionActive = state.lifecycle != RunLifecycle.IDLE && state.lifecycle != RunLifecycle.STOPPED

    // The last Run this screen watched go live, kept after the session goes idle so the read below
    // knows which row to wait for (#422). Held rather than read straight off the state, because by
    // the time the Run is over the state no longer names it — activeDbSessionId is null the moment
    // the Run stops being live, which is exactly when the read wants it.
    //
    // Written from composition, which is safe here only because nothing reads it from composition:
    // the id is read inside the effect below, so no recomposition is recorded against it and the
    // write cannot loop.
    //
    // Saved rather than merely remembered, because a plain `remember` is thrown away when the
    // activity is recreated — a rotation while the finish sheet is still open is enough — and the
    // service state restored alongside it no longer names the Run either: activeDbSessionId went
    // null the moment the Run stopped being live, so there is nothing left to latch the id back
    // from and it would come back null. The read below would then be handed no Run to wait for and
    // would count the just-finished row while its record is still half-written, taking its default
    // isWalk = false; the Walk mark the sheet writes afterwards moves neither key of the effect, so
    // the suggestion would stay wrong for the rest of the day. The rule the gate states — that this
    // screen reads a Run's history only once that Run's record is complete — is right as it stands
    // in [SessionRepository.recentMeasuredRunsOnceTheRecordIsComplete]; what went missing was this
    // caller's half of it, the id, so the fix is to keep the id, not to add another case to the
    // rule. Kept the same way the finish sheet keeps its own pending id above, for the same reason:
    // "which Run is still owed a word" outlives the activity that asked the question.
    //
    // Deriving it from the restored finish-sheet state instead was declined: that id lives above
    // the NavHost and is cleared the moment the sheet is answered, while this screen still has to
    // wait for the totals of a Run whose sheet was dismissed — and a Run recorded with no sheet at
    // all would never set it.
    var lastRunRowId by rememberSaveable(sessionRepository) { mutableStateOf<Long?>(null) }
    state.activeDbSessionId?.let { lastRunRowId = it }

    // The Runs today's likely distance is derived from (#422). Read at a few moments rather than
    // watched, so the picker does not re-sort under the runner's finger: when the screen comes to
    // the front, and when a Run ends with the screen still in front of the runner. That second
    // moment is the one a resumed-only key missed — a Run started and finished here left the
    // lifecycle resumed and the repository unchanged, so the suggestion went on being worked out
    // from a history one Run short until the app was backgrounded.
    //
    // The wait for that Run's record to be complete lives in the repository rather than here
    // ([SessionRepository.recentMeasuredRunsOnceTheRecordIsComplete]): the stop publishes STOPPED
    // before it writes the Run's totals, and the finish sheet's Walk mark is written after them, so
    // a read taken the instant the session goes idle would miss the very Run it was re-taken for or
    // count a Walk as a Run — and that rule is worth a unit test, which a composable is not. The
    // finish sheet lives above this screen and cannot be seen from here, which is the other reason
    // the whole rule is stated there.
    var recentRuns by remember(sessionRepository) { mutableStateOf(emptyList<RunPaceRow>()) }
    LaunchedEffect(sessionRepository, screenIsResumed, isSessionActive) {
        if (!screenIsResumed) return@LaunchedEffect
        // Nothing to read for while a Run is on: the picker is not on screen then, and the Run that
        // would answer the question is the one still being run.
        if (isSessionActive) return@LaunchedEffect
        // The clock read here rather than taken from composition: this runs when the screen comes
        // back, which may be days after the frame that started it. Read *once* and used for both
        // ends of the window — how far back a Run may be and still say anything about today's
        // fitness, and the moment past which a row is a Run that has not happened (a clock
        // corrected backwards leaves one stamped there). Two readings would be two windows, and the
        // pair would disagree about where now is.
        val nowMillis = System.currentTimeMillis()
        recentRuns = sessionRepository.recentMeasuredRunsOnceTheRecordIsComplete(
            justFinishedRunId = lastRunRowId,
            sinceMillis = routeSuggestionSinceMillis(nowMillis),
            untilMillis = nowMillis,
        )
    }
    // The one distance the plan states outright (#422): a Test is measured against the Stage's Best
    // Effort requirement, which is a time at a set distance, so the course has to be at least that
    // far. Asked of the Stage rather than of the Workout, because the requirement belongs to the
    // Stage and a Workout does not know which Stage is holding it; and asked only of a Test, because
    // every other session on the stage is prescribed in seconds and the requirement says nothing
    // about how far those go.
    val todaysFixedDistanceMeters = if (todaysWorkout?.isTest == true) {
        activeStage?.bestEffortRequirement?.record?.distanceMeters
    } else {
        null
    }
    // Plain arithmetic on this phone: no network, no AI coach, no consent gate (#422).
    val routeTargetMeters =
        suggestedRouteDistanceMeters(todaysWorkout, recentRuns, todaysFixedDistanceMeters)
    val routeTargetIsFixed = todaysFixedDistanceMeters != null

    // Taken from the library rather than from the pick, so a course deleted while this screen sat
    // open is not the course a Run sets off on (#56) — the same rule the Workout pick keeps above.
    val pickedRoute = routes.firstOrNull { it.id == routeChoice?.routeId }
    val pickedRouteReversed = routeChoice?.reversed == true
    // Everything the tap has to carry, built in one place so START and Simulate cannot set off on
    // different Runs.
    val startRunRequest = StartRunRequest(
        skipPlan = skipPlanToday,
        runMode = RunMode.ofSettingValue(selectedRunMode),
        pickedWorkoutId = todaysWorkoutId,
        // The switch is against the course's usual way and the Run writes down against its line as
        // kept; the two differ where the course was flipped (#466).
        route = pickedRoute?.let { runRouteSetOutAlong(it, backwards = pickedRouteReversed) },
    )

    // A course is chosen for one Run (#56), so the tap that asks for that Run spends it. Without
    // this the pick would outlive the Run it was made for — the screen keeps it across a rotation
    // and across a walk to the Routes library and back, which is exactly what it should do while
    // the Run is still ahead of them, and exactly what it must not do once it is behind them. Not
    // conditioned on the Run actually beginning: a START that is refused was still the runner
    // saying "that Run, now", and a refusal they have to notice is better than a course they do not.
    val spendRouteChoice = { onRouteChoiceChange(null) }

    // Reach for the saved strap in the background while the record screen is up (#110): heart
    // rate is a sensor, so the app connects to it before you start and reports progress on the
    // sensor line — but it never blocks starting. Fires once per pre-run entry with a saved
    // device; skipped while simulating (no real strap) or once a run is active.
    //
    // Via the service intent, NOT hrService.connectToDevice(): a direct binder call races the
    // intent-based connects (Manage Devices, START) because it skips the onStartCommand queue —
    // the same race the onConnectToDevice comment documents. All connects funnel through
    // ACTION_START_FOREGROUND so they serialize on the service's main thread.
    val autoConnectContext = LocalContext.current
    val activeStrapAddress = userSettings.activeDeviceAddress
    // A foreground service may only be started from the foreground, and stopping a Run re-fires the
    // effect below — so a Run stopped from the notification, or stopped and pocketed, reached for
    // the strap from the background and Android killed the app for it (#193). Hence screenIsResumed
    // above.
    //
    // A key rather than a check inside the effect, because everything the effect tests is read from
    // the composition: held as a check, coming back to the screen would either never re-ask the
    // question or re-ask it against the state of whenever the effect was launched. As a key,
    // returning to the screen recomposes and the question is asked again, freshly.
    LaunchedEffect(hrService, activeStrapAddress, isSessionActive, state.isSimulating, screenIsResumed) {
        // Checked at fire time, not as a key: without BLUETOOTH_CONNECT the service's connect
        // path dead-ends immediately, so promoting it to foreground here would strand an idle
        // notification + wake lock just from opening the record screen (Codex P2 #123). The
        // user can still connect explicitly — those taps run the permission prompt flow.
        val canConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                autoConnectContext, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        // Idle only, and deliberately not "given up": a chase that ran out of attempts must not be
        // restarted from here the instant it ends (ADR 0007). The Retry button and START are how it
        // begins again.
        if (screenIsResumed && canConnect && !isSessionActive && !state.isSimulating &&
            hrService != null && activeStrapAddress != null &&
            state.acquisition.phase is AcquisitionPhase.Idle
        ) {
            val intent = Intent(autoConnectContext, HrForegroundService::class.java).apply {
                action = HrForegroundService.ACTION_START_FOREGROUND
                putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, activeStrapAddress)
                // No EXTRA_MAKE_ACTIVE: this is a background attempt, not a user choice — its
                // verify must not out-promote a strap the user activates while it's in flight.
            }
            try {
                ContextCompat.startForegroundService(autoConnectContext, intent)
            } catch (e: IllegalStateException) {
                // The screen went behind something between the check above and this line. Android
                // refuses a foreground start from the background, and this connect is a convenience
                // that is already allowed to fail — it waits for the next time the screen comes up.
                //
                // Caught as IllegalStateException rather than as ForegroundServiceStartNotAllowed-
                // Exception, which is its API 31 subclass: this app runs back to API 26, where
                // naming that class in a catch is naming a class the runtime does not have.
                Log.w("MainActivity", "Not reaching for the strap: the screen is no longer in front", e)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        bottomBar = {
            MainBottomBar(
                onOpenHistory = onOpenHistory,
                onOpenProgress = onOpenProgress,
                onOpenManageDevices = onOpenManageDevices,
                onOpenSettings = onOpenSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(RunningUiTokens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(RunningUiTokens.SectionSpacing),
                horizontalAlignment = Alignment.Start
            ) {
                item {
                    Text(text = "Running App", style = MaterialTheme.typography.headlineMedium)
                }
                item {
                    OutlinedButton(
                        onClick = onOpenTrainingPlan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                    ) {
                        Text("Open Training Plan")
                    }
                }
                // Here rather than in the bottom bar, which is already five buttons wide and
                // ellipsizing every one of its labels at the default text size (#63). A sixth would
                // cost the whole bar its legibility to reach a screen the runner visits between
                // runs, not during one.
                item {
                    OutlinedButton(
                        onClick = onOpenRoutes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                    ) {
                        Text("Open Routes")
                    }
                }
                // Beside Routes and for the same reason it is not in the bottom bar: both are
                // collections the runner curates between runs rather than during one (#63, #69).
                item {
                    OutlinedButton(
                        onClick = onOpenSegments,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                    ) {
                        Text("Open Segments")
                    }
                }

                // Treadmill / Outdoor is the one pre-run choice, pre-filled from last time (#107).
                if (!isSessionActive) {
                    item {
                        RunModeSelector(
                            runMode = selectedRunMode,
                            onRunModeChange = { mode ->
                                selectedRunMode = mode
                                onRunModeChange(mode)
                            }
                        )
                    }
                }

                // Not offered where a Run could not set out on a course anyway (#56) — asked of the
                // rule rather than spelled here, so the screen and the rulebook cannot disagree.
                if (!isSessionActive && runModeCanSetOutOnARoute(RunMode.ofSettingValue(selectedRunMode))) {
                    item {
                        RoutePickerCard(
                            routes = routes,
                            picked = pickedRoute,
                            pickedThumbnail = pickedRouteThumbnail,
                            reversed = pickedRouteReversed,
                            targetMeters = routeTargetMeters,
                            targetIsFixed = routeTargetIsFixed,
                            onChoose = { onChooseRoute(routeTargetMeters, routeTargetIsFixed) },
                            onReversedChange = { reversed ->
                                routeChoice?.let { onRouteChoiceChange(it.copy(reversed = reversed)) }
                            }
                        )
                    }
                }

                if (!isSessionActive) {
                    item {
                        TodayCard(
                            state = todayCard,
                            onPickWorkout = { pickedWorkoutId = it },
                            onSkipToday = { skipPlanToday = true },
                            onUndoSkip = { skipPlanToday = false },
                            onChoosePlan = onOpenTrainingPlan
                        )
                    }

                    // Always shown when there is a debrief, adaptation or not. The card's note is
                    // only the debrief's first sentence (#113), so this is the one place the
                    // coach's full reasoning can be read — suppressing it when the card carried a
                    // note, as this used to, left the numbers changed and the reasoning nowhere.
                    // The two no longer duplicate each other: one line of what changed on the
                    // card, the whole argument here.
                    if (debrief != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                                    Text(
                                        text = debriefHeading(userSettings.latestDebriefAuthor),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = debrief,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // In-run controls stay in the scroll area; the START button below is a pre-run
                // affordance only, so an active run shows Pause / Skip / Stop here instead.
                if (isSessionActive) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                                Text("Controls", style = MaterialTheme.typography.labelLarge)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = onTogglePause,
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                                    ) {
                                        Text(if (state.lifecycle == RunLifecycle.PAUSED) "Resume" else "Pause")
                                    }
                                    Button(
                                        onClick = { hrService?.skipCurrentPhase() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                                    ) {
                                        val label = when (state.currentPhase) {
                                            SessionPhase.WARM_UP -> "Skip Warmup"
                                            SessionPhase.MAIN -> "Start Cooldown"
                                            SessionPhase.COOL_DOWN -> "End Session"
                                        }
                                        Text(label)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = onStopSession,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = RunningUiTokens.MinTouchTarget)
                                ) {
                                    Text("Force Stop")
                                }
                            }
                        }
                    }
                }

                // Developer / testing tools. Kept off the clean pre-run path but retained because
                // the phone-first workflow drives runs through Simulate; user settings live behind
                // the Prefs tab in the bottom bar.
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = RunningUiTokens.MinTouchTarget)
                        ) {
                            Text("Permissions")
                        }
                        Button(
                            onClick = {
                                if (!state.isSimulating) spendRouteChoice()
                                onToggleSimulation(!state.isSimulating, startRunRequest)
                            },
                            colors = if (state.isSimulating) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else ButtonDefaults.buttonColors(),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = RunningUiTokens.MinTouchTarget)
                        ) {
                            Text(if (state.isSimulating) "Stop Sim" else "Simulate")
                        }
                        OutlinedButton(
                            onClick = onTestCue,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = RunningUiTokens.MinTouchTarget)
                        ) {
                            Text("Test Cue")
                        }
                    }
                }

                item {
                    SettingsSummaryCard(settings = state.userSettings)
                }

                if (isSessionActive) {
                    item {
                        WorkoutView(state = state, sessionRepository = sessionRepository, onOpenFullScreenMap = onOpenFullScreenMap)
                    }
                }
            }

            // One sensor line and the always-live START, pinned to the bottom (#110). Pre-run
            // only: an active run shows its live controls in the scroll area above.
            if (!isSessionActive) {
                StartFooter(
                    acquisition = state.acquisition,
                    strapConnected = state.acquisition.phase is AcquisitionPhase.Connected,
                    isSimulating = state.isSimulating,
                    onStart = {
                        onStartRun(startRunRequest)
                        spendRouteChoice()
                    },
                    // The activity-level handler re-acquires via the service intent (saved strap
                    // first, scan fallback) — no direct binder connect here, which would race the
                    // intent-based connect paths.
                    onRetryStrap = onRetryStrap
                )
            }
        }
    }
}

// Treadmill / Outdoor — the single pre-run choice, pre-filled from last time (#107).
