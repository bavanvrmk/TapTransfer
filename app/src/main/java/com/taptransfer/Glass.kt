package com.taptransfer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable fun GlassBackdrop(page:String) {
    val position by animateFloatAsState(if(page=="Home" || page=="Trips") .18f else .78f, spring(stiffness=65f), label="ambient position")
    Canvas(Modifier.fillMaxSize()) {
        val radius=size.width*.85f
        drawCircle(Brush.radialGradient(listOf(Color(0x384D77E8),Color.Transparent),Offset(size.width*position,size.height*.18f),radius),
            radius,Offset(size.width*position,size.height*.18f))
        val bottom=Offset(size.width*.9f,size.height*.90f)
        drawCircle(Brush.radialGradient(listOf(Color(0x255963AB),Color.Transparent),bottom,radius),radius,bottom)
    }
}
@Composable fun Modifier.glass():Modifier {
    val shape=RoundedCornerShape(26.dp)
    return clip(shape).background(Brush.linearGradient(listOf(
        MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),
        MaterialTheme.colorScheme.onSurface.copy(alpha=.045f)
    ))).border(1.dp,Brush.linearGradient(listOf(MaterialTheme.colorScheme.onSurface.copy(alpha=.24f),MaterialTheme.colorScheme.onSurface.copy(alpha=.06f))),shape)
}
@Composable fun GlassPanel(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().glass().animateContentSize().padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
