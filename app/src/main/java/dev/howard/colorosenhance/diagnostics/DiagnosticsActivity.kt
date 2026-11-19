package dev.howard.colorosenhance.diagnostics

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.howard.colorosenhance.BuildConfig
import dev.howard.colorosenhance.EnhanceApplication
import dev.howard.colorosenhance.SettingsCatalog
import dev.howard.colorosenhance.SettingsIcons
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class DiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DiagnosticsScreen(this) }
    }
}

private data class PointStatus(val point: DiagnosticPoint, val configured: Boolean?, val summary: String)
private data class ModuleStatus(val environment: String, val service: String, val rows: List<PointStatus>)
private val reportRanges = listOf("15 分钟" to Duration.ofMinutes(15), "1 小时" to Duration.ofHours(1), "24 小时" to Duration.ofHours(24))
private val reportTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault())

private fun status(app: EnhanceApplication): ModuleStatus {
    val store = DiagnosticStore.get(app)
    val boot = DiagnosticStore.boot(app)
    val reports = store.latest()
    val preferences = DiagnosticPoint.entries.map { it.group }.distinct().associateWith { app.settings().read(it) }
    val rows = DiagnosticPoint.entries.map { point ->
        val values = preferences[point.group]?.values()
        val default = point == DiagnosticPoint.SETTINGS || point == DiagnosticPoint.TOKEN_FADE
        val flag = values?.get(point.key) as? Boolean ?: default
        val configured = values?.let {
            if (point.group == "integration" || point.key == "enabled") flag
            else flag && it["enabled"] == true && (point.key == "notification_cloud_enabled" ||
                SettingsCatalog.controlEnabled(point.group, point.key, it))
        }
        val setting = when (configured) { true -> "开启"; false -> "关闭"; null -> "读取不到配置" }
        val report = reports[point]
        val runtime = when {
            report == null -> if (configured == true) "暂无报告，请检查 LSPosed 作用域或触发该功能" else "暂无运行报告"
            report.boot() != boot || report.update() != store.appUpdated() -> "上次版本或开机记录 · ${report.signal().state().label}"
            !DiagnosticProvider.live(point) -> "上次报告 · ${report.signal().state().label}"
            else -> report.signal().state().label
        }
        val last = report?.let { "\n${reportTime.format(Instant.ofEpochMilli(it.signal().time()))}" }.orEmpty()
        PointStatus(point, configured, "配置$setting · $runtime$last")
    }
    val connection = if (app.frameworkService() == null) "LSPosed 服务未连接" else "LSPosed 服务已连接"
    return ModuleStatus("模块 ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n固件 ${Build.VERSION.INCREMENTAL}",
        connection, rows)
}

@Composable
private fun DiagnosticsScreen(activity: DiagnosticsActivity) {
    val app = activity.application as EnhanceApplication
    val appearance = remember { app.getSharedPreferences("ui_presentation", Context.MODE_PRIVATE).getInt("appearance", 0) }
    val lightBars = when (appearance) { 1 -> true; 2 -> false; else -> !isSystemInDarkTheme() }
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
    val theme = remember(appearance) { ThemeController(when (appearance) {
        1 -> ColorSchemeMode.MonetLight; 2 -> ColorSchemeMode.MonetDark; else -> ColorSchemeMode.MonetSystem
    }) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<ModuleStatus?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var range by rememberSaveable { mutableIntStateOf(1) }
    var showDisabled by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    DisposableEffect(app) {
        val observer = Runnable { revision++ }
        app.observe(observer)
        onDispose { app.removeObserver(observer) }
    }
    LaunchedEffect(revision) {
        val result = withContext(Dispatchers.IO) { runCatching { status(app) } }
        snapshot = result.getOrNull()
        loadFailed = result.isFailure
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val duration = reportRanges[range].second
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val current = status(app)
                    val now = System.currentTimeMillis()
                    val events = DiagnosticStore.get(app).recent(now, duration.toMillis())
                    val report = buildString {
                        appendLine("ColorOS 增强 · 模块诊断"); appendLine(current.environment); appendLine(current.service)
                        appendLine("导出时间 ${reportTime.format(Instant.ofEpochMilli(now))}")
                        appendLine("\n关键接口")
                        current.rows.filter { it.configured != false }.forEach { appendLine("${it.point.name} ${it.point.label} · ${it.summary.replace('\n', ' ')}") }
                        appendLine("\n最近事件（${events.size} 条）")
                        for (entry in events) appendLine("${reportTime.format(Instant.ofEpochMilli(entry.time()))} ${entry.point().scope.label} ${entry.point().name} ${entry.state().name}")
                    }
                    checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                    events.size
                }
            }
            saving = false
            Toast.makeText(activity, result.fold({ "诊断日志已保存（$it 条事件）" }, { "保存诊断日志失败" }), Toast.LENGTH_SHORT).show()
        }
    }
    MiuixTheme(controller = theme) {
        val colors = MiuixTheme.colorScheme
        Scaffold(modifier = Modifier.fillMaxSize().background(colors.surface), topBar = {
            TopAppBar(title = "模块状态", navigationIcon = {
                Box(Modifier.size(48.dp).clickable { activity.finish() }, contentAlignment = Alignment.Center) {
                    Image(SettingsIcons.icon("back"), "返回", Modifier.size(24.dp), colorFilter = ColorFilter.tint(colors.onSurface))
                }
            })
        }, contentWindowInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                item("module") { Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = snapshot?.service ?: if (loadFailed) "状态暂不可用" else "正在读取…", summary = snapshot?.environment.orEmpty())
                    BasicComponent(title = "刷新状态", onClick = { revision++ })
                    SwitchPreference(showDisabled, { showDisabled = it }, "同时显示未开启的功能")
                } }
                item("export") { Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = "日志范围 · 最近 ${reportRanges[range].first}", onClick = { range = (range + 1) % reportRanges.size })
                    BasicComponent(title = if (saving) "正在保存…" else "保存诊断日志", enabled = !saving,
                        summary = "最多保留 24 小时内的 512 条模块事件。日志包含固件与 Hook 状态，不含通知正文、账号或 PIN。",
                        onClick = { picker.launch("ColorOS-Enhance-diagnostics.txt") })
                } }
                for (host in DiagnosticPoint.Scope.entries) {
                    val rows = snapshot?.rows.orEmpty().filter { it.point.scope == host && (showDisabled || it.configured != false) }
                    if (rows.isNotEmpty()) item(host.name) { Card(Modifier.fillMaxWidth()) {
                        BasicComponent(title = host.label, summary = if (DiagnosticProvider.live(host)) "当前进程已报告" else "当前进程尚未确认")
                        rows.forEach { BasicComponent(title = it.point.label, summary = it.summary) }
                    } }
                }
            }
        }
    }
}
