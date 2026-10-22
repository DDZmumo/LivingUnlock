package com.windowslockpin.companion.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windowslockpin.companion.R
import com.windowslockpin.companion.ui.theme.IceBluePrimary
import com.windowslockpin.companion.update.ReleaseUpdate
import com.windowslockpin.companion.update.ReleaseUpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@Composable
fun SettingsScreen(
    isListening: Boolean,
    compatibilityScan: Boolean,
    onBack: () -> Unit,
    onToggleListening: (Boolean) -> Unit,
    onToggleCompatibility: (Boolean) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.bg_livingunlock), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0xDA0D1B2A)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回主页", tint = IceBluePrimary)
                    }
                    Text("设置", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                UpdateSettingsCard()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, tint = IceBluePrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("蓝牙连接", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                SettingsCard {
                    SettingToggle("解锁监听", "关闭后，将无法接收电脑的蓝牙解锁请求。",
                        isListening, onToggleListening)
                }
                SettingsCard {
                    Text("电脑主动连接 · 推荐", color = IceBluePrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    SettingsBody("电脑需要解锁时主动连接已配对手机。手机被动等待连接，不主动搜索电脑，也不会持续重连。")
                    Spacer(Modifier.height(12.dp))
                    Text(if (isListening) "已随解锁监听启用" else "开启解锁监听后启用",
                        color = Color(0xFF9EADBF), fontSize = 13.sp)
                }
                SettingsCard {
                    SettingToggle("BLE 兼容扫描", "默认关闭。仅在需要兼容旧版电脑或迁移旧配对时开启。",
                        compatibilityScan, onToggleCompatibility)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0x3300E5FF))
                    Spacer(Modifier.height(12.dp))
                    SettingsBody("开启后，手机会同时进行低功耗 BLE 扫描。电脑主动连接失败时，可广播信号，让手机扫描到后反向连接。")
                    Spacer(Modifier.height(10.dp))
                    SettingsBody("部分系统会在熄屏时暂停扫描，因此兼容扫描不保证熄屏响应。它会增加扫描开销；关闭不会影响电脑主动连接。")
                    if (compatibilityScan && !isListening) {
                        Spacer(Modifier.height(10.dp))
                        Text("已保存偏好；解锁监听关闭时不会扫描。", color = IceBluePrimary, fontSize = 13.sp)
                    }
                }
                SettingsCard {
                    Text("旧配对如何迁移", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SettingsBody("更新电脑和手机后，若还未使用主动连接成功解锁，可临时开启兼容扫描，在手机亮屏时完成一次指纹解锁。电脑会安全记录手机地址，之后即可关闭兼容扫描，无需重新绑定。")
                    Spacer(Modifier.height(12.dp))
                    SettingsBody("两种方式均通过本地蓝牙通信，仍需指纹确认。Windows 原生 PIN 和密码登录保持可用。")
                }
            }
        }
    }
}

@Composable
private fun UpdateSettingsCard() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
    }
    var includePrerelease by rememberSaveable { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("手动检查 GitHub Releases 中的 Android 安装包。") }
    var result by remember { mutableStateOf<ReleaseUpdate?>(null) }

    SettingsCard {
        Text("应用更新", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        SettingsBody("当前版本：$version")
        Spacer(Modifier.height(12.dp))
        SettingToggle("包含预发布版本", "包括 GitHub 标记的实验版。", includePrerelease) {
            if (!checking) {
                includePrerelease = it
                result = null
                message = "版本范围已切换，请重新检查。"
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            if (!checking) scope.launch {
                checking = true
                result = null
                message = "正在连接 GitHub…"
                try {
                    val release = ReleaseUpdateChecker.check(version, includePrerelease)
                    result = release
                    message = when {
                        release == null -> "所选范围内暂无 Android 安装包。可尝试包含预发布版本。"
                        release.isNewer -> "发现新版本 ${release.tag}${if (release.prerelease) "（预发布）" else ""}"
                        else -> "当前无需更新。GitHub 上 Android 最新版本为 ${release.tag}${if (release.prerelease) "（预发布）" else ""}。"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = when (e) {
                        is SocketTimeoutException -> "检查超时，请重试。"
                        is UnknownHostException -> "无法连接 GitHub，请检查网络后重试。"
                        else -> e.message ?: "检查失败，请稍后重试。"
                    }
                } finally { checking = false }
            }
        }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (checking) "正在检查…" else "检查更新")
        }
        Spacer(Modifier.height(10.dp))
        Text(message, color = if (result?.isNewer == true) IceBluePrimary else Color(0xFFC5D5E4), fontSize = 14.sp)
        result?.let { release ->
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SettingsBody(release.notes.take(3000))
            }
            if (context.packageName.endsWith(".debug") && release.isNewer) {
                Spacer(Modifier.height(8.dp))
                SettingsBody("当前是调试版。GitHub 的正式签名 APK 会作为独立应用安装，配对不会自动迁移。")
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                runCatching { uriHandler.openUri(if (release.isNewer) release.downloadUrl else release.releaseUrl) }
                    .onFailure { message = "无法打开浏览器，请在 GitHub 查看 Release。" }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (release.isNewer) "下载安装包" else "查看此版本")
            }
        }
        TextButton(onClick = {
            runCatching { uriHandler.openUri(ReleaseUpdateChecker.RELEASES_URL) }
                .onFailure { message = "无法打开浏览器。" }
        }) { Text("查看 GitHub Releases", color = IceBluePrimary) }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = Color(0xE6132238), border = BorderStroke(1.dp, Color(0x5500E5FF))) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            SettingsBody(description)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedTrackColor = IceBluePrimary))
    }
}

@Composable
private fun SettingsBody(text: String) {
    Text(text, color = Color(0xFFC5D5E4), fontSize = 14.sp, lineHeight = 22.sp)
}
