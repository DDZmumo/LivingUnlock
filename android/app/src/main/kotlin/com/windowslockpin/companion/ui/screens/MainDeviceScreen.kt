package com.windowslockpin.companion.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windowslockpin.companion.R
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.ui.theme.*

@Composable
fun MainDeviceScreen(
    pairedDevices: List<PairedPcRecord>,
    isListeningEnabled: Boolean,
    onToggleListening: (Boolean) -> Unit,
    onScanQrCode: () -> Unit,
    onUnpairDevice: (PairedPcRecord) -> Unit,
    onDeviceClick: (PairedPcRecord) -> Unit = {},
    displayName: (PairedPcRecord) -> String = { it.pcName },
    onOpenSettings: () -> Unit = {}
) {
    var deviceToUnpair by remember { mutableStateOf<PairedPcRecord?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Fullscreen character background
        Image(
            painter = painterResource(id = R.drawable.bg_livingunlock),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Semi-transparent gradient overlay for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB30A192F),
                            Color(0xCC0D1B2A),
                            Color(0xF2070E1A)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE6132238))
                    .border(1.dp, Color(0x5500E5FF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_livingunlock),
                    contentDescription = "LivingUnlock",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LivingUnlock",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "近场蓝牙与生物安全解锁",
                        fontSize = 12.sp,
                        color = Color(0xFF9EADBF)
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = CyanAccent)
                }
                // Background listening switch
                Switch(
                    checked = isListeningEnabled,
                    onCheckedChange = onToggleListening,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = IceBluePrimary,
                        uncheckedThumbColor = SlateGray,
                        uncheckedTrackColor = Color(0x33000000)
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已绑定的电脑设备",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE3F0FC)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${pairedDevices.size} 台",
                    fontSize = 13.sp,
                    color = IceBluePrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Device List or Empty Placeholder
            if (pairedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xD9132238))
                        .border(1.dp, Color(0x4000E5FF), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0x330088FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "尚未绑定任何电脑",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击下方按钮扫描电脑端显示的二维码完成配对后，即可在电脑锁屏时享受近场一键解锁体验。",
                            fontSize = 13.sp,
                            color = Color(0xFF9EADBF),
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pairedDevices) { record ->
                        PairedDeviceCard(
                            record = record,
                            name = displayName(record),
                            onClick = { onDeviceClick(record) },
                            onUnpairClick = { deviceToUnpair = record }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scan QR code button
            Button(
                onClick = onScanQrCode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = IceBluePrimary,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "扫码绑定新电脑",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Unpair confirmation dialog
    deviceToUnpair?.let { record ->
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = {
                Text(
                    text = "解除绑定",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = "确定解除与电脑【${displayName(record)}】的配对关系吗？解除后电脑锁屏将无法通过此手机进行近场解锁。",
                    color = Color(0xFFC5D5E4),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnpairDevice(record)
                        deviceToUnpair = null
                    }
                ) {
                    Text("解除绑定", color = SoftRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text("取消", color = Color(0xFF8EB2D5))
                }
            },
            modifier = Modifier.border(1.dp, Color(0x4000E5FF), RoundedCornerShape(20.dp)),
            containerColor = Color(0xF2101E33),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun PairedDeviceCard(
    record: PairedPcRecord,
    name: String = record.pcName,
    onClick: () -> Unit = {},
    onUnpairClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick=onClick)
            .background(Color(0xE6132238))
            .border(1.dp, Color(0x4000E5FF), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x330088FF)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                tint = CyanAccent,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = IceBluePrimary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = record.bluetoothMac.value,
                    fontSize = 12.sp,
                    color = Color(0xFF9EADBF)
                )
            }
        }

        // Right side: ONLY Unpair button, as required
        IconButton(
            onClick = onUnpairClick,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x1AFF4D4F))
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "解除绑定",
                tint = SoftRed,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
