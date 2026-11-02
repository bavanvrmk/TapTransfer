package com.taptransfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun LiquidAction(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    emphasis:Int=2,content:@Composable RowScope.()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed && enabled) .965f else 1f,spring(stiffness=550f),label="control press")
    var contact by remember {mutableStateOf(Offset.Unspecified)}
    LaunchedEffect(interaction){interaction.interactions.collect { if(it is PressInteraction.Press)contact=it.pressPosition }}
    val glint by animateFloatAsState(if(pressed && enabled) .20f else .035f,spring(stiffness=450f),label="glass reflection")
    val color=MaterialTheme.colorScheme.primary
    Button(onClick,modifier=modifier.heightIn(min=48.dp).graphicsLayer { scaleX=scale; scaleY=scale }.glass().drawWithContent {
            drawContent()
            val center=if(contact==Offset.Unspecified) Offset(size.width*.25f,0f) else contact
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha=glint),Color.Transparent),center,size.width.coerceAtLeast(1f)*.75f))
        },
        enabled=enabled,interactionSource=interaction,shape=RoundedCornerShape(26.dp),
        colors=ButtonDefaults.buttonColors(containerColor=color.copy(alpha=when(emphasis){2->.22f;1->.085f;else->.035f}),
            contentColor=if(emphasis==2) MaterialTheme.colorScheme.onSurface else color,
            disabledContainerColor=Color.Transparent,disabledContentColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.38f)),
        contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp),content=content)
}

@Composable internal fun LiquidOutline(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit)=
    LiquidAction(onClick,modifier,enabled,1,content)
@Composable internal fun LiquidText(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit)=
    LiquidAction(onClick,modifier,enabled,0,content)
@Composable internal fun LiquidChip(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit) {
    FilterChip(selected,onClick,label=label,modifier=Modifier.heightIn(min=48.dp),
        shape=RoundedCornerShape(24.dp),colors=FilterChipDefaults.filterChipColors(
            containerColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.055f),
            selectedContainerColor=MaterialTheme.colorScheme.primary.copy(alpha=.25f),
            selectedLabelColor=MaterialTheme.colorScheme.onSurface),
        leadingIcon=if(selected) ({ Text("✓") }) else null)
}
@Composable internal fun LiquidCard(modifier:Modifier=Modifier,shape:androidx.compose.ui.graphics.Shape=RoundedCornerShape(26.dp),
    onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    val surface=modifier.glass()
    val colors=CardDefaults.cardColors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface)
    if(onClick==null) Card(surface,shape,colors,content=content)
    else Card(onClick=onClick,modifier=surface,shape=shape,colors=colors,content=content)
}

@Composable internal fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .09f),
    unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f)
)
