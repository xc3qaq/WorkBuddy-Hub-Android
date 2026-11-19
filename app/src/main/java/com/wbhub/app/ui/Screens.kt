package com.wbhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wbhub.app.data.Login
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.wbhub.app.bridge.CallRecord
import com.wbhub.app.bridge.DotShape
import java.util.Locale
import com.wbhub.app.data.CheckinItem
import com.wbhub.app.proto.Wire

import com.wbhub.app.proto.HubModel
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CredentialScreen(
    state: HubState,
    onSwitchRealm: (Wire.Region) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onLogin: (Wire.Region) -> Unit,
    onLogout: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val region = state.realm
    val regionLabel = realmName(region)
    val active = state.credential
    val saved = state.accounts[region].orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前账号 · $regionLabel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "查看详情",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        active == null -> Text(
                            "$regionLabel 还没有账号，点下面的按钮登录。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> {
                            Text("账号：${active.nickname.ifBlank { active.uid.take(8) }}")
                            Text("有效期：${state.expiryText.ifBlank { "未知" }}")
                            Text(
                                "点本卡片查看 Token 等完整信息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // Build switch. The dot shows a build has at least one saved account.
        item {
            Text("版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Wire.Region.entries.forEachIndexed { index, item ->
                    val count = state.accounts[item].orEmpty().size
                    SegmentedButton(
                        selected = region == item,
                        onClick = { onSwitchRealm(item) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = Wire.Region.entries.size),
                    ) {
                        Text(if (count > 0) "${realmName(item)} $count" else realmName(item))
                    }
                }
            }
        }

        // Accounts of the selected build. Selecting one switches the credential
        // the bridge serves, so switching never requires signing in again.
        if (saved.isNotEmpty()) {
            item {
                Text(
                    "$regionLabel 账号",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(saved, key = { it.id }) { account ->
                val isActive = account.id == state.activeAccountId
                AccountRow(
                    label = account.label,
                    uid = account.uid,
                    isActive = isActive,
                    onSelect = { onSwitchAccount(account.id) },
                    onDelete = { onDeleteAccount(account.id) },
                )
            }
        }

        item {
            Button(onClick = { onLogin(region) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (active == null) "登录 $regionLabel" else "添加 $regionLabel 账号")
            }
        }

        if (state.hasAnyCredential) {
            item {
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("退出登录（清除全部账号）")
                }
            }
        }

        item {
            Text(
                "说明：走官方 CLI 的 OAuth 流程，登录后可拿到 API token（网页 cookie 无法调用接口）。" +
                    "国内版与国际版是两套独立账号体系，各自可以添加多个账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One saved account, with a switch and a delete action. */
@Composable
private fun AccountRow(
    label: String,
    uid: String,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (uid.isNotBlank()) {
                    Text(
                        uid.take(8),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isActive) {
                Text(
                    "使用中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除账号",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Full credential detail, including both tokens, in a modal drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailDrawer(
    state: HubState,
    onDismiss: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
) {
    if (state.credential == null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Text("尚无凭证", modifier = Modifier.padding(24.dp))
        }
        return
    }
    val cred = state.credential
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "凭证详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item { detailRow("版本", realmName(Wire.regionOf(cred.domain)), onCopy) }
            item { detailRow("昵称", cred.nickname.ifBlank { "(无)" }, onCopy) }
            item { detailRow("UID", cred.uid, onCopy) }
            item { detailRow("Domain", cred.domain, onCopy) }
            item { detailRow("来源", cred.source, onCopy) }
            item { detailRow("有效期", state.expiryText.ifBlank { "未知" }, onCopy) }
            item { detailRow("Access Token", cred.accessToken, onCopy) }
            item { detailRow("Refresh Token", cred.refreshToken.ifBlank { "(无)" }, onCopy) }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "点任意一行可复制其内容。Token 属于登录凭据，请勿分享。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One copyable key/value line in the detail drawer. */
@Composable
private fun detailRow(label: String, value: String, onCopy: (String, String) -> Unit) {
    Card(
        onClick = { onCopy(label, value) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BridgeScreen(
    state: HubState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onCopyEndpoint: () -> Unit,
    onShowHelp: () -> Unit,
    onCopyModel: (String) -> Unit,
    onRequestNotifications: () -> Unit,
    onRefreshModels: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestOverlay: () -> Unit,
    onOverlayOpacity: (Float) -> Unit,
    onOverlayLocked: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onSaveLanKey: (String) -> Unit,
    onCopyField: (String, String) -> Unit,
    onOpenAdvanced: () -> Unit,
    onToggleStealth: (Boolean) -> Unit,
    onDotColor: (Int) -> Unit,
    onDotSize: (Int) -> Unit,
    onDotShape: (DotShape) -> Unit,
    onDotAlpha: (Float) -> Unit,
    onBurnIn: (Boolean) -> Unit,
    onBurnInInterval: (Long) -> Unit,
    onAdjustDot: (Boolean) -> Unit,
) {
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    // The key fields are local state so editing does not rewrite the running
    // server's keys on every keystroke.
    var keyText by remember(state.secret) { mutableStateOf(state.secret) }
    var lanKeyText by remember(state.lanKey) { mutableStateOf(state.lanKey) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本地 API 平台",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onOpenAdvanced) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "高级设置",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Switch(checked = state.bridgeRunning, onCheckedChange = { checked ->
                            if (checked) onStart(portText.toIntOrNull() ?: 8765) else onStop()
                        })
                    }
                    if (!state.batteryExempt) {
                        Text(
                            "建议同时关闭电池优化：点此设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onRequestBatteryExemption),
                        )
                    }
                    if (!state.notificationsAllowed) {
                        Card(
                            onClick = onRequestNotifications,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text(
                                "通知权限未开启，服务可能被系统回收。点此开启。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (state.bridgeRunning) "运行中：http://127.0.0.1:${state.port}/v1" else "未运行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.bridgeRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (state.bridgeRunning) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                    Text("本机密钥", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "本机客户端（含内置对话）用它调用端点，与局域网密钥互相独立。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { onSaveKey(keyText) },
                            enabled = keyText.isNotBlank() && keyText != state.secret,
                        ) { Text("保存") }
                        FilledTonalButton(onClick = { keyText = randomKey() }) { Text("随机生成") }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("局域网访问", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.lanEnabled) "同一网络下的设备可以调用" else "仅本机可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.lanEnabled, onCheckedChange = onToggleLan)
                    }
                    if (state.lanEnabled) {
                        val address = state.lanAddresses.firstOrNull()
                        if (address == null) {
                            Text(
                                "未检测到局域网地址，请连接 Wi-Fi 后重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text("局域网地址", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "http://$address:${state.port}/v1",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text("局域网密钥", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = lanKeyText,
                                onValueChange = { lanKeyText = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { onSaveLanKey(lanKeyText) },
                                    enabled = lanKeyText.isNotBlank() && lanKeyText != state.lanKey,
                                ) { Text("保存") }
                                FilledTonalButton(onClick = { lanKeyText = randomKey() }) { Text("随机生成") }
                            }
                        }
                        Text(
                            "⚠ 仅在可信网络下开启。密钥以明文 HTTP 传输，" +
                                "同网段的其他人可能窃取，公共 Wi-Fi 下请关闭。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "接入方式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onShowHelp) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = "接入帮助",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    EndpointRow(
                        label = "baseUrl",
                        value = "http://127.0.0.1:${state.port}/v1",
                        onCopy = onCopyEndpoint,
                    )
                    EndpointRow(
                        label = "apiKey",
                        value = state.secret,
                        onCopy = { onCopyField("apiKey", state.secret) },
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "后台常驻（悬浮窗）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (state.overlayAllowed) "已开启" else "未开启",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.overlayAllowed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        if (state.overlayAllowed) {
                            "服务运行时会在屏幕边缘显示状态面板，让应用保持可见，系统不会在后台冻结它。面板可拖动，点一下可收起。"
                        } else {
                            "开启后会在屏幕上显示一个小状态面板，让应用保持可见，避免后台被系统冻结。无需 root，也无需其它工具。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.overlayAllowed) {
                        Button(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) {
                            Text("开启悬浮窗")
                        }
                    } else {
                        Text(
                            "透明度 ${(state.overlayOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = state.overlayOpacity,
                            onValueChange = onOverlayOpacity,
                            valueRange = 0.15f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("固定位置", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "开启后悬浮窗不能被拖动，避免误触。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.overlayLocked,
                                onCheckedChange = onOverlayLocked,
                            )
                        }

                        HorizontalDivider()

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("隐匿模式", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "悬浮窗变成一个小圆点，不拦截点击，可以穿过去点到下面的应用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.stealthEnabled,
                                onCheckedChange = onToggleStealth,
                            )
                        }

                        if (state.stealthEnabled) {
                            FilledTonalButton(
                                onClick = { onAdjustDot(!state.adjustingDot) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.adjustingDot) "完成调整" else "调整位置")
                            }
                            Text(
                                if (state.adjustingDot) {
                                    "现在可以拖动小圆点，拖到想要的位置后点「完成调整」。"
                                } else {
                                    "小圆点会拦截点击，所以默认不能拖动；点上方按钮临时开启拖动。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            DotSettings(
                                state = state,
                                onDotColor = onDotColor,
                                onDotSize = onDotSize,
                                onDotShape = onDotShape,
                                onDotAlpha = onDotAlpha,
                                onBurnIn = onBurnIn,
                                onBurnInInterval = onBurnInInterval,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("可用模型（点模型名可复制）", Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onRefreshModels,
                    enabled = state.loading != Loading.MODELS,
                ) {
                    if (state.loading == Loading.MODELS) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("获取中")
                    } else {
                        Text("重新获取")
                    }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth()) {
                ModelList(
                    models = state.models,
                    onCopyModel = onCopyModel,
                    modifier = Modifier.heightIn(max = 620.dp),
                )
                if (state.loading == Loading.MODELS && state.models.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContainedLoadingIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun RewardsScreen(
    state: HubState,
    onCheckin: () -> Unit,
    onCheckinAll: () -> Unit,
    onRefreshBalance: () -> Unit,
    onStreakBonus: () -> Unit,
    onActivityReport: () -> Unit,
    onTravel: () -> Unit,
    onNightOwl: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                    Text("每日签到", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = onCheckin,
                        enabled = state.loading != Loading.CHECKIN,
                        modifier = Modifier
                            .fillMaxWidth()
                            // A long press runs the whole build's accounts, so a
                            // user with several does not have to tap per account.
                            .combinedClickable(
                                onClick = onCheckin,
                                onLongClick = onCheckinAll,
                            ),
                    ) {
                        if (state.loading == Loading.CHECKIN) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("签到中…")
                        } else {
                            Text("签到领积分")
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                    Text("自动任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "每个按钮独立执行，长按可对全部账号执行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BonusButton(
                        label = "连登管家",
                        hint = "兑换已解锁档位 + 抽奖 + 补签",
                        loading = state.loading == Loading.STREAK,
                        enabled = state.loading == null,
                        onClick = onStreakBonus,
                    )
                    BonusButton(
                        label = "活跃上报",
                        hint = "点亮今日连登",
                        loading = state.loading == Loading.ACTIVITY,
                        enabled = state.loading == null,
                        onClick = onActivityReport,
                    )
                    BonusButton(
                        label = "猫猫旅行",
                        hint = "领养 / 派出 / 领取奖励",
                        loading = state.loading == Loading.TRAVEL,
                        enabled = state.loading == null,
                        onClick = onTravel,
                    )
                    BonusButton(
                        label = "夜猫子",
                        hint = "23:00–08:00 时段有效",
                        loading = state.loading == Loading.NIGHT,
                        enabled = state.loading == null,
                        onClick = onNightOwl,
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "余额",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = onRefreshBalance,
                            enabled = state.loading != Loading.BALANCE,
                        ) {
                            if (state.loading == Loading.BALANCE) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("刷新中")
                            } else {
                                Text("刷新")
                            }
                        }
                    }
                    val balance = state.balance
                    when {
                        state.loading == Loading.BALANCE && balance == null -> {
                            CircularWavyProgressIndicator()
                        }
                        balance == null -> Text("点“刷新”查询余额")
                        balance.accounts.isEmpty() -> Text("暂无套餐数据")
                        else -> {
                            Text(
                                "可用合计：${balance.total.toLong()}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            balance.accounts.forEach { acc ->
                                Text(
                                    "${acc.name}：${acc.remain.toLong()} / ${acc.size.toLong()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (balance.cycleEnd.isNotBlank()) {
                                Text(
                                    "周期截止：${balance.cycleEnd}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One entry in the bonus-routine card.
 *
 * Every routine gets its own button because they hit different endpoints, some
 * are only valid inside a time window, and one failing should not make another
 * look unsuccessful.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BonusButton(
    label: String,
    hint: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("$label 执行中…")
        } else {
            Column(Modifier.fillMaxWidth()) {
                Text(label)
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Modal shown when a check-in or bonus routine finishes. */
@Composable
fun CheckinDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("执行结果") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好") }
        },
    )
}

/** Integration guide shown from the help icon. */
@Composable
fun HelpDialog(state: HubState, onDismiss: () -> Unit, onCopyEndpoint: () -> Unit) {
    val endpoint = "http://127.0.0.1:${state.port}/v1"
    val snippet = """
        {
          "providers": {
            "workbuddy": {
              "baseUrl": "$endpoint",
              "api": "openai-completions",
              "apiKey": "${state.secret}",
              "models": [
                { "id": "hy3" }
              ]
            }
          }
        }
    """.trimIndent()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何接入") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("服务提供 OpenAI 兼容接口，任意支持 openai-completions 的客户端均可直接使用。")
                }
                item { Text("1. 在本机启动下方开关"); Text("2. 客户端填入以下地址与密钥") }
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("baseUrl: $endpoint", style = MaterialTheme.typography.bodySmall)
                            Text("apiKey: ${state.secret}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("3. 模型名称见下方列表，点模型名即可复制") }
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("models.json 示例", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(snippet, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("提示：倍率 x0.00 的模型为免费，列表已按倍率从低到高排序。")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopyEndpoint() }) { Text("复制地址") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}


/** Display name for a build. */
internal fun realmName(region: Wire.Region): String =
    if (region == Wire.Region.GLOBAL) "国际版" else "国内版"


/** Result of one check-in attempt, or a batch of them. */
@Composable
fun CheckinDialog(
    message: String,
    items: List<CheckinItem>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.isEmpty()) "签到结果" else "批量签到结果") },
        text = {
            if (items.isEmpty()) {
                Text(message)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text(message, style = MaterialTheme.typography.bodyMedium) }
                    items(items, key = { it.label }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (entry.ok) "✓" else "✗",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (entry.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.width(20.dp),
                            )
                            Column {
                                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}

/** Confirms signing out, which discards every stored credential. */
@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出登录") },
        text = { Text("将清除已保存的登录凭证（国内版与国际版），需要重新登录才能继续使用。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Call history with the usage totals derived from it. */
@Composable
fun CallsScreen(
    state: HubState,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelStats: () -> Unit,
) {
    // Calls arrive from the service without the UI being told, so the page
    // reloads while it is on screen rather than only when it is opened.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            kotlinx.coroutines.delay(2000L)
        }
    }
    // The page follows the account in use: usage is per account, and mixing
    // them would answer neither "how much has this one used" nor "which is
    // nearly spent". The totals and the list are filtered by the same id, so
    // the figures always describe the records that are visible.
    val accountId = state.activeAccountId.orEmpty()
    val records = state.calls.filter { it.accountId == accountId }
    // The summary reports what the endpoint has served in total, not what the
    // list below happens to still hold: the list is capped, so deriving the
    // figures from it would make them fall as older calls age out.
    val totals = state.accountTotals.of(accountId)
    // The pull is a manual reload of what is already on disk; the periodic poll
    // above keeps the list live, and this exists so a user who has just made a
    // call does not have to wait for the next tick.
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A LazyColumn key has to be unique across the list, and a key built from
    // the timestamp and the model is not: since the built-in chat started
    // writing its own turns into this log, one run can finish several calls
    // inside the same millisecond. A duplicated key is not a cosmetic
    // problem — LazyColumn throws on it, and because the key is recomputed on
    // every poll the page dies the moment it is opened. The occurrence count
    // makes each position distinct while leaving a record that is still there
    // on the next poll under the same key.
    val visible = records.asReversed()
    val rowKeys = remember(records) {
        val seen = HashMap<String, Int>(visible.size)
        visible.map { record ->
            val base = record.timestamp.toString() + ":" + record.model
            val repeat = seen[base] ?: 0
            seen[base] = repeat + 1
            if (repeat == 0) base else base + "#" + repeat
        }
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                onRefresh()
                refreshing = false
            }
        },
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "用量统计",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (records.isNotEmpty()) {
                            TextButton(onClick = onClear) { Text("清空") }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "记录设置")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("成功调用", totals.calls.toString())
                        StatCell("失败", totals.failures.toString())
                        StatCell("消耗积分", formatCredits(totals.credits))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("输入 Tokens", totals.promptTokens.toString())
                        StatCell("输出 Tokens", totals.completionTokens.toString())
                        StatCell("合计 Tokens", totals.totalTokens.toString())
                    }
                }
            }
        }

        if (records.isEmpty()) {
            item {
                Text(
                    "这个账号还没有调用记录。通过本地 API 平台发起对话后，这里会显示每次调用的模型、Tokens 与积分消耗。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "最近 ${records.size} 次调用（新→旧）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenModelStats) { Text("模型调用频率") }
                }
            }
            itemsIndexed(visible, key = { index, _ -> rowKeys[index] }) { _, record ->
                CallRow(record, state.models)
            }
        }
    }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CallRow(record: CallRecord, models: List<HubModel>) {
    val failed = record.outcome == CallRecord.Outcome.FAILED
    // The catalogue row when the model is still offered; a delisted model has
    // none, so a stand-in is built from the id to keep the badge meaningful.
    val model = models.firstOrNull { it.id == record.model }
        ?: HubModel(id = record.model, name = record.model)
    // A listed model may have changed price since the call, and the current
    // figure is the one a reader is deciding against.
    val multiplier = model.multiplier.takeIf { it >= 0 } ?: record.multiplier
    // A tinted card rather than coloured text alone: the outcome is the first
    // thing a reader wants from a list, and colour carries it faster than a
    // word does. The tint is kept faint so the figures stay readable.
    val accent = if (failed) MaterialTheme.colorScheme.error else SUCCESS_GREEN
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = if (failed) 0.14f else 0.10f),
        ),
    ) {
        Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VendorBadge(model, Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        record.model.ifEmpty { "(未知模型)" },
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (multiplier >= 0) {
                        Text(
                            "倍率 ${formatMultiplier(multiplier)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    record.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed) {
                Text(
                    "失败 · ${record.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "${record.promptTokens} 入 / ${record.completionTokens} 出 · 消耗 ${formatCredits(record.credits)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Which caller spent the credit. Shown for the built-in chat only:
            // the endpoint is what this page is primarily a record of, and
            // labelling every one of its rows would bury the exceptions.
            if (record.source == CallRecord.Source.AGENT) {
                Text(
                    record.source.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (record.isRemote) {
                // Only shown for network calls: this device is the default and
                // labelling every local row would bury the ones that are not.
                Text(
                    "来自局域网 ${record.sourceIp}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * Colour for a successful call.
 *
 * Not taken from the scheme: the theme's tertiary and secondary slots already
 * mean something else on this screen, and a status colour that shifts with the
 * theme would stop being a status colour.
 */
private val SUCCESS_GREEN = Color(0xFF2E7D32)

/** Credit figures are multipliers, so trailing zeros are trimmed. */
private fun formatCredits(value: Double): String {
    if (value == 0.0) return "0"
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

/**
 * A fresh key for the endpoint.
 *
 * Random rather than memorable: it is pasted into a client once and never
 * typed, and a guessable key is the only thing between a peer and the account.
 */
private fun randomKey(): String =
    "wb-" + (1..24).map { "0123456789abcdef"[java.security.SecureRandom().nextInt(16)] }.joinToString("")

/**
 * Appearance controls for the stealth dot, with a live preview.
 *
 * The preview matters more than the controls: a colour and a diameter are hard
 * to judge as numbers, and what the user is choosing is how visible the
 * indicator is on their own screen.
 */
@Composable
private fun DotSettings(
    state: HubState,
    onDotColor: (Int) -> Unit,
    onDotSize: (Int) -> Unit,
    onDotShape: (DotShape) -> Unit,
    onDotAlpha: (Float) -> Unit,
    onBurnIn: (Boolean) -> Unit,
    onBurnInInterval: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A checkerboard behind the preview would be the honest way to show
            // a faint colour, but the shape alone is enough to judge here.
            val previewColor = Color(state.dotColor).copy(alpha = state.dotAlpha)
            Box(
                modifier = Modifier
                    .size(state.dotSize.dp)
                    .then(
                        when (state.dotShape) {
                            DotShape.FILLED -> Modifier.background(previewColor, CircleShape)
                            DotShape.RING -> Modifier.border(
                                width = if (state.dotSize >= 20) 3.dp else 2.dp,
                                color = previewColor,
                                shape = CircleShape,
                            )
                        },
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "预览",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("形状", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DotShape.entries.forEach { shape ->
                FilterChip(
                    selected = state.dotShape == shape,
                    onClick = { onDotShape(shape) },
                    label = { Text(shape.label) },
                )
            }
        }

        Text("颜色", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DOT_PALETTE.forEach { color ->
                val selected = state.dotColor == color
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(color), CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        )
                        .clickable { onDotColor(color) },
                )
            }
        }

        Text(
            "大小 ${state.dotSize}dp",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = state.dotSize.toFloat(),
            onValueChange = { onDotSize(it.roundToInt()) },
            valueRange = 6f..40f,
            steps = 33,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "透明度 ${(state.dotAlpha * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = state.dotAlpha,
            onValueChange = onDotAlpha,
            // The floor is zero: an invisible dot still keeps the process
            // visible to the system, which is the window's whole purpose.
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("防烧屏", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "圆点会在紧邻的八个位置之间轮换，每个位置都不与上一个重合，" +
                        "让像素有机会熄灭。基准位置不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.burnInEnabled, onCheckedChange = onBurnIn)
        }

        if (state.burnInEnabled) {
            Text(
                "间隔 ${formatInterval(state.burnInIntervalMs)}",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = burnInSliderValue(state.burnInIntervalMs),
                onValueChange = { onBurnInInterval(sliderToInterval(it)) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "最短 1 秒，最长 30 分钟。间隔越短，单个像素点亮的时间越少，" +
                    "但圆点看起来会更活跃。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Maps the interval onto a slider that feels linear.
 *
 * The useful range spans 1 second to 30 minutes, and a linear slider would
 * spend most of its travel above ten minutes, where the setting barely matters.
 * A logarithmic mapping puts the short intervals, which are the ones worth
 * tuning, in the first half.
 */
private fun burnInSliderValue(ms: Long): Float {
    val min = 1_000.0
    val max = 30 * 60 * 1000.0
    val clamped = ms.coerceIn(1_000L, 30L * 60 * 1000).toDouble()
    return (kotlin.math.ln(clamped / min) / kotlin.math.ln(max / min)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToInterval(value: Float): Long {
    val min = 1_000.0
    val max = 30 * 60 * 1000.0
    val ms = min * Math.pow(max / min, value.toDouble())
    // Rounded to whole seconds: sub-second precision is not meaningful here.
    return ((ms / 1000).roundToInt() * 1000L).coerceIn(1_000L, 30L * 60 * 1000)
}

private fun formatInterval(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds} 秒"
        seconds % 60 == 0L -> "${seconds / 60} 分钟"
        else -> "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}

/** Colours offered for the dot: readable on both light and dark backgrounds. */
private val DOT_PALETTE = listOf(
    0xFF34C759.toInt(), // green
    0xFF0A84FF.toInt(), // blue
    0xFFFF9F0A.toInt(), // orange
    0xFFFF3B30.toInt(), // red
    0xFFBF5AF2.toInt(), // purple
    0xFF8E8E93.toInt(), // grey
)

/**
 * How often each model has been used.
 *
 * Every model the account offers is listed, including ones never called: a
 * ranking of used models answers "which is most used" but not "which am I
 * paying for and not using", which is the other half of the question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelStatsSheet(
    state: HubState,
    onDismiss: () -> Unit,
) {
    val accountId = state.activeAccountId.orEmpty()
    val records = state.calls.filter { it.accountId == accountId }
    val counts = records.groupingBy { it.model }.eachCount()
    // Credit is summed rather than averaged: the question the list answers is
    // where the account's spending went, and a per-call average would hide a
    // model that is cheap but called constantly.
    val creditsByModel: Map<String, Double> = records
        .groupBy { it.model }
        .mapValues { entry -> entry.value.sumOf { it.credits } }

    // Listed models first, then any that appear only in the records: those are
    // delisted, and dropping them would lose calls that did happen.
    val listed = state.models.map { model ->
        StatRow(
            model = model,
            count = counts[model.id] ?: 0,
            credits = creditsByModel[model.id] ?: 0.0,
        )
    }
    val delisted = counts
        .filterKeys { id -> state.models.none { it.id == id } }
        .map { (id, count) ->
            StatRow(
                model = HubModel(id = id, name = id),
                count = count,
                credits = creditsByModel[id] ?: 0.0,
                fallbackMultiplier = records.firstOrNull { it.model == id }?.multiplier ?: -1.0,
            )
        }
    val rows = (listed + delisted).sortedWith(
        compareByDescending<StatRow> { it.count }.thenBy { it.model.name },
    )
    val totalCredits = records.sumOf { it.credits }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    "模型调用频率",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "该账号共 ${records.size} 次调用、消耗 ${formatCredits(totalCredits)} 积分；" +
                        "按次数排序，未使用的模型也会列出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
            }
            items(rows) { row -> ModelStatRow(row, records.size) }
        }
    }
}

/** One model and what it has been used for. */
private data class StatRow(
    val model: HubModel,
    val count: Int,
    /** Credit spent on this model. */
    val credits: Double = 0.0,
    /**
     * Multiplier to show when the catalogue no longer carries the model.
     *
     * A listed model's current figure wins over this, so a price change is
     * visible rather than hidden behind what the call happened to cost.
     */
    val fallbackMultiplier: Double = -1.0,
) {
    val multiplier: Double get() = model.multiplier.takeIf { it >= 0 } ?: fallbackMultiplier
}

@Composable
private fun ModelStatRow(row: StatRow, totalCalls: Int) {
    // Share of all calls, which is the figure that makes a ranking readable:
    // "12 times" means little without knowing whether that is most of the
    // traffic or a rounding error.
    val share = if (totalCalls > 0) row.count * 100.0 / totalCalls else 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VendorBadge(row.model, Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.model.name.ifEmpty { row.model.id },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildList {
                if (row.multiplier >= 0) add("倍率 ${formatMultiplier(row.multiplier)}")
                if (row.credits > 0) add("消耗 ${formatCredits(row.credits)} 积分")
            }.joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${row.count} 次",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (row.count > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                String.format(Locale.US, "%.1f%%", share),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Renders a price multiplier, trimming a trailing zero. */
private fun formatMultiplier(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }

/**
 * Offers to rewrite the client's self-identification.
 *
 * Shown when the Anthropic dialect is switched on, because that is the point at
 * which the user is most likely to be pointing Claude Code at this endpoint, and
 * the upstream refuses that client's own identity string. The prompt states what
 * is rewritten rather than doing it silently.
 */
@Composable
fun DisguisePromptDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要伪装客户端身份吗？") },
        text = {
            Text(
                "WorkBuddy 上游会主动拦截 Claude Code 的身份声明，" +
                    "被拦截时请求会直接失败。\n\n" +
                    "如果你使用的是 Claude Code，请开启伪装：" +
                    "只改写那一句身份声明（去掉一个词），" +
                    "模型的能力、你的提示词与工具定义都不受影响。\n\n" +
                    "使用其它客户端时保持关闭即可。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("开启伪装") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不开启") } },
    )
}

/**
 * Advanced settings for the endpoint.
 *
 * Kept behind a button rather than shown inline: these change how the endpoint
 * behaves for clients, not what it serves, and most users never touch them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeAdvancedSheet(
    state: HubState,
    onDismiss: () -> Unit,
    onToggleAggregate: (Boolean) -> Unit,
    onToggleAnthropic: (Boolean) -> Unit,
    onToggleResponses: (Boolean) -> Unit,
    onToggleHttps: (Boolean) -> Unit,
    onToggleDisguise: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "高级设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("支持非流式请求", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "开启后，请求里 stream 为 false 或未指定的客户端会收到一个完整响应；" +
                            "关闭时一律返回流式结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.aggregateNonStream, onCheckedChange = onToggleAggregate)
            }

            Text(
                "上游只支持流式输出，因此非流式响应是在本机收齐后再返回的：" +
                    "首字节会等到模型生成完毕，较长的回答在客户端看来会有一段等待。" +
                    "不需要该功能的客户端保持关闭即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                "协议转换",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "上游只提供 OpenAI Chat Completions，开启后本机可以把它的请求与响应" +
                    "翻译成下面这些协议，供使用对应协议的客户端接入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Anthropic Messages API", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "提供 /v1/messages，供 Claude Code 等客户端使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.formatAnthropic, onCheckedChange = onToggleAnthropic)
            }

            // Shown for every dialect, not just the translated ones: the
            // gateway refuses the same passages whichever protocol the client
            // speaks, and an OpenAI-compatible client (DSH Go, an editor
            // plugin, a script) is exactly the case that used to have no way
            // to turn this on.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("请求兼容改写", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "上游会拦截部分客户端与运行时的自我声明（Claude Code、Codex、DSH 等）。" +
                            "开启后只改写这几句声明，提示词、工具定义与模型能力都不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.disguiseIdentity, onCheckedChange = onToggleDisguise)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OpenAI Responses API", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "提供 /v1/responses，供使用新版 Responses 协议的客户端使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.formatResponses, onCheckedChange = onToggleResponses)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HTTPS 端点", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "同一个接口额外在 ${state.port + 1} 端口提供 TLS 版本，" +
                            "需要信任本应用签发的证书；明文端口不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.httpsEnabled, onCheckedChange = onToggleHttps)
            }
        }
    }
}

/**
 * Settings for the call history, shown as a bottom sheet from the calls page.
 *
 * The record count is a real trade the user is making — history against storage
 * on their own device — so the sheet states the resulting size rather than
 * presenting the number as a free choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSettingsSheet(
    state: HubState,
    onDismiss: () -> Unit,
    onLimitChange: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "记录设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                "保留条数 ${state.callLogLimit}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = callLimitSliderValue(state.callLogLimit),
                onValueChange = { onLimitChange(sliderToCallLimit(it)) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("10 万", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                "约占用 ${formatBytes(state.callLogBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "调用记录保存在本机，不会上传。条数越多，占用空间越大；" +
                    "超出上限时最旧的记录会被自动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                "用量统计是累计值，不受保留条数影响，清空记录也不会重置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Maps the record limit onto a slider.
 *
 * Linear would spend most of its travel above ten thousand, where the setting
 * is a storage decision rather than a viewing one; logarithmic puts the range
 * most people actually choose in the first half.
 */
private fun callLimitSliderValue(limit: Int): Float {
    val min = 100.0
    val max = 100_000.0
    val clamped = limit.coerceIn(100, 100_000).toDouble()
    return (kotlin.math.ln(clamped / min) / kotlin.math.ln(max / min)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToCallLimit(value: Float): Int {
    val min = 100.0
    val max = 100_000.0
    val raw = min * Math.pow(max / min, value.toDouble())
    // Rounded to a readable step: the exact count is not a meaningful choice,
    // and a slider that reports 47318 invites precision that does not exist.
    val step = when {
        raw < 1_000 -> 50
        raw < 10_000 -> 100
        raw < 50_000 -> 500
        else -> 1_000
    }
    return ((raw / step).roundToInt() * step).coerceIn(100, 100_000)
}

/**
 * One piece of connection information with a copy button.
 *
 * The values are what a user pastes into a client, so the copy action sits on
 * the row itself rather than in a separate button that copies both at once:
 * clients want one or the other, and a combined blob needs editing afterwards.
 */
@Composable
private fun EndpointRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "复制 $label",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Human-readable size for the history estimate. */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
