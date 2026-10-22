package com.windowslockpin.companion.ui.screens

import android.animation.ValueAnimator
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.windowslockpin.companion.time.AppTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ApplicationTimeBar() {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    val formatter=remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    var time by remember { mutableStateOf(formatter.format(Instant.ofEpochMilli(AppTime.clock.snapshot().utcMs))) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("应用内时间") }
    val reduceMotion=!ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                val now=AppTime.clock.snapshot().utcMs
                time=formatter.format(Instant.ofEpochMilli(now))
                delay((1000-now%1000).coerceIn(50,1000))
            }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min=54.dp).clip(RoundedCornerShape(20.dp))
        .background(Color(0xE6132238)).border(1.dp,Color(0x5500E5FF),RoundedCornerShape(20.dp))
        .padding(horizontal=14.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(status,fontSize=11.sp,color=Color(0xFF9EADBF),maxLines=1)
            Row(Modifier.clearAndSetSemantics {contentDescription="应用内时间 $time"},verticalAlignment=Alignment.CenterVertically) {
                time.forEachIndexed { index,digit ->
                    if(digit==':')Text(":",fontSize=21.sp,fontFamily=FontFamily.Monospace,color=Color(0xFF7ACFFF))
                    else {
                        val width=with(LocalDensity.current){21.sp.toDp()*0.7f}
                        val height=with(LocalDensity.current){27.sp.toDp()}
                        Box(Modifier.width(width).height(height).clipToBounds(),contentAlignment=Alignment.Center) {
                            AnimatedContent(digit,transitionSpec={
                                if(reduceMotion)EnterTransition.None togetherWith ExitTransition.None
                                else {
                                    val easing=CubicBezierEasing(0.23f,1f,0.32f,1f)
                                    (slideInVertically(tween(180,easing=easing)){it}+fadeIn(tween(180))) togetherWith
                                        (slideOutVertically(tween(180,easing=easing)){-it}+fadeOut(tween(180)))
                                }
                            },label="clock_digit_$index") { value ->
                                Text(value.toString(),fontSize=21.sp,fontFamily=FontFamily.Monospace,color=Color(0xFFDCF4FF))
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick={
            if(!busy) {
                busy=true;status="正在校准应用时间"
                scope.launch {
                    try {
                        status=if(AppTime.calibrateManually(context))"应用内时间 · 已校准" else "本次取时失败，保留内部时钟"
                    } finally {busy=false}
                }
            }
        },enabled=!busy,modifier=Modifier.height(48.dp),contentPadding=PaddingValues(horizontal=10.dp)) {
            if(busy)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
            else Text("校准",fontSize=13.sp,color=Color(0xFF7ACFFF))
        }
    }
}
