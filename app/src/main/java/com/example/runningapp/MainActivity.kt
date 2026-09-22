package com.example.runningapp

import android.Manifest
import android.net.Uri
import com.example.runningapp.run.RunLifecycle
import com.example.runningapp.run.RunMode
import com.example.runningapp.run.StartRunRequest
import com.example.runningapp.run.RunRoute
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.IBinder
import androidx.room.withTransaction
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.example.runningapp.archive.MonthlyArchiveWorker
import com.example.runningapp.archive.SafArchiveFolder
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.isFinished
import com.example.runningapp.export.ExportFormat
import com.example.runningapp.export.exportShareChooser
import com.example.runningapp.navigation.NavControllerPageStack
import com.example.runningapp.navigation.Routes
import com.example.runningapp.navigation.closeEveryPageOf
import com.example.runningapp.navigation.leaveRunPage
import com.example.runningapp.ui.FeelFeedbackSheet
import com.example.runningapp.ui.BackupViewModel
import com.example.runningapp.ui.BackupViewModelFactory
import com.example.runningapp.ui.RestoreUiState
import com.example.runningapp.ui.RestoreViewModel
import com.example.runningapp.ui.RestoreViewModelFactory
import com.example.runningapp.ui.SegmentCreateScreen
import com.example.runningapp.ui.SegmentDetailScreen
import com.example.runningapp.ui.SegmentsScreen
import com.example.runningapp.ui.SegmentsViewModel
import com.example.runningapp.ui.SegmentsViewModelFactory
import com.example.runningapp.routes.RunRouteSaver
import com.example.runningapp.ui.RoutePicking
import com.example.runningapp.ui.routeLengthToOpenWhilePicking
import com.example.runningapp.ui.routeLibraryRowsNearestFirst
import com.example.runningapp.ui.routeChoiceIsNoRoute
import com.example.runningapp.ui.RunRouteSaver
import com.example.runningapp.ui.RouteDetailScreen
import com.example.runningapp.ui.RoutesScreen
import com.example.runningapp.ui.RoutesViewModel
import com.example.runningapp.ui.RoutesViewModelFactory
import com.example.runningapp.ui.HistoryScreen
import com.example.runningapp.ui.HistoryViewModel
import com.example.runningapp.ui.HistoryViewModelFactory
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.ui.RecordDetailScreen
import com.example.runningapp.ui.recordDetailNotReadYet
import com.example.runningapp.ui.RecordsViewModel
import com.example.runningapp.ui.RecordsViewModelFactory
import com.example.runningapp.ui.ProgressScreen
import com.example.runningapp.ui.ProgressViewModel
import com.example.runningapp.ui.ProgressViewModelFactory
import com.example.runningapp.ui.MatchedRunsScreen
import com.example.runningapp.data.RunSummaryRow
import com.example.runningapp.ui.RunSummaryUi
import com.example.runningapp.ui.SessionDetailScreen
import com.example.runningapp.ui.SessionDetailViewModel
import com.example.runningapp.ui.SessionDetailViewModelFactory
import com.example.runningapp.training.BarStanding
import com.example.runningapp.training.StageTrainingSummary
import com.example.runningapp.training.alreadyBeatenLine
import com.example.runningapp.training.barShortfallLine
import com.example.runningapp.training.stageTrainingSummaryOf
import com.example.runningapp.ui.TrainingPlanScreen
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.workout.FullScreenMapScreen
import java.time.LocalDate
import java.time.ZoneId
import com.example.runningapp.run.AcquisitionState
import androidx.navigation.compose.NavHost
import kotlinx.coroutines.Dispatchers
import com.example.runningapp.navigation.landDeletedRuns
import com.example.runningapp.ui.runRouteAfterPick
import com.example.runningapp.ui.SettingsScreen
import com.example.runningapp.ui.backupResultMessage
import com.example.runningapp.ui.strapRowSummary

class MainActivity : ComponentActivity() {

    private var hrService by mutableStateOf<HrForegroundService?>(null)
    private var isBound by mutableStateOf(false)
    private var forceMainToken = mutableStateOf(0)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HrForegroundService.LocalBinder
            val bound = binder.getService()
            hrService = bound
            isBound = true

