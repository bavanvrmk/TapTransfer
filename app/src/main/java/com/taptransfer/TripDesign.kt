package com.taptransfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TripBanner(title:String, eyebrow:String, subtitle:String) {
    GlassPanel {
        Text(eyebrow,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable
fun WalletOverview(trip:Trip,totals:TripTotals) {
    LiquidCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Group wallet",style=MaterialTheme.typography.labelLarge)
            Text("₹"+rupees(totals.wallet),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold)
            HorizontalDivider()
            Text("Held by "+trip.keeper+"   ·   Spent ₹"+rupees(totals.spent))
        }
    }
    Text("Member balances",style=MaterialTheme.typography.titleLarge)
    Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
            totals.balances.entries.forEachIndexed { index,(person,balance)->
                if(index>0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(person,Modifier.weight(1f),fontWeight=FontWeight.Medium)
                    Text(when {balance>0->"Due ₹"+rupees(balance);balance<0->"Owes ₹"+rupees(-balance);else->"Settled"},
                        color=if(balance<0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
@Composable
fun DriveCard(status:String, busy:Boolean,save:()->Unit,importCopies:()->Unit) {
    LiquidCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Offline backup",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(status,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            Text("Export or restore a portable copy including receipts.")
            LiquidAction(save,Modifier.fillMaxWidth(),enabled=!busy){Text("Save trip copy…")}
            LiquidOutline(importCopies,Modifier.fillMaxWidth(),enabled=!busy){Text("Import members’ copies…")}
            Text("In the file picker, open the location menu and select Drive. Each copy includes all entries and receipts.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

