package com.windowslockpin.companion.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.windowslockpin.companion.R
import com.windowslockpin.companion.time.TimePreparation

@Composable
fun TimePreparationScreen(preparation: TimePreparation,onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.bg_livingunlock),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        Box(Modifier.fillMaxSize().background(Color(0xDD101D2A)))
        Surface(Modifier.align(Alignment.Center).padding(28.dp).widthIn(max=420.dp),
            shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=0.94f)) {
            Column(Modifier.padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(24.dp)) {
                Text("${preparation.pcName} 请求解锁",style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                CircularProgressIndicator()
                Text(preparation.message,style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
                Text("准备完成后，你可以确认解锁并验证指纹。",textAlign=TextAlign.Center)
                OutlinedButton(onClick=onCancel,modifier=Modifier.fillMaxWidth()){Text("取消")}
            }
        }
    }
}
