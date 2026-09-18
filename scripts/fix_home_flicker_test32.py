from pathlib import Path

proxy_path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
main_path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/HetuMainActivity.kt')

proxy = proxy_path.read_text(encoding='utf-8')
main = main_path.read_text(encoding='utf-8')

old = '''class ReferenceProxyActivity : ComponentActivity() {
    private var uiRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            key(uiRevision) { HetuTheme { RefProxyShell { finish() } } }
        }
    }

    override fun onResume() {
        super.onResume()
        uiRevision++
    }
}'''
new = '''class ReferenceProxyActivity : ComponentActivity() {
    private var resumeRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Re-read theme preferences on resume without destroying the Compose tree.
            // The previous forced wrapper recreated RefProxyShell and briefly exposed
            // default/empty runtime state before the async refresh completed.
            val revision = resumeRevision
            HetuTheme { RefProxyShell(resumeRevision = revision) { finish() } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeRevision++
    }
}'''
if old not in proxy:
    raise SystemExit('ReferenceProxyActivity root recreation block not found')
proxy = proxy.replace(old, new, 1)

old = '''private fun RefProxyShell(onBack: () -> Unit) {'''
new = '''private fun RefProxyShell(resumeRevision: Int, onBack: () -> Unit) {'''
if old not in proxy:
    raise SystemExit('RefProxyShell signature not found')
proxy = proxy.replace(old, new, 1)

anchor = '''    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refresh()
        while (true) {
            delay(2200)
            refresh()
        }
    }
'''
addition = anchor + '''
    // Returning from Theme/secondary activities should refresh data in place. Never
    // replace the composition or reset state/runtime/providers to their empty defaults.
    LaunchedEffect(resumeRevision) {
        if (resumeRevision > 1) refresh()
    }
'''
if anchor not in proxy:
    raise SystemExit('proxy refresh loop anchor not found')
proxy = proxy.replace(anchor, addition, 1)

# Ad-block home: keep the last real snapshot in the app shell so switching tabs does
# not recreate HomePage with HomeSnapshot() and momentarily show fake zero/off values.
old = '''    var page by rememberSaveable { mutableStateOf(MainPage.Home) }
    val dockItems = remember {'''
new = '''    var page by rememberSaveable { mutableStateOf(MainPage.Home) }
    var homeSnapshotCache by remember { mutableStateOf<HomeSnapshot?>(null) }
    val dockItems = remember {'''
if old not in main:
    raise SystemExit('main page state anchor not found')
main = main.replace(old, new, 1)

old = '''                MainPage.Home -> HomePage(controller, actionScope)'''
new = '''                MainPage.Home -> HomePage(
                    controller = controller,
                    actionScope = actionScope,
                    cachedSnapshot = homeSnapshotCache,
                    onSnapshot = { homeSnapshotCache = it },
                )'''
if old not in main:
    raise SystemExit('HomePage call not found')
main = main.replace(old, new, 1)

old = '''private fun HomePage(controller: HetuComposeController, actionScope: CoroutineScope) {
    val context = LocalContext.current
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(HomeSnapshot()) }
    var busy by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf("") }'''
new = '''private fun HomePage(
    controller: HetuComposeController,
    actionScope: CoroutineScope,
    cachedSnapshot: HomeSnapshot?,
    onSnapshot: (HomeSnapshot) -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(cachedSnapshot ?: HomeSnapshot()) }
    var snapshotReady by remember { mutableStateOf(cachedSnapshot != null) }
    var busy by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf("") }'''
if old not in main:
    raise SystemExit('HomePage state block not found')
main = main.replace(old, new, 1)

old = '''    LaunchedEffect(revision) {
        try {
            snapshot = controller.homeSnapshot()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            operationMessage = error.message ?: "状态读取失败"
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            try { snapshot = controller.homeSnapshot() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }'''
new = '''    LaunchedEffect(revision) {
        try {
            val next = controller.homeSnapshot()
            snapshot = next
            snapshotReady = true
            onSnapshot(next)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            operationMessage = error.message ?: "状态读取失败"
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            try {
                val next = controller.homeSnapshot()
                snapshot = next
                snapshotReady = true
                onSnapshot(next)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }'''
if old not in main:
    raise SystemExit('HomePage refresh effects not found')
main = main.replace(old, new, 1)

# On the very first process start there is no cached truth yet. Use neutral loading
# copy instead of rendering a false "off / zero" dashboard for a few frames.
main = main.replace(
    '''if (snapshot.moduleEnabled || snapshot.vpnRunning) "去广告已开启" else "去广告未开启"''',
    '''if (!snapshotReady) "正在读取状态" else if (snapshot.moduleEnabled || snapshot.vpnRunning) "去广告已开启" else "去广告未开启"''',
    1,
)
main = main.replace(
    '''if (snapshot.moduleEnabled || snapshot.vpnRunning) tokens.success else tokens.warning,''',
    '''if (!snapshotReady) tokens.textMuted else if (snapshot.moduleEnabled || snapshot.vpnRunning) tokens.success else tokens.warning,''',
    1,
)
main = main.replace(
    '''if (snapshot.moduleEnabled || snapshot.vpnRunning) "正在保护" else "准备就绪",''',
    '''if (!snapshotReady) "正在同步" else if (snapshot.moduleEnabled || snapshot.vpnRunning) "正在保护" else "准备就绪",''',
    1,
)

proxy_path.write_text(proxy, encoding='utf-8')
main_path.write_text(main, encoding='utf-8')

# Guardrails: the two old flicker sources must be gone.
if 'key(uiRevision) {' in proxy:
    raise SystemExit('forced root recreation still present')
if 'var snapshot by remember { mutableStateOf(HomeSnapshot()) }' in main:
    raise SystemExit('uncached HomeSnapshot reset still present')
print('test32 home flicker fix applied')