            // Mission: Robust Sync - if service is running, force UI to main screen
            if (bound.isSessionActive()) {
                Log.d("MainActivity", "Restoring active session UI")
                forceMainToken.value++
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            hrService = null
        }
    }

    // A START tap that had to ask for location first parks here until the
    // dialog resolves, then the run starts from the launcher callback. Whole,
    // including which Workout was picked (#174): the tap is replayed as it was
    // made, not re-read from a screen that has been sitting behind a dialog.
    private var pendingStartRun: StartRunRequest? = null

    // A Manage Devices scan tap that had to ask for BLUETOOTH_SCAN first.
    // Unlike START (which proceeds even on denial — GPS is a sensor, #110),
    // a scan without the permission is a pure dead-end, so it only fires on grant.
    private var pendingScan = false

    /**
     * A `.gpx` another app asked this one to open, waiting for the screen to be built (#54).
     *
     * Parked in a field rather than imported here, because an import belongs to the Route library's
     * view model and that does not exist yet when the intent arrives — this Activity may be being
     * created by the very tap that carried the file.
     *
     * Clearing this field is not what stops the file being imported twice; see [takeRouteFileIn].
     * This field lives and dies with the Activity, and a recreated Activity is handed the original
     * intent again.
     */
    private var pendingRouteFile by mutableStateOf<Uri?>(null)

    /**
     * "Open with" on a `.gpx` while this app is already open.
     *
     * Reached only because the Activity is `singleTop`: without that Android would build a second
     * MainActivity on top of the first, which would bind the service a second time and leave a Run
     * in progress being watched by a screen the runner cannot get back to.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeRouteFileIn(intent)?.let { pendingRouteFile = it }
    }

    /**
     * The `.gpx` an intent is asking this app to open, or null if it is asking for anything else.
     *
     * Taken rather than merely read: an Activity keeps the intent it was launched by, and Android
     * hands the same one back every time it is recreated. A file left sitting in it is imported
     * again on each recreation — and a recreation is an ordinary thing, not a rare one. Changing
     * the phone's text size while the Route library was open put two more copies of the same route
     * in the library, and changing it back put a third, none of which the runner asked for.
     *
     * So the file comes out of the intent as it is read. What is left behind is an ACTION_VIEW
     * intent with no data, which asks for nothing.
     *
     * This reaches only as far as this process, and since #277 that is all it has to reach: a file
     * no longer gets into the copy of the intent the system keeps, because this Activity no longer
     * owns the `.gpx` filters — [com.example.runningapp.OpenRouteFileActivity] does. See
     * [com.example.runningapp.routes.RouteFileLaunch].
     */
    private fun takeRouteFileIn(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val file = intent.data ?: return null
        intent.data = null
        return file
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Resume a START that was waiting on the location dialog. The gate
            // was only "having asked" (#110) — the run starts whether or not
            // the dialog was granted; denied just means no GPS this run.
            pendingStartRun?.let { parked ->
                pendingStartRun = null
                sendStartRun(parked)
            }
            if (pendingScan) {
                pendingScan = false
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) sendForceScan()
            }
        }

    private fun sendForceScan() {
        val intent = Intent(this, HrForegroundService::class.java).apply {
            action = HrForegroundService.ACTION_FORCE_SCAN
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendStartRun(request: StartRunRequest) {
        // START begins the run regardless of the strap (#110): the service
        // opens the record and starts the clock, then acquires the strap as a
        // sensor alongside. The mode travels with the intent so a just-tapped
        // Treadmill/Outdoor choice is honoured even before its settings write
        // lands.
        val intent = Intent(this, HrForegroundService::class.java).apply {
            action = HrForegroundService.ACTION_START_RUN
            putRunChoices(request)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * Closes the app and reopens it, so the armed restore can be applied before Room opens (#86).
     *
     * The process is ended rather than the Activity recreated, and that is the whole point: the
     * database this app has open is the one about to be replaced, and only a fresh process is
     * guaranteed to have no connection to it, no Room instance cached, and no background work
     * mid-write. `PendingRestore.applyIfArmed` then runs on the way back up, in the one window
     * where the file provably has no readers.
     *
     * The relaunch intent is started first so Android has somewhere to go; the exit follows
     * immediately, and the runner sees an ordinary relaunch onto their restored history.
     */
    private fun restartForRestore() {
        val relaunch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (relaunch != null) startActivity(relaunch)
        finish()
        // Not exitProcess: this is the documented way to end an Android process without running
        // shutdown hooks that would try to touch the database on the way out.
        Runtime.getRuntime().exit(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A `.gpx` this launch was started by (#54). Read before anything else so it is already
        // parked by the time the Route library's view model exists to be handed it.
        //
        // Not when this Activity is its task's root, though. Since #277 the root is always started
        // by [OpenRouteFileActivity]'s plain Home launch, so a file in a root launch intent can only
        // be Android replaying a task that an "Open with" rooted before that fix landed — a task
        // that outlives an app update, and heals the first time it is created afresh. The runner
        // tapped their own app, not a file, and the file's read grant died with the process that was
        // given it, so there is nothing here to do but let them have Home.
        pendingRouteFile = takeRouteFileIn(intent)?.takeUnless { isTaskRoot }

        // Every pass that goes back for work a previous process owed — a Run left interrupted, a
        // history measured before a feature shipped, a debt written down on the way out. Once per
        // process, in the background, on a scope that outlives this Activity. The list, its order and
        // the reason for each pass are in [launchPassesOver] (#477).
        runningAppContainer().payLaunchPassesOnce()

        // Keeps the monthly full archive scheduled (#85). Called on every launch and cheap every
        // time: an existing schedule is left exactly where it is, so this only ever creates the job
        // the first time, or after the runner has cleared the app's data.
        MonthlyArchiveWorker.schedule(this)

        setContent {
            RunningAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                  Box(modifier = Modifier.fillMaxSize()) {
                    val serviceState = produceState(initialValue = HrState(), key1 = hrService) {
                        hrService?.let { service ->
                            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                service.hrState.collect { value = it }
                            }
                        }
                    }
                    val scope = rememberCoroutineScope()
                    var feelSheetSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
                    // The mode the finished Run was recorded in, kept beside its id: the sheet asks
                    // a treadmill Run how far it went (#231), and by the time it is on screen the
                    // Run is over and no longer has a mode to be asked for.
                    var feelSheetRunMode by rememberSaveable { mutableStateOf<String?>(null) }

                    // The course picked for the next Run, and which way round (#56). Above the
                    // NavHost, not inside the record screen, because the record screen is disposed
                    // whenever another one is on top of it, and [navigateHome] below clears the
                    // state saved for it as well — and checking a course in the Routes library
                    // before setting off on it is the very trip that would throw the pick away.
                    var routeChoice by rememberSaveable(stateSaver = RunRouteSaver) {
                        mutableStateOf<RunRoute?>(null)
                    }

                    val navController = rememberNavController()
                    // Moving forward stacks. The screen being left stays underneath the new one, so
                    // the phone's Back button and the arrow in each top bar both have somewhere to
                    // return to. Replacing the graph on every move instead — which is what this did
                    // before #412 — left exactly one screen alive, so Back had nothing to pop and
                    // closed the app from every page in the app.
                    val navigateTo: (String) -> Unit = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                    // One step back the way the runner actually came. Off the stack rather than to a
                    // named address, because most of these pages are reached from more than one
                    // place: a Run opened from a Record has to return to that Record, and naming
                    // History here would land the runner on a page they were never on.
                    val goBack: () -> Unit = { navController.popBackStack() }
                    // The stack as the closing rules read it (#476). Which pages come off when a
                    // thing is gone is decided in [closeEveryPageOf] and its neighbours; this only
                    // does the pops they ask for.
                    val pages = remember(navController) { NavControllerPageStack(navController) }
                    // Home with the stack behind it cleared: the record screen taking the app over,
                    // not a screen stacked on top of one. Only for the moves that mean "the app is
                    // back at the start" — a Run beginning, a strap picked. Back from Home leaves
                    // the app, which is what Back on a home screen means.
                    val navigateHome: () -> Unit = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }

                    val appContainer = remember { this@MainActivity.runningAppContainer() }
                    val settingsRepository = remember { appContainer.settingsRepository }
                    val userSettings by settingsRepository.userSettingsFlow.collectAsState(initial = UserSettings())
                    val coachPrescriptions by appContainer.coachPrescriptionRepository
                        .prescriptionsFlow.collectAsState(initial = CoachPrescriptions.NONE)

                    val database = remember { appContainer.database }
                    val sessionRepository = remember { appContainer.sessionRepository }
                    val historyViewModel: HistoryViewModel = viewModel(
                        factory = HistoryViewModelFactory(sessionRepository)
                    )
                    val sessionDetailViewModel: SessionDetailViewModel = viewModel(
                        factory = SessionDetailViewModelFactory(
                            sessionRepository,
                            appContainer.exportFileStore,
                            // The same library the Routes screen imports into (#55): a course kept
                            // off a Run is a Route like any other, and lands in the one list.
                            runRouteSaver = RunRouteSaver(
                                appContainer.database.routeDao(),
                                // The Run a course is traced off is the plainest Run on it, so it
                                // is remembered there and then (#420) — see the DAO for why the
                                // "unless it already names one" lives inside the write.
                                appContainer.database.sessionDao()::rememberRunAlongRoute,
                                // Both writes or neither — see RunRouteSaver.inTransaction. The
                                // line AppContainer already runs for a re-tally of history.
                                inTransaction = { block ->
                                    appContainer.database.withTransaction { block() }
                                },
                            ),
                            zoneChanges = appContainer.zoneChanges,
                            // The library, so a Run's Matched Runs card can call the ground by the
                            // runner's own name for it rather than "this route" (#74).
                            savedCourses = appContainer.savedCourseShapes,
                            // Watched, not read once: a refusal that was only ever the switch's
                            // doing must stop being a refusal the moment the runner moves the
                            // switch back (#76).
                            aiSummariesAllowed = settingsRepository.userSettingsFlow
                                .map { it.aiDataSharingEnabled && !it.testingModeEnabled }
                                .distinctUntilChanged(),
                        )
                    )
                    val backupViewModel: BackupViewModel = viewModel(
                        factory = BackupViewModelFactory(
                            this@MainActivity,
                            appContainer.archiver,
                            settingsRepository
                        )
                    )
                    val backingUp by backupViewModel.backingUp.collectAsState()
                    val backupOutcome by backupViewModel.lastOutcome.collectAsState()
                    val pickBackupFolder = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { treeUri ->
                        // Taken here, while the grant this Activity was handed is still alive:
                        // without it the monthly job would find the folder closed the first time it
                        // ran, months later.
                        if (treeUri != null) {
                            SafArchiveFolder.takePersistedAccess(this@MainActivity, treeUri)
                        }
                        backupViewModel.folderChosen(treeUri?.toString())
                    }

                    val restoreViewModel: RestoreViewModel = viewModel(
                        factory = RestoreViewModelFactory(
                            applicationContext,
                            database,
                            settingsRepository,
                        )
                    )
                    val restoreState by restoreViewModel.state.collectAsState()

                    // Held by the app, not by Settings: a download outlives the runner leaving the
                    // screen (#42).
                    val offlineMapState by appContainer.offlineMap.state.collectAsState()
                    // The tap that needed location finishes once it is granted, rather than asking
                    // the runner to tap again after the dialog.
                    //
                    // Fine and coarse asked together, as checkAndRequestPermissions does: from
                    // Android 12 a request for fine alone is ignored by the system, with no dialog.
                    // Either answer will do — "approximate" is plenty to centre 15 km of map on.
                    val askLocationForOfflineMap = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { answers ->
                        if (answers.values.any { it }) {
                            appContainer.offlineMap.download()
                        } else {
                            appContainer.offlineMap.locationRefused()
                        }
                    }
                    // OpenDocument rather than GetContent: it hands back a Uri this app may read
                    // for as long as it holds it, which is the whole reason a picked file works
                    // where the app's own Downloads copy no longer does after a Clear storage
                    // (#198) — the grant comes from the act of picking, so who owns the file on
                    // disk stops mattering.
                    val pickRestoreFile = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        restoreViewModel.fileChosen(uri)
                    }
                    // Armed and staged; the swap itself happens at the next launch, before Room
                    // opens anything (PendingRestore). Relaunching is how the app gets to that
                    // moment — the database is open and being read right now, and replacing the
                    // file underneath live readers is the one way this feature could destroy the
                    // history it exists to rescue.
                    LaunchedEffect(restoreState) {
                        if (restoreState is RestoreUiState.Restarting) restartForRestore()
                    }

                    // Scoped to the Activity rather than to the Routes destination: an "Open with"
                    // has a file to hand over before that screen has been navigated to, and the
                    // import must not be cancelled by the runner walking away from the library.
                    val routesViewModel: RoutesViewModel = viewModel(
                        factory = RoutesViewModelFactory(
                            appContainer.routeLibrary,
                            appContainer.routeImporter,
                            zoneChanges = appContainer.zoneChanges,
                        )
                    )
                    // Scoped to the Activity rather than to a destination, because saving a
                    // Segment is the last thing the creation screen does before it is popped: work
                    // launched from that screen's own scope would be cancelled by the very
                    // navigation that follows it.
                    val segmentsViewModel: SegmentsViewModel = viewModel(
                        factory = SegmentsViewModelFactory(
                            segmentDao = appContainer.database.segmentDao(),
                            segmentEffortDao = appContainer.database.segmentEffortDao(),
                            // On the container's scope, not this one: the creation screen is popped
                            // the instant a Segment is saved (#70).
                            onSegmentSaved = { appContainer.timeSegmentAgainstHistory(it) },
                            // So a Segment's dated efforts follow the phone across a zone change,
                            // the way the Progress screen's readers do (#320, #343).
                            zoneChanges = appContainer.zoneChanges,
                        )
                    )
                    // "*/*", like the restore picker, and for the same reason: a `.gpx` in Downloads
                    // is announced as `application/octet-stream` as often as it is by its real type,
                    // and a filter that greys out the runner's own file is worse than a broad one.
                    val pickRouteFile = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        routesViewModel.fileChosen(uri)
                    }
                    // A file another app opened this one with lands in exactly the flow the picker
                    // does — same importer, same refusals, same library — with the screen brought
                    // up in front of it so the runner sees where their route went.
                    LaunchedEffect(pendingRouteFile) {
                        pendingRouteFile?.let { uri ->
                            pendingRouteFile = null
                            navigateTo(Routes.routeLibrary())
                            routesViewModel.fileChosen(uri)
                        }
                    }

                    val exportShareReady by sessionDetailViewModel.exportShareReady.collectAsState()
                    val exportShareFailed by sessionDetailViewModel.exportShareFailed.collectAsState()
                    // What became of a Run kept as a course (#55), named by Run for the same reason.
                    val saveAsRouteMessage by sessionDetailViewModel.saveAsRouteMessage.collectAsState()
                    // Which Run's summary is being written, and which one's ask came back with
                    // nothing (#76). Named by Run for the reason the export results are: an answer
                    // landing after the runner has moved on must not put a spinner over another Run.
                    val summaryWriting by sessionDetailViewModel.summaryWriting.collectAsState()
                    val summaryFailed by sessionDetailViewModel.summaryFailed.collectAsState()
                    val summaryRefused by sessionDetailViewModel.summaryRefused.collectAsState()
                    // Whether the switch would let any Run be written about right now. Not the same
                    // question as "was this Run refused": a Run written about before sharing was
                    // switched off is never asked again, so it holds no refusal — only this says
                    // that its "write it again" could now do nothing but be turned down (#76).
                    val summariesAllowed by sessionDetailViewModel.summariesAllowed.collectAsState()
                    // Which Runs are on their way out (#414). Named by Run for the reason the
                    // export results and the summaries are: the delete outlives the page that asked
                    // for it, and a Run going must not close the doors on whichever Run the runner
                    // is looking at now.
                    val deletePending by sessionDetailViewModel.deletePending.collectAsState()
                    val selectedSessionIds by historyViewModel.selectedSessionIds.collectAsState()
                    // Through the view model rather than straight off the DAO: a History row is the
                    // run plus what it won and where it went (#51), and only the view model has
                    // those.
                    val historyRows by historyViewModel.rows.collectAsState()

                    // The library the pre-run picker offers (#56). Off the same Activity-scoped
                    // view model the Routes screen watches, so an import made a moment ago is
                    // already in the picker.
                    val routeLibrary by routesViewModel.routes.collectAsState()

                    val forceMainSignal by forceMainToken
                    LaunchedEffect(forceMainSignal) {
                        if (forceMainSignal > 0) {
                            // The record screen taking the app over, so whatever the runner had
                            // stacked up goes with it: this fires because the app has been aimed at
                            // Home from outside, and Back must not walk back into the pile behind.
                            // The History selection goes with the pile. It is the one piece of
                            // screen state that outlives its screen, and this is the one way out of
                            // History the screen's own Back cannot guard (#416) — so History must
                            // not be re-entered later with its Delete button still armed.
                            historyViewModel.clearSelection()
                            navigateHome()
                        }
                    }

                    // Which Runs have gone and are still waiting for their page to come off the
                    // stack (#414). Read as state and acknowledged, not listened for once: the
                    // delete can land in the gap between this activity being torn down and its
                    // replacement listening again, and a landing nobody heard is a page left on
                    // "Deleting this run…" for ever, because the mark on a landed delete never
                    // comes off.
                    val deleteCompleted by sessionDetailViewModel.deleteCompleted.collectAsState()
                    LaunchedEffect(deleteCompleted) {
                        // Not gated on the page the runner is looking at the way an export is — an
                        // export opens a chooser *over* the current screen, while this only removes
                        // pages about a Run that is gone, wherever they sit. See [landDeletedRuns].
                        pages.landDeletedRuns(deleteCompleted) { deletedSessionId ->
                            sessionDetailViewModel.deleteCompletedHandled(deletedSessionId)
                        }
                    }

                    // The picked course's shape, for the pre-run card (#496). Drawn for that one
                    // course rather than by drawing the whole library, which is work only a runner
                    // who opens their routes should pay for (#59).
                    val pickedRouteThumbnail by produceState<com.example.runningapp.analysis.RouteThumbnail?>(
                        initialValue = null,
                        key1 = routeChoice?.routeId
                    ) {
                        value = null
                        routeChoice?.routeId?.let { id -> value = routesViewModel.thumbnailOf(id) }
                    }
                    // A pick made on a Routes page goes back to the record screen it was made for,
                    // past every Routes page the runner walked through to make it (#496).
                    val pickRoute: (Long?) -> Unit = { routeId ->
                        routeChoice = runRouteAfterPick(routeChoice, routeId)
                        navController.popBackStack(Routes.MAIN, inclusive = false)
                    }

                    NavHost(navController = navController, startDestination = Routes.MAIN) {
                        composable(Routes.MAIN) {
                            MainScreen(
                                hrService = hrService,
                                userSettings = userSettings,
                                coachPrescriptions = coachPrescriptions,
                                sessionRepository = sessionRepository,
                                zoneChanges = appContainer.zoneChanges,
                                onRequestPermissions = { checkAndRequestPermissions() },
                                routes = routeLibrary,
                                routeChoice = routeChoice,
                                onRouteChoiceChange = { routeChoice = it },
                                pickedRouteThumbnail = pickedRouteThumbnail,
                                onChooseRoute = { targetMeters, targetIsFixed ->
                                    navigateTo(Routes.routePicker(targetMeters, targetIsFixed))
                                },
                                onStartRun = { request ->
                                    // An Outdoor run without location permission would silently
                                    // record 0 km (LocationTracker just logs and returns): ask
                                    // first instead of starting blind. The tap is parked in
                                    // pendingStartRun and the run starts from the permission
                                    // callback once the dialog resolves — START itself never
                                    // gates on GPS (#110), only on having asked.
                                    val needsLocation = request.runMode == RunMode.OUTDOOR &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                                        ) != PackageManager.PERMISSION_GRANTED
                                    if (needsLocation) {
                                        pendingStartRun = request
                                        checkAndRequestPermissions()
                                    } else {
                                        sendStartRun(request)
                                    }
                                },
                                onRetryStrap = {
                                    // Re-acquire, don't scan: a bare scan never auto-connects
                                    // (results only fill the Discovered list in Manage Devices),
                                    // so FORCE_SCAN here couldn't bring the strap back. The
                                    // no-extra START_FOREGROUND path connects the saved strap
                                    // directly, falling back to a scan only when none is saved.
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        this.action = HrForegroundService.ACTION_START_FOREGROUND
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onTogglePause = {
                                    hrService?.togglePause()
                                },
                                onStopSession = {
                                    hrService?.hrState?.value?.let {
                                        val runRowId = it.activeDbSessionId
                                        if (runRowId != null &&
                                            it.lifecycle.isLive
                                        ) {
                                            feelSheetSessionId = runRowId
                                            // And the Stage waits for what this sheet is about to
                                            // be told (#297). Said here rather than where the sheet
                                            // is drawn, and before the stop below is issued, so the
                                            // finalization that follows can only ever find the
                                            // sheet already claimed: a Walk marked on this sheet
                                            // must reach the graduation rule, and the rule is asked
                                            // seconds after STOP.
                                            sessionRepository.finishSheetOpened(runRowId)
                                            // The Run's own pinned mode (HrState.activeRunMode) and
                                            // nothing else. Falling back to the live setting would
                                            // let an outdoor Run be asked for a distance it cannot
                                            // be told, and the repository refuses one with only a
                                            // log — so the runner would type a number and watch it
                                            // vanish. Unknown asks nothing; the Run's own page is
                                            // still there.
                                            feelSheetRunMode = it.activeRunMode
                                        }
                                    }
                                     val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_STOP_FOREGROUND
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onConnectToDevice = { address, skipPlan ->
                                    Log.d("MainActivity", "User tapped device: $address")
                                    // The service's ACTION_START_FOREGROUND handler reads EXTRA_SKIP_PLAN
                                    // and then connects via the override address, so the skip choice is
                                    // always applied before session setup. Do NOT also call
                                    // hrService?.connectToDevice() here: when the service is already bound
                                    // that direct connect can reach startNewDatabaseSession() before the
                                    // intent sets skipPlanForToday, attaching the plan the user skipped.
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                        putExtra(HrForegroundService.EXTRA_MAKE_ACTIVE, true)
                                        putExtra(HrForegroundService.EXTRA_SKIP_PLAN, skipPlan)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onTestCue = {
                                    hrService?.enqueueCue(
                                        "Target heart rate reached. Keep it up!",
                                        CuePriority.INFORMATION,
                                    )
                                },
                                onOpenSettings = {
                                    navigateTo(Routes.SETTINGS)
                                },
                                onOpenHistory = {
                                    navigateTo(Routes.HISTORY)
                                },
                                onOpenProgress = {
                                    navigateTo(Routes.PROGRESS)
                                },
                                onOpenManageDevices = {
                                    navigateTo(Routes.MANAGE_DEVICES)
                                },
                                onOpenTrainingPlan = {
                                    navigateTo(Routes.TRAINING_PLAN)
                                },
                                onOpenRoutes = {
                                    navigateTo(Routes.routeLibrary())
                                },
                                onOpenSegments = {
                                    navigateTo(Routes.SEGMENTS)
                                },
                                onOpenFullScreenMap = {
                                    navigateTo(Routes.MAP)
                                },
                                onToggleSimulation = { simulationEnabled, request ->
                                    val simulationIntent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_SET_SIMULATION
                                        putExtra(HrForegroundService.EXTRA_SIMULATION_ENABLED, simulationEnabled)
                                        // Turning simulation on starts a Run, so it carries every
                                        // choice START carries, by the same one door (#174, #56).
                                        putRunChoices(request)
                                    }
                                    // Started from the tap, not from the settings write's coroutine. The
                                    // write is suspend and lands on Dispatchers.IO, so starting after it
                                    // put a startForegroundService() an unbounded time after the gesture
                                    // that justified it — background it in that window and the start is
                                    // background-initiated: refused outright on Android 12+, and on the
                                    // clock wherever it is not. Nothing is lost by going first, because
                                    // the handler reads the toggle off EXTRA_SIMULATION_ENABLED rather
                                    // than out of settings, exactly as START does with the run mode.
                                    ContextCompat.startForegroundService(this@MainActivity, simulationIntent)
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.setSimulationEnabled(simulationEnabled)
                                    }
                                },
                                onRunModeChange = { runMode ->
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.setRunMode(runMode)
                                    }
                                }
                            )
                        }
                        composable(Routes.MANAGE_DEVICES) {
                            ManageDevicesScreen(
                                settings = userSettings,
                                acquisition = serviceState?.value?.acquisition ?: AcquisitionState(),
                                scannedDevices = serviceState?.value?.scannedDevices ?: emptyList(),
                                isRunActive = serviceState?.value?.lifecycle?.isLive == true,
                                onSetActive = { address ->
                                    scope.launch {
                                        settingsRepository.setActiveDevice(address)
                                    }
                                },
                                onRemove = { address ->
                                    // Release the live connection too when it's this strap:
                                    // otherwise the retry loop keeps chasing it and the verify
                                    // path re-saves (and re-activates) the device just forgotten.
                                    hrService?.forgetDevice(address)
                                    scope.launch {
                                        settingsRepository.removeDevice(address)
                                    }
                                },
                                onConnect = { address ->
                                    Log.d("MainActivity", "User tapped device in ManageDevices: $address")
                                    // Connect-only under #110: acquire/save the strap and return to the
                                    // record screen, where START owns the run and the plan-skip choice.
                                    // Deliberately no EXTRA_SKIP_PLAN — this action must not touch the
                                    // service's pending skip state that the eventual START will set.
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                        putExtra(HrForegroundService.EXTRA_MAKE_ACTIVE, true)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                    navigateHome()
                                },
                                onScan = {
                                    // A fresh scan so a first (or replacement) strap can be discovered
                                    // and tapped to pair; the discovered list renders on this screen.
                                    // Without BLUETOOTH_SCAN (API 31+, e.g. a fresh install) the
                                    // service would go foreground only to dead-end in
                                    // startScanning()'s permission check — ask first and park the
                                    // scan; it fires from the permission callback once granted.
                                    val needsScanPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity, Manifest.permission.BLUETOOTH_SCAN
                                        ) != PackageManager.PERMISSION_GRANTED
                                    if (needsScanPermission) {
                                        pendingScan = true
                                        checkAndRequestPermissions()
                                    } else {
                                        sendForceScan()
                                    }
                                },
                                onBack = goBack
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                settings = userSettings,
                                strapSummary = strapRowSummary(
                                    userSettings,
                                    serviceState?.value?.connectionStatus ?: "Disconnected"
                                ),
                                // Both heart rates go through the repository rather than DataStore
                                // directly: the first deliberate Max HR set recomputes all history
                                // (#112) and every resting-HR statement re-bands it (#172), and
                                // neither may be something a surface can forget to do.
                                //
                                // One call carries both, so a pair stated together reaches the
                                // door as a single statement — and the container queues the
                                // statements so separate ones arrive in the order they were made.
                                // Launched independently they raced for the repository's lock, and
                                // the same gestures left different history depending on which won.
                                onHrCommit = appContainer::stateHeartRates,
                                onTargetZoneChange = { zone ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setTargetZone(zone) }
                                },
                                onCoachingEnabledChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setCoachingEnabled(enabled) }
                                },
                                onSplitAnnouncementsChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setSplitAnnouncementsEnabled(enabled) }
                                },
                                onTurnaroundCueChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setTurnaroundCueEnabled(enabled) }
                                },
                                onAutoPauseChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setAutoPauseEnabled(enabled) }
                                },
                                onAiDataSharingChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setAiDataSharingEnabled(enabled) }
                                },
                                onTestingModeChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setTestingModeEnabled(enabled) }
                                },
                                onManageStrap = { navigateTo(Routes.MANAGE_DEVICES) },
                                onPickBackupFolder = { thenBackUp ->
                                    backupViewModel.folderPickerOpened(thenBackUp)
                                    // null starts the picker at the system's own default rather
                                    // than anywhere this app chooses for them.
                                    pickBackupFolder.launch(null)
                                },
                                onBackUpNow = backupViewModel::backUpNow,
                                // Asked of the system rather than read off the stored Uri: a folder
                                // restored from another phone keeps its address and loses its
                                // permission, and a backup section that looks set up and cannot
                                // write is worse than one that asks for a folder.
                                backupFolderUri = SafArchiveFolder.grantedFolder(
                                    this@MainActivity,
                                    userSettings.backupFolderUri
                                )?.toString(),
                                backingUp = backingUp,
                                backupResult = backupResultMessage(backupOutcome),
                                onPickRestoreFile = {
                                    // Every type, not just the two this app writes. A backup that
                                    // came back through Drive or a chat app arrives with whatever
                                    // type that app decided on, and a picker that hides the file
                                    // the runner is looking for would be its own bug — the file is
                                    // checked by its contents once picked (RestoreFileKind).
                                    pickRestoreFile.launch(arrayOf("*/*"))
                                },
                                restoreState = restoreState,
                                onConfirmRestore = restoreViewModel::confirm,
                                onDismissRestore = restoreViewModel::dismiss,
                                runInProgress = serviceState?.value?.let {
                                    it.lifecycle != RunLifecycle.IDLE &&
                                        it.lifecycle != RunLifecycle.STOPPED
                                } ?: false,
                                offlineMapState = offlineMapState,
                                onDownloadOfflineMap = {
                                    val locationPermissions = arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                    val hasLocation = locationPermissions.any {
                                        ContextCompat.checkSelfPermission(this@MainActivity, it) ==
                                            PackageManager.PERMISSION_GRANTED
                                    }
                                    if (hasLocation) {
                                        appContainer.offlineMap.download()
                                    } else {
                                        askLocationForOfflineMap.launch(locationPermissions)
                                    }
                                },
                                onBack = {
                                    // The result belonged to the visit that asked for it; coming
                                    // back to Settings later should read the last-backup time, not
                                    // an announcement about a backup made some time ago.
                                    backupViewModel.resultShown()
                                    goBack()
                                }
                            )
                        }
                        composable(Routes.HISTORY) {
                            // The routes are worked out from here on, not from launch: the view
                            // model outlives this screen, and twenty tracks read and simplified is
                            // work nobody asked for until History is on screen (#51).
                            LaunchedEffect(Unit) { historyViewModel.drawRoutesWhileHistoryIsOpen() }
                            HistoryScreen(
                                rows = historyRows,
                                selectedSessionIds = selectedSessionIds,
                                onToggleSelection = { id -> historyViewModel.toggleSelection(id) },
                                onClearSelection = { historyViewModel.clearSelection() },
                                onDeleteSelected = { historyViewModel.deleteSelectedSessions() },
                                onSessionClick = { id ->
                                    navigateTo(Routes.sessionDetail(id))
                                },
                                onBack = goBack
                            )
                        }
                        composable(
                            route = Routes.SESSION_DETAIL,
                            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong(Routes.ARG_SESSION_ID)

                            val sessionSamples by produceState<List<com.example.runningapp.data.HrSample>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.sampleDao().getSamplesForSession(id).collect { value = it }
                                }
                            }
                            val sessionIntervalStats by produceState<List<com.example.runningapp.data.RunWalkIntervalStat>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.runWalkIntervalStatDao().getIntervalStatsForSessionFlow(id).collect { value = it }
                                }
                            }
                            val selectedSession by produceState<com.example.runningapp.data.RunnerSession?>(initialValue = null, key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.sessionDao().getSessionByIdFlow(id).collect { value = it }
                                }
                            }

                            // A run that recorded a route — the only kind GPX can describe (#84).
                            val hasTrack by produceState(initialValue = false, key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionRepository.hasTrackFlow(id).collect { value = it }
                                }
                            }

                            // The route the splits and the elevation line are measured off (#45),
                            // through the same accuracy gate as the map and the GPX export so all
                            // three are describing the same run.
                            val sessionTrack by produceState<List<com.example.runningapp.data.TrackPoint>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionRepository.getTrackPointsForMapFlow(id).collect { value = it }
                                }
                            }

                            // The medals this run won (#49), watched rather than read once: a run
                            // opened straight off the finish line may still be being scored.
                            val sessionAchievements by produceState<List<com.example.runningapp.data.Achievement>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.achievementDao().getAchievementsForSessionFlow(id).collect { value = it }
                                }
                            }

                            // The named ground this run went over, and where it placed there (#71).
                            // Watched for the reason the medals are, and one more of its own: a
                            // Segment cut this morning is still being walked against history on a
                            // scope that outlives this page, and its efforts land one run at a time.
                            val sessionSegmentEfforts by produceState<List<com.example.runningapp.ui.RunSegmentEffortUi>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionDetailViewModel.segmentEfforts(id).collect { value = it }
                                }
                            }

                            // The other Runs over this same route (#73). Watched for the reason the
                            // Segments are, and one more of its own: the shapes of a whole history
                            // are still being taken on the first launch after this shipped, so the
                            // number on the card fills in as that pass lands.
                            val sessionMatchedRuns by produceState<com.example.runningapp.ui.MatchedRunsUi?>(initialValue = null, key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionDetailViewModel.matchedRuns(id).collect { value = it }
                                }
                            }

                            // What this run has been told it holds (#282), watched for the same
                            // reason the medals are: stating one re-scores, and the card and the
                            // book underneath it have to arrive at the new answer together.
                            val sessionStatedEfforts by produceState<List<com.example.runningapp.data.StatedBestEffort>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionDetailViewModel.statedBestEfforts(id).collect { value = it }
                                }
                            }

                            // --- The Run Summary (#76) ---
                            //
                            // Watched, because the page is open while the words are being written:
                            // the card is a spinner, the model answers, the row lands, and the card
                            // fills in without the runner touching anything.
                            val sessionSummaryRow by produceState<RunSummaryRow?>(initialValue = null, key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionDetailViewModel.runSummary(id).collect { value = it }
                                }
                            }
                            // Whether everything the summary would describe has been measured yet.
                            // A Run opened straight off the finish line is still being scored, still
                            // being walked against the Segments and still having its shape taken —
                            // and these words are written once and kept, so writing them out of a
                            // half-measured Run would say "no records" about a Run that took gold a
                            // second later, for ever.
                            val summaryFactsSettled by produceState(initialValue = false, key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionDetailViewModel.runSummaryFactsSettled(id).collect { value = it }
                                }
                            }
                            // The one ask this feature makes on its own, and the only place it is
                            // made: a Run nobody opens is never sent anywhere. Held until the facts
                            // have settled.
                            //
                            // Whether anything is written already is *not* asked here, and
                            // deliberately: the watched row above begins as null, which is the same
                            // null it would hold for a Run nobody has written about, so a page
                            // opened for the second time cannot be told apart from a first open
                            // until the store answers. The ask itself reads the store and waits for
                            // that answer, so a second open still costs nothing and reaches nothing.
                            //
                            // What the model is told is gathered inside the ask rather than passed
                            // in from here, for the same kind of reason: the cards below are drawn
                            // from watched reads that begin empty, and a prompt built off those
                            // could be built a frame before the medals arrive.
                            LaunchedEffect(sessionId, summaryFactsSettled) {
                                val id = sessionId ?: return@LaunchedEffect
                                if (!summaryFactsSettled) return@LaunchedEffect
                                sessionDetailViewModel.requestRunSummary(id)
                            }
                            val summaryUi = sessionId?.let { id ->
                                RunSummaryUi(
                                    text = sessionSummaryRow?.text,
                                    isWriting = id in summaryWriting,
                                    failed = id in summaryFailed,
                                    refused = id in summaryRefused,
                                    // The same gate the ask above is behind, because "write it
                                    // again" writes words that are kept just as long (#76).
                                    factsSettled = summaryFactsSettled,
                                    sharingAllowed = summariesAllowed,
                                )
                            }

                            // Inside this destination, and gated on the run that asked: an export is
                            // slow enough that the runner can be somewhere else by the time it
                            // lands, and a chooser opening over another screen interrupts whatever
                            // they went there to do. Keyed on the file, so one that arrived while
                            // this screen was being recreated still opens as soon as it is
                            // listening again.
                            LaunchedEffect(exportShareReady, sessionId) {
                                exportShareReady?.takeIf { it.sessionId == sessionId }?.let { file ->
                                    startActivity(exportShareChooser(file))
                                    sessionDetailViewModel.exportShareHandled()
                                }
                            }

                            // What this run can be written as (#218). FIT needs nothing of the run
                            // but that it finished: a Run with neither Strap nor GPS still states a
                            // Duration and a Stated Distance, and a file saying so is the case this
                            // export was added for (#329). GPX needs fixes to hang its trackpoints
                            // on, so that same Run gets none.
                            val shareableFormats = remember(hasTrack, selectedSession) {
                                buildList {
                                    if (selectedSession?.isFinished() == true) add(ExportFormat.FIT)
                                    if (hasTrack) add(ExportFormat.GPX)
                                }
                            }

                            // Whether this Run is on its way out — asked once, because the page's
                            // own state and where Back goes are the same question (#414).
                            val runIsGoing = sessionId != null && sessionId in deletePending

                            SessionDetailScreen(
                                session = selectedSession,
                                samples = sessionSamples,
                                intervalStats = sessionIntervalStats,
                                trackPoints = sessionTrack,
                                achievements = sessionAchievements,
                                // Only reached by a run carrying no Reserve of its own (#228). The
                                // pair history is banded against rather than the one in force: a
                                // future-only Max HR correction must not recolour a run's route
                                // away from the zone bars further down its own page.
                                fallbackHrProfile = userSettings.historyHrProfile,
                                onDeleteSession = { id ->
                                    sessionDetailViewModel.deleteSession(id)
                                },
                                // Not [goBack] while this Run is going: leaving the page for a Run
                                // on its way out leaves *every* page of that Run, by the very call
                                // the delete landing will make. One step back would instead uncover
                                // whatever sits between two copies of this Run — the group of Runs
                                // matched to it, which is not a page about this Run and so stays
                                // live — and anything opened from there would be swept away by the
                                // completion pop. See [leaveRunPage].
                                onBack = { pages.leaveRunPage(sessionId, runIsGoing) },
                                onStateDistance = { id, distanceKm ->
                                    sessionDetailViewModel.stateDistance(id, distanceKm)
                                },
                                onSaveFeelFeedback = { id, effort, note, isWalk ->
                                    sessionDetailViewModel.saveFeelFeedback(id, effort, note, isWalk)
                                },
                                statedBestEfforts = sessionStatedEfforts,
                                onStateBestEffort = { id, type, seconds ->
                                    sessionDetailViewModel.stateBestEffort(id, type, seconds)
                                },
                                shareableFormats = shareableFormats,
                                onShareRun = { id, format -> sessionDetailViewModel.shareRun(id, format) },
                                shareFailed = exportShareFailed != null && exportShareFailed == sessionId,
                                onShareFailureShown = { sessionDetailViewModel.exportShareFailureShown() },
                                // Offered only where the recording holds a route, which is the same
                                // gate GPX export is behind: there is nothing to cut a Segment out
                                // of without one (#69).
                                onCreateSegment = if (hasTrack) {
                                    { id -> navigateTo(Routes.segmentCreate(id)) }
                                } else {
                                    null
                                },
                                // Behind the same gate, for the same reason: there is no course to
                                // keep without a recorded track (#55).
                                onSaveAsRoute = if (hasTrack) {
                                    { id -> sessionDetailViewModel.saveAsRoute(id) }
                                } else {
                                    null
                                },
                                saveAsRouteMessage = saveAsRouteMessage
                                    ?.takeIf { it.sessionId == sessionId }?.text,
                                onSaveAsRouteMessageShown = {
                                    sessionDetailViewModel.saveAsRouteMessageShown()
                                },
                                segmentEfforts = sessionSegmentEfforts,
                                onOpenSegment = { id -> navigateTo(Routes.segmentDetail(id)) },
                                matchedRuns = sessionMatchedRuns,
                                onOpenMatchedRuns = sessionId?.let { id ->
                                    { navigateTo(Routes.matchedRuns(id)) }
                                },
                                runSummary = summaryUi,
                                onRegenerateRunSummary = sessionId?.let { id ->
                                    { sessionDetailViewModel.regenerateRunSummary(id) }
                                },
                                deleteInProgress = runIsGoing,
                            )
                        }
                        composable(Routes.TRAINING_PLAN) {
                            // Which Stage the runner is actually in, asked of the one place that
                            // walk is made. The screen marks a card ACTIVE by this id and the line
                            // below is about that same Stage, so both have to be the same answer:
                            // resolving it twice is two fallbacks that agree until one of them
                            // changes.
                            val activeStage = TrainingPlanProvider.resolveActiveStage(
                                userSettings.activePlanId,
                                userSettings.activeStageId
                            )
                            // The bar that Stage asks for, and the best the runner has ever been at
                            // that distance (#293). Read straight off the record book, so it
                            // re-answers itself when a Run is deleted, marked a Walk or told what
                            // the treadmill console said.
                            val requirement = activeStage?.bestEffortRequirement
                            val barStanding by produceState<BarStanding>(
                                initialValue = BarStanding.Silent,
                                sessionRepository,
                                requirement
                            ) {
                                // THE RULE, for every read on this screen that is keyed to the
                                // Stage: clear before reading. `produceState` applies its
                                // `initialValue` once, when the state is first remembered — a key
                                // change restarts the producer and KEEPS the last value. So a Stage
                                // that changes with the screen still open (another plan activated,
                                // a graduation landing) would leave the Stage just left answering
                                // for the Stage just entered, until the read returns. The window is
                                // short and what it shows is a bar the runner has not been measured
                                // against, which is the one thing this card may never say.
                                //
                                // `Silent` is what clearing means here (#446): the standing this
                                // screen has not read yet is the app having nothing it may say
                                // about the bar, which is the same answer it gives for a Stage
                                // carrying no bar at all. Neither line the card can draw is printed
                                // from it, so the window shows no bar rather than the wrong one.
                                value = BarStanding.Silent
                                sessionRepository.barStandingFlow(requirement)
                                    .collect { value = it }
                            }
                            // What the app has already counted under that Stage (#445), read once
                            // each time this screen is opened, and not watched afterwards. It is a
                            // statement about stored Runs, and the count is only ever moved by a
                            // Run finishing — which a runner does not do with the plan screen in
                            // front of them, and which the rescue pass can only do to a Run whose
                            // sheet is long since answered. So a stale figure needs the screen to
                            // have been left open across a whole Run, and leaving the screen
                            // disposes this and re-reads it. The same bargain the line above makes
                            // with today's date.
                            //
                            // It counts the Stage a Run finishing here would be STAMPED with, and
                            // that is not a second resolution to keep in step: `activeStageId`
                            // arrives already resolved, once, on the way out of storage
                            // ([activePlanAndStage], #381), and `HrForegroundService.pinRunConfig`
                            // writes `ranUnderStageId` from that same resolved setting (#234). The
                            // unrecognised-Stage case takes its Plan down with it there too, so
                            // this card has no Stage to draw rather than a Stage nothing stamps.
                            // Resolving again above is idempotent, not a second fallback.
                            val stageTraining by produceState<StageTrainingSummary?>(
                                initialValue = null,
                                sessionRepository,
                                activeStage
                            ) {
                                // Cleared before the read for the reason stated above: a retained
                                // value here would put the last Stage's weeks and run count on the
                                // card of the Stage just entered.
                                value = null
                                value = activeStage?.let { stage ->
                                    stageTrainingSummaryOf(
                                        record = sessionRepository.stageTrainingRecord(stage.id),
                                        // The Stage's own bar, where its bar names weeks (#445);
                                        // silent where its Workouts can never fill the count (#452).
                                        stage = stage,
                                    )
                                }
                            }
                            TrainingPlanScreen(
                                activePlanId = userSettings.activePlanId,
                                activeStageId = activeStage?.id,
                                // The day is read here rather than waited for the way the Test-due
                                // prompt waits for it (#292): nothing about this line falls due or
                                // is held, and the only thing today's date decides is whether the
                                // Run's year is printed alongside its day. A screen left open
                                // across New Year re-reads it the next time it is opened, which is
                                // the whole of what is owed.
                                alreadyBeatenLine = requirement?.let {
                                    alreadyBeatenLine(
                                        requirement = it,
                                        best = barStanding.bestOrNull,
                                        today = LocalDate.now(),
                                        zone = ZoneId.systemDefault()
                                    )
                                },
                                stageTraining = stageTraining,
                                // The other answer to the same comparison, off the same best
                                // effort (#446), so the card cannot both congratulate and measure.
                                barShortfallLine = requirement?.let {
                                    barShortfallLine(requirement = it, standing = barStanding)
                                },
                                // Read straight off the settings, which is the only place a
                                // finished plan is recorded (#294) — nothing here measures or
                                // infers it.
                                planCompletion = userSettings.planCompletion,
                                onActivatePlan = { planId, stageId ->
                                    scope.launch {
                                        settingsRepository.setActivePlan(planId, stageId)
                                    }
                                },
                                // Straight to the one write that holds the rule (#235). Nothing is
                                // decided here: which Stages may be gone back to is the screen's
                                // reading of where the runner stands, and what a move takes with it
                                // is [SettingsRepository.moveBackToStage]'s.
                                onMoveBackToStage = { planId, stageId, stageTitle ->
                                    scope.launch {
                                        settingsRepository.moveBackToStage(planId, stageId, stageTitle)
                                    }
                                },
                                onBack = goBack
                            )
                        }
                        composable(Routes.PROGRESS) {
                            // Scoped to the screen rather than to the Activity, unlike History's:
                            // building the curves reads every scored Run the phone holds, and that
                            // is not work a launch should do for a screen nobody has opened (#63).
                            val progressViewModel: ProgressViewModel = viewModel(
                                factory = ProgressViewModelFactory(
                                    sessionRepository,
                                    settingsRepository,
                                    // The same door the settings screen's fields go through, so a
                                    // Max HR stated here queues behind anything stated there (#172).
                                    appContainer::stateHeartRates,
                                    appContainer.database.goalDao(),
                                    // So the charts redraw when the runner changes zone and not
                                    // only when history moves (#320).
                                    appContainer.zoneChanges,
                                )
                            )
                            val progressState by progressViewModel.state.collectAsState()
                            // Its own view model, scoped to this screen like the curves': the
                            // records are a watched query rather than arithmetic over history, so
                            // the two arrive by different routes and neither waits on the other
                            // (#75).
                            val recordsViewModel: RecordsViewModel = viewModel(
                                factory = RecordsViewModelFactory(
                                    sessionRepository,
                                    appContainer.zoneChanges,
                                )
                            )
                            val recordsGrid by recordsViewModel.grid.collectAsState()
                            ProgressScreen(
                                state = progressState,
                                records = recordsGrid,
                                onOpenRecord = { navigateTo(Routes.recordDetail(it)) },
                                onRangeChosen = { progressViewModel.rangeChosen(it) },
                                onMeasureChosen = { progressViewModel.measureChosen(it) },
                                onMaxHrConfirmed = { progressViewModel.maxHrConfirmed(it) },
                                onMaxHrCardDismissed = { progressViewModel.maxHrCardDismissed() },
                                onGoalSet = { period, metric, target ->
                                    progressViewModel.goalSet(period, metric, target)
                                },
                                onGoalRemoved = { goal -> progressViewModel.goalRemoved(goal) },
                                onBack = goBack
                            )
                        }
                        composable(
                            route = Routes.RECORD_DETAIL,
                            arguments = listOf(navArgument(Routes.ARG_RECORD_TYPE) { type = NavType.StringType })
                        ) { backStackEntry ->
                            // The Record the address names, or nothing at all where it names none:
                            // a [RecordType] is persisted by name, so an address that no longer
                            // matches one is an address written by an older app for a Record this
                            // one does not have. Better to go back to the grid than to draw a page
                            // for a Record nobody can name (#75).
                            val recordType = backStackEntry.arguments
                                ?.getString(Routes.ARG_RECORD_TYPE)
                                ?.let { name -> RecordType.entries.firstOrNull { it.name == name } }
                            val recordsViewModel: RecordsViewModel = viewModel(
                                factory = RecordsViewModelFactory(
                                    sessionRepository,
                                    appContainer.zoneChanges,
                                )
                            )
                            LaunchedEffect(recordType) {
                                // Spelled from the argument this entry was given rather than from
                                // [recordType], which is null here by definition: that argument is
                                // what put this entry on the stack, so it is what takes it off
                                // again. See [closeEveryPageOf].
                                val unknownRecord = backStackEntry.arguments
                                    ?.getString(Routes.ARG_RECORD_TYPE)
                                if (recordType == null && unknownRecord != null) {
                                    pages.closeEveryPageOf(Routes.recordDetail(unknownRecord))
                                }
                            }
                            recordType?.let { type ->
                                // Watched, like the grid that led here: a Run finishing, a
                                // treadmill time stated or a Run deleted moves this list while it
                                // is open.
                                // Opened as "nothing has come back yet" rather than as an empty
                                // Record (#75): the page is drawn the instant the cell is tapped
                                // and the first read of the efforts lands frames later, so an
                                // initial value of no efforts would tell a runner who has covered
                                // this distance a hundred times that they never had, right after
                                // they tapped the number saying they had. See
                                // [recordDetailNotReadYet].
                                val detail by produceState(
                                    initialValue = recordDetailNotReadYet(type),
                                    key1 = type
                                ) {
                                    recordsViewModel.detail(type).collect { value = it }
                                }
                                RecordDetailScreen(
                                    detail = detail,
                                    onOpenRun = { navigateTo(Routes.sessionDetail(it)) },
                                    onBack = goBack,
                                )
                            }
                        }
                        composable(
                            route = Routes.ROUTE_LIBRARY,
                            arguments = listOf(
                                navArgument(Routes.ARG_PICK) { type = NavType.BoolType; defaultValue = false },
                                navArgument(Routes.ARG_TARGET_METERS) { type = NavType.FloatType; defaultValue = -1f },
                                navArgument(Routes.ARG_TARGET_FIXED) { type = NavType.BoolType; defaultValue = false },
                            )
                        ) { backStackEntry ->
                            val arguments = backStackEntry.arguments
                            val picking = arguments?.getBoolean(Routes.ARG_PICK) == true
                            val targetMeters = arguments?.getFloat(Routes.ARG_TARGET_METERS)
                                ?.takeIf { it >= 0f }?.toDouble()
                            val targetIsFixed = arguments?.getBoolean(Routes.ARG_TARGET_FIXED) == true
                            // Folded: one row per family, the rest a row each (#421).
                            val routeRows by routesViewModel.libraryRows.collectAsState()
                            val importingRoute by routesViewModel.importing.collectAsState()
                            val routeMessage by routesViewModel.message.collectAsState()
                            // Which course an import has just added, so the list can be moved to it
                            // rather than left looking unchanged (#458).
                            val courseToShow by routesViewModel.courseToShow.collectAsState()
                            // Asked for here rather than at launch: working out the shape of every
                            // kept course is arithmetic nobody who never opens their routes should
                            // pay for (#59).
                            LaunchedEffect(Unit) { routesViewModel.drawCoursesWhileLibraryIsOpen() }
                            // Picking sorts towards today's distance (#422, #496); browsing keeps
                            // the library's own order.
                            val shownRows = remember(routeRows, routeLibrary, picking, targetMeters, targetIsFixed) {
                                if (picking) {
                                    routeLibraryRowsNearestFirst(routeRows, routeLibrary, targetMeters, targetIsFixed)
                                } else {
                                    routeRows
                                }
                            }
                            RoutesScreen(
                                rows = shownRows,
                                picking = if (picking) {
                                    RoutePicking(
                                        targetMeters = targetMeters,
                                        targetIsFixed = targetIsFixed,
                                        nothingPicked = routeChoiceIsNoRoute(routeChoice, routeLibrary),
                                        onPickNoRoute = { pickRoute(null) },
                                    )
                                } else {
                                    null
                                },
                                isImporting = importingRoute,
                                message = routeMessage,
                                courseToShow = courseToShow,
                                onImport = { pickRouteFile.launch(arrayOf("*/*")) },
                                onOpen = { routeId ->
                                    // A picking row opens on the length it was placed by (#496).
                                    val best = if (picking) {
                                        shownRows.firstOrNull { it.openRouteId == routeId }?.let { row ->
                                            routeLengthToOpenWhilePicking(row, routeLibrary, targetMeters, targetIsFixed)
                                        }
                                    } else {
                                        null
                                    }
                                    navigateTo(Routes.routeDetail(best ?: routeId, picking, exact = best != null))
                                },
                                onDelete = { route -> routesViewModel.delete(route) },
                                onMessageShown = { routesViewModel.messageShown() },
                                onCourseShown = { ask -> routesViewModel.courseShown(ask) },
                                onBack = goBack
                            )
                        }
                        composable(
                            route = Routes.ROUTE_DETAIL,
                            arguments = listOf(
                                navArgument(Routes.ARG_ROUTE_ID) { type = NavType.LongType },
                                navArgument(Routes.ARG_PICK) { type = NavType.BoolType; defaultValue = false },
                                navArgument(Routes.ARG_EXACT) { type = NavType.BoolType; defaultValue = false },
                            )
                        ) { backStackEntry ->
                            val routeId = backStackEntry.arguments?.getLong(Routes.ARG_ROUTE_ID)
                            val picking = backStackEntry.arguments?.getBoolean(Routes.ARG_PICK) == true
                            val exact = backStackEntry.arguments?.getBoolean(Routes.ARG_EXACT) == true
                            // Which of the family's lengths the page is showing (#421).
                            //
                            // Held by the destination rather than by the screen, and remembered
                            // across a rotation, so the length the runner tapped survives one. It
                            // starts as nothing and is settled once, by the read below: a family
                            // opens on the length run most recently.
                            var selectedRouteId by rememberSaveable(routeId) {
                                mutableStateOf<Long?>(null)
                            }
                            LaunchedEffect(routeId) {
                                if (selectedRouteId != null) return@LaunchedEffect
                                // Back to the id the row opened where the landing read finds
                                // nothing, which is the course having been deleted. Left as null it
                                // would leave the page on its opening spinner for good; settled on
                                // the id, the page draws exactly what #420 already drew for a course
                                // that is not there.
                                selectedRouteId = routeId?.let {
                                    if (exact) it else routesViewModel.landingSibling(it) ?: it
                                }
                            }
                            // Everything below follows the *chosen* length rather than the one the
                            // library row opened, which is what makes a chip switch the whole page.
                            val shownRouteId = selectedRouteId
                            // The chips, watched: a length imported or deleted, or given this very
                            // family name on this very page, belongs on the row without the runner
                            // leaving and coming back.
                            //
                            // Asked about the length being *shown*, not the one the library row
                            // opened. A runner who chips to the 8k and then clears its family is
                            // asking about the 8k, and chips still drawn from the 5k's family would
                            // leave the page showing a course that none of its own chips names.
                            val siblings by produceState(
                                initialValue = emptyList<com.example.runningapp.data.RouteHeader>(),
                                key1 = shownRouteId
                            ) {
                                shownRouteId?.let { id ->
                                    routesViewModel.siblings(id).collect { value = it }
                                }
                            }
                            val familyNames by routesViewModel.familyNames
                                .collectAsState(initial = emptyList())
                            // Watched rather than read once, so a rename made here reaches the title
                            // and a delete made in the library empties the page the same instant it
                            // empties the row.
                            val route by produceState<com.example.runningapp.data.RouteHeader?>(
                                initialValue = null,
                                key1 = shownRouteId
                            ) {
                                shownRouteId?.let { id -> routesViewModel.route(id).collect { value = it } }
                            }
                            // Read once and not watched: a Route's line is written when the row is
                            // inserted and never rewritten, so there is nothing to watch for — see
                            // [com.example.runningapp.data.Route.polyline].
                            val line by produceState<List<com.example.runningapp.analysis.MapFix>>(
                                initialValue = emptyList(),
                                key1 = shownRouteId
                            ) {
                                // Emptied first, so a chip tapped shows no map for a frame rather
                                // than the previous length's map under the new length's numbers.
                                value = emptyList()
                                shownRouteId?.let { id -> value = routesViewModel.line(id) }
                            }
                            // Watched too, and for a reason of its own: a Run finishing on this
                            // course, or being saved as it, puts a row on this list under an open
                            // page (#420).
                            // Null is "not read yet", never "none": the row and the Runs are two
                            // reads and the row can land first, so an empty list here would tell a
                            // runner who has been round this course fifty times that they never had.
                            // The rule the record book's own page keeps — recordDetailNotReadYet.
                            val runs by produceState<List<com.example.runningapp.ui.RouteRunUi>?>(
                                initialValue = null,
                                key1 = shownRouteId
                            ) {
                                value = null
                                shownRouteId?.let { id ->
                                    routesViewModel.runsOnRoute(id).collect { value = it }
                                }
                            }
                            RouteDetailScreen(
                                route = route,
                                siblings = siblings,
                                selectedId = shownRouteId,
                                onSelectLength = { id -> selectedRouteId = id },
                                familyNames = familyNames,
                                onSetFamily = { row, family ->
                                    routesViewModel.setFamily(row, family)
                                },
                                line = line,
                                runs = runs,
                                onRename = { row, name -> routesViewModel.rename(row, name) },
                                onDelete = { row -> routesViewModel.delete(row) },
                                onFlip = { row -> routesViewModel.flip(row.id) },
                                // Only on a page opened to pick the next Run's course (#496).
                                onPick = if (picking) { row -> pickRoute(row.id) } else null,
                                isPicked = routeChoice?.routeId != null && routeChoice?.routeId == shownRouteId,
                                // A time on a course belongs to a morning, and the page that holds
                                // the morning is the Run's own (#72, #420).
                                onOpenRun = { runId -> navigateTo(Routes.sessionDetail(runId)) },
                                onBack = goBack
                            )
                        }
                        composable(Routes.SEGMENTS) {
                            val segments by segmentsViewModel.segments.collectAsState()
                            val segmentMessage by segmentsViewModel.message.collectAsState()
                            SegmentsScreen(
                                segments = segments,
                                message = segmentMessage,
                                onOpen = { segment -> navigateTo(Routes.segmentDetail(segment.id)) },
                                onRename = { segment, name -> segmentsViewModel.rename(segment, name) },
                                onDelete = { segment -> segmentsViewModel.delete(segment) },
                                onMessageShown = { segmentsViewModel.messageShown() },
                                onBack = goBack
                            )
                        }
                        composable(
                            route = Routes.SEGMENT_DETAIL,
                            arguments = listOf(navArgument(Routes.ARG_SEGMENT_ID) { type = NavType.LongType })
                        ) { backStackEntry ->
                            val segmentId = backStackEntry.arguments?.getLong(Routes.ARG_SEGMENT_ID)
                            // Watched rather than read once, so a rename reaches the title and a
                            // delete made here empties the page the same instant it empties the row.
                            val segment by produceState<com.example.runningapp.data.Segment?>(
                                initialValue = null,
                                key1 = segmentId
                            ) {
                                segmentId?.let { id -> segmentsViewModel.segment(id).collect { value = it } }
                            }
                            // Watched too, and for a reason of its own: a Segment cut a moment ago
                            // is still being put to history on the container's scope, so its
                            // efforts land one Run at a time under an open page (#70).
                            val efforts by produceState<List<com.example.runningapp.ui.SegmentEffortUi>>(
                                initialValue = emptyList(),
                                key1 = segmentId
                            ) {
                                segmentId?.let { id -> segmentsViewModel.efforts(id).collect { value = it } }
                            }
                            SegmentDetailScreen(
                                segment = segment,
                                efforts = efforts,
                                onRename = { row, name -> segmentsViewModel.rename(row, name) },
                                onDelete = { row ->
                                    segmentsViewModel.delete(row)
                                    // Off the stack, not covered over: the Segment this page is
                                    // for has just been thrown away, so Back must not be able to
                                    // walk back onto it. See [closeEveryPageOf].
                                    pages.closeEveryPageOf(Routes.segmentDetail(row.id))
                                },
                                // A time on a hill belongs to a morning, and the page that holds
                                // the morning is the Run's own (#72).
                                onOpenRun = { runId -> navigateTo(Routes.sessionDetail(runId)) },
                                onBack = goBack
                            )
                        }
                        composable(
                            route = Routes.MATCHED_RUNS,
                            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong(Routes.ARG_SESSION_ID)
                            // Watched, like the card that led here: the group is worked out on read
                            // from the shapes, so a Run finishing or being deleted while this list
                            // is open takes a line off it or adds one (#73).
                            // Held as "has the read answered yet, and with what" rather than as a
                            // group that may be null, because those are two different nulls and the
                            // page owes a different thing to each: a spinner while the shapes are
                            // being read, and a way out once they have been read and there is no
                            // group. A flag saying "a group has been seen" cannot tell them apart —
                            // a group gone before the first emission never sets it, so the page
                            // would sit there loading for ever.
                            val answered by produceState<Answered<com.example.runningapp.ui.MatchedRunsUi?>?>(
                                initialValue = null,
                                key1 = sessionId
                            ) {
                                sessionId?.let { id ->
                                    sessionDetailViewModel.matchedRuns(id).collect { value = Answered(it) }
                                }
                            }
                            // Back the way the runner came, which is the Run's own page: this list
                            // is only ever reached from it.
                            val backToTheRun: () -> Unit = goBack
                            // A group the read has answered "none" to closes the page, the way a
                            // deleted Segment's does (#70): the Run this list belongs to has been
                            // thrown away, or has left its own group, and a screen for a group
                            // nobody has has nothing to show.
                            // By this group's own address, not [backToTheRun]: the group is
                            // re-read on every write to the Runs it is drawn from, so "there is no
                            // such group" can arrive more than once, and a second step off the
                            // stack would take the Run's own page with it. Asking twice for a
                            // group already gone takes nothing. See [closeEveryPageOf].
                            LaunchedEffect(answered) {
                                if (answered != null && answered?.value == null && sessionId != null) {
                                    pages.closeEveryPageOf(Routes.matchedRuns(sessionId))
                                }
                            }
                            MatchedRunsScreen(
                                matched = answered?.value,
                                onOpenRun = { runId -> navigateTo(Routes.sessionDetail(runId)) },
                                onBack = backToTheRun,
                            )
                        }
                        composable(
                            route = Routes.SEGMENT_CREATE,
                            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong(Routes.ARG_SESSION_ID)
                            val session by produceState<com.example.runningapp.data.RunnerSession?>(
                                initialValue = null,
                                key1 = sessionId
                            ) {
                                sessionId?.let { id ->
                                    database.sessionDao().getSessionByIdFlow(id).collect { value = it }
                                }
                            }
                            // The same accuracy-gated track the Run's own map is drawn from, so the
                            // stretch the runner marks out is a slice of exactly the line they were
                            // shown up there.
                            val track by produceState<List<com.example.runningapp.data.TrackPoint>>(
                                initialValue = emptyList(),
                                key1 = sessionId
                            ) {
                                sessionId?.let { id ->
                                    sessionRepository.getTrackPointsForMapFlow(id).collect { value = it }
                                }
                            }
                            val samples by produceState<List<com.example.runningapp.data.HrSample>>(
                                initialValue = emptyList(),
                                key1 = sessionId
                            ) {
                                sessionId?.let { id ->
                                    database.sampleDao().getSamplesForSession(id).collect { value = it }
                                }
                            }
                            SegmentCreateScreen(
                                session = session,
                                samples = samples,
                                trackPoints = track,
                                onSave = { cut, name ->
                                    sessionId?.let { segmentsViewModel.saveSegment(cut, name, it) }
                                    // On to the collection rather than back to the Run: the runner
                                    // has just made a thing, and this is where it is. Cancelling
                                    // goes back the way they came, which is the difference between
                                    // having made one and not.
                                    //
                                    // The cutting screen comes off the stack on the way, so Back
                                    // from the collection returns to the Run rather than re-opening
                                    // a cut that has already been made.
                                    navController.navigate(Routes.SEGMENTS) {
                                        popUpTo(Routes.SEGMENT_CREATE) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onBack = goBack
                            )
                        }
                        composable(Routes.MAP) {
                            FullScreenMapScreen(
                                state = serviceState.value,
                                sessionRepository = sessionRepository,
                                onBack = goBack
                            )
                        }
                    }

                    feelSheetSessionId?.let { sessionId ->
                        // Every way off this sheet ends here, which is why both exits go through one
                        // lambda: the Run's Stage has been waiting on this sheet since STOP (#297),
                        // and an exit that forgot to say so would hold the plan until the next
                        // launch. [writes] is whatever that exit had to store first — a Save's
                        // effort, note and any stated distance, and nothing at all for a dismissal,
                        // because a runner who swipes the sheet away has said the Run was what it
                        // looks like and that is an answer too.
                        //
                        // The Walk switch is handed over by name rather than written in [writes],
                        // because it is not one of the answer's writes but the word the settlement
                        // reads: inside the block it would share the fate of the writes beside it,
                        // and a mark lost to somebody else's failure is a Stage graduated on a walk.
                        //
                        // Handed to the container rather than launched here, because the first thing
                        // this does is take the sheet away: a scope that belongs to the composition
                        // is cancelled by the runner leaving the app on the exit itself, and the
                        // gate would go on naming this Run for the life of the process, with the
                        // finish already past and the launch pass already run.
                        val closeSheet: (Boolean?, suspend () -> Unit) -> Unit = { markedAsWalk, writes ->
                            feelSheetSessionId = null
                            appContainer.answerFinishSheet(sessionId, markedAsWalk, writes)
                        }
                        FeelFeedbackSheet(
                            // A treadmill Run, said positively: anything else — an outdoor Run, or a
                            // Run whose mode is not known — is not asked.
                            askForDistance = feelSheetRunMode == RunMode.TREADMILL.settingValue,
                            onSave = { effort, note, distanceKm, isWalk ->
                                closeSheet(isWalk) {
                                    sessionRepository.saveFeelFeedback(sessionId, effort, note)
                                    // After the feedback, so the snapshot the distance takes carries
                                    // both. Only when there is one: stating nothing must not cost a
                                    // second copy of the whole database.
                                    if (distanceKm != null) {
                                        sessionRepository.stateDistance(sessionId, distanceKm)
                                    }
                                }
                            },
                            onDismiss = { closeSheet(null) {} }
                        )
                    }
                  }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The weather Runs are still owed, asked for every time the app comes to the front rather than
        // once per process, so a phone that was offline at launch tries again when the runner comes
        // back to it (#444). An ask while one is still going runs once it ends.
        runningAppContainer().askForOwedWeather()
        Intent(this, HrForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
             permissions.add(Manifest.permission.BLUETOOTH)
             permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        // Mission 4: Location permissions (Foreground)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            // Foreground granted, now check background if needed
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Show a dialog/explanation if needed? For now just request.
                // NOTE: Android 11+ requires separate request for background.
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

private class Answered<T>(val value: T)
