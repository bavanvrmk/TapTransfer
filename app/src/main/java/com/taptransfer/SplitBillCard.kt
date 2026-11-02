package com.taptransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SplitBillCard(useAmount: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var total by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("2") }
    LiquidOutline(onClick={open=!open}) { Text(if(open) "Close bill splitter" else "Split a bill") }
    if (!open) return
    LiquidCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Split equally", style=MaterialTheme.typography.titleLarge)
            Text("Include yourself in the headcount. Choose one share to request.")
            OutlinedTextField(total,{total=it.take(9)},label={Text("Bill total (₹)")},singleLine=true,colors=glassFieldColors(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            OutlinedTextField(count,{count=it.filter(Char::isDigit).take(3)},label={Text("People")},singleLine=true,colors=glassFieldColors(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            val result = runCatching { splitBill(total,count.toIntOrNull() ?: 0) }
            result.getOrNull()?.forEach { part ->
                LiquidText(onClick={useAmount(part.amount);open=false}) { Text("Use ₹"+part.amount+" · "+part.people+" share(s)") }
            }
            if(total.isNotEmpty() && result.isFailure) Text(result.exceptionOrNull()?.message.orEmpty(),color=MaterialTheme.colorScheme.error)
        }
    }
}
