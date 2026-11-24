package dev.howard.colorosenhance.updates

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import dev.howard.colorosenhance.BuildConfig
import dev.howard.colorosenhance.EnhanceApplication
import dev.howard.colorosenhance.SettingsIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Shared manual update screen for both frontends. */
class UpdateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UpdateScreen(this) }
    }
}

@Composable
private fun UpdateScreen(activity: UpdateActivity) {
    val app = activity.application as EnhanceApplication
    val appearance = remember { activity.getSharedPreferences("ui_presentation", Context.MODE_PRIVATE).getInt("appearance", 0).coerceIn(0, 2) }
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
    var request by rememberSaveable { mutableIntStateOf(0) }
    var showUpdatePrompts by remember { mutableStateOf(app.updatePromptsEnabled()) }
    var includePrereleases by remember { mutableStateOf(app.prefersPrereleases()) }
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<GitHubUpdateClient.Result?>(null) }
    LaunchedEffect(request, includePrereleases) {
        val selectedChannel = includePrereleases
        loading = true
        result = null
        result = withContext(Dispatchers.IO) { GitHubUpdateClient().check(selectedChannel) }
        loading = false
    }
    MiuixTheme(controller = theme) {
        val colors = MiuixTheme.colorScheme
        Box(Modifier.fillMaxSize().background(colors.surface)) {
            Scaffold(topBar = {
                TopAppBar(title = "检查更新", navigationIcon = {
                    Box(Modifier.size(48.dp).clickable { activity.finish() }, contentAlignment = Alignment.Center) {
                        Image(SettingsIcons.icon("back"), "返回", Modifier.size(24.dp), colorFilter = ColorFilter.tint(colors.onSurface))
                    }
                })
            }, contentWindowInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item("channel") {
                        Card(Modifier.fillMaxWidth()) {
                            SwitchPreference(showUpdatePrompts, { enabled ->
                                app.setUpdatePromptsEnabled(enabled)
                                showUpdatePrompts = enabled
                            }, "启动时提醒新版本", summary = "打开应用时检查更新，有新版本时弹窗提醒。")
                            SwitchPreference(includePrereleases, { include ->
                                app.setPreferPrereleases(include)
                                includePrereleases = include
                                result = null
                                loading = true
                            }, "优先检查预发布版本", summary = "开启后可收到比正式版更新的预发布版。关闭时只检查正式版。")
                        }
                    }
                    item("status") {
                        Card(Modifier.fillMaxWidth()) {
                            val release = result?.release()
                            val channel = if (release?.prerelease() == true) "预发布版" else "正式版"
                            val title = when {
                                loading -> "正在检查…"
                                release == null -> "检查未完成"
                                release.newerThan(BuildConfig.VERSION_CODE.toLong()) ->
                                    "${if (release.prerelease()) "发现预发布版" else "发现新版本"} ${release.versionName()}"
                                else -> "暂无更新"
                            }
                            BasicComponent(title = title, summary = "当前版本 ${BuildConfig.VERSION_NAME}" +
                                if (release == null) "" else " · 可用$channel ${release.versionName()}")
                            if (!loading && release == null) Text(result?.error().orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 14.sp)
                            TextButton(if (loading) "正在检查…" else "重新检查", { if (!loading) request++ }, Modifier.fillMaxWidth().padding(12.dp))
                            if (!loading && release != null && release.newerThan(BuildConfig.VERSION_CODE.toLong()))
                                TextButton("在浏览器中下载 APK", { UpdateLinks.browser(activity, release.apkUrl()) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        }
                    }
                    val release = result?.release()
                    if (release != null && release.hasNotes()) item("notes") {
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                            Text("版本说明", fontSize = 18.sp)
                            ReleaseNotesText(release, Modifier.padding(top = 12.dp))
                        }
                    }
                    item("links") {
                        Card(Modifier.fillMaxWidth()) {
                            BasicComponent(title = "查看发布页", summary = "GitHub · LSPosed 模块仓库", onClick = {
                                UpdateLinks.browser(activity, release?.pageUrl() ?: ReleaseInfo.RELEASES_URL)
                            })
                            BasicComponent(title = "GitHub 仓库", summary = "源码、使用说明与问题反馈", onClick = { UpdateLinks.repository(activity) })
                        }
                    }
                    item("privacy") {
                        Text("手动检查包含已跳过的版本。通过 HTTPS 访问 GitHub，不发送模块配置、媒体数据或设备标识；GitHub 会收到网络请求的 IP 地址。下载和安装由你在浏览器及安装器中确认。",
                            Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}
