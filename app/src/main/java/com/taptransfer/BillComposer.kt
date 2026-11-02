package com.taptransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private fun prepareReceipt(context:Context, uri:Uri):ByteArray {
    fun stream()=requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the receipt." }
    val signature=stream().use { it.readNBytesCompat(8) }
    val type=receiptType(signature)
    if(type=="application/pdf") return stream().bounded(2*1024*1024)
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    stream().use { BitmapFactory.decodeStream(it,null,bounds) }
    require(bounds.outWidth>0 && bounds.outHeight>0) { "Could not read this photo." }
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2400) sample*=2
    val bitmap=stream().use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample}) } ?: error("Could not open image")
    try {
        for(quality in listOf(90,75,55,35)) {
            val out=ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out))
            if(out.size()<=2*1024*1024) return out.toByteArray()
        }
        error("Choose a smaller receipt photo.")
    } finally {bitmap.recycle()}
}
private fun java.io.InputStream.readNBytesCompat(n:Int):ByteArray {
    val bytes=ByteArray(n);var used=0
    while(used<n){val count=read(bytes,used,n-used);if(count<=0)break;used+=count}
    return bytes.copyOf(used)
}
@Composable
fun BillComposer(trip:Trip, advanced:()->Unit, cancel:()->Unit, save:(TripEntry)->Unit) {
    val ctx=LocalContext.current
    val prefs=remember {ctx.getSharedPreferences("trip_identity",Context.MODE_PRIVATE)}
    val scope=rememberCoroutineScope()
    var me by rememberSaveable(trip.id) {mutableStateOf(prefs.getString(trip.id,"").orEmpty())}
    var payer by rememberSaveable {mutableStateOf("")}
    var isPaid by rememberSaveable {mutableStateOf(false)}
    var title by rememberSaveable {mutableStateOf("")}
    var amount by rememberSaveable {mutableStateOf("")}
    var method by rememberSaveable {mutableStateOf("Cash")}
    var customize by rememberSaveable {mutableStateOf(false)}
    var included by rememberSaveable {mutableStateOf(ArrayList(trip.members))}
    var error by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var draft by rememberSaveable {mutableStateOf("")}
    var cameraPath by rememberSaveable {mutableStateOf("")}
    fun attach(uri:Uri,captured:File?=null) {
        busy=true
        scope.launch {
            try {
                val next=withContext(Dispatchers.IO) {
                    val bytes=prepareReceipt(ctx,uri)
                    File.createTempFile("receipt-",".bin",ctx.cacheDir).apply{writeBytes(bytes)}.absolutePath
                }
                if(draft.isNotEmpty())File(draft).delete()
                draft=next;error=""
            } catch(e:Exception){error=e.message ?: "Unable to attach receipt."}
            finally {captured?.delete();busy=false}
        }
    }
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let { uri->attach(uri) }}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        if(cameraPath.isNotBlank()) {
            val photo=File(cameraPath);cameraPath=""
            if(ok) attach(Uri.fromFile(photo),photo) else photo.delete()
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cutoff=System.currentTimeMillis()-86400000L
            ctx.cacheDir.listFiles()?.filter{it.name.startsWith("receipt-") && it.name.endsWith(".bin") && it.absolutePath!=draft && it.lastModified()<cutoff}?.forEach{it.delete()}
        }
    }
    var thumbnail by remember(draft) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(draft) {
        thumbnail=if(draft.isBlank()) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(draft,BitmapFactory.Options().apply { inSampleSize=4 })
        }
    }
    GlassPanel {
        Text("Add a bill",style=MaterialTheme.typography.headlineMedium)
        Text(if(draft.isEmpty()) "Attach a receipt if you have one." else "✓ Receipt attached",color=MaterialTheme.colorScheme.primary)
        thumbnail?.let { Image(it.asImageBitmap(),"Attached receipt preview",Modifier.fillMaxWidth().height(120.dp),contentScale=ContentScale.Fit) }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            LiquidOutline(enabled=!busy,onClick={
                runCatching{
                    val folder=File(ctx.cacheDir,"bill-camera").apply{mkdirs()}
                    val file=File.createTempFile("photo-",".jpg",folder);cameraPath=file.absolutePath
                    camera.launch(FileProvider.getUriForFile(ctx,ctx.packageName+".files",file))
                }.onFailure{error="Camera unavailable. Choose a photo or PDF instead."}
            }) {Text("Camera")}
            LiquidOutline(enabled=!busy,onClick={choose.launch(arrayOf("image/jpeg","image/png","application/pdf"))}){Text("Photo or PDF")}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(draft.isNotEmpty())LiquidText(onClick={File(draft).delete();draft=""}){Text("Remove receipt")}
    }
    Input("Bill name",title){title=it.take(200)}
    Input("Amount (₹)",amount,true){amount=it.take(11)}
    Pick("I am",me,trip.members){me=it;prefs.edit().putString(trip.id,it).apply()}
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        LiquidChip(!isPaid,{isPaid=false},{Text("To pay")})
        LiquidChip(isPaid,{isPaid=true},{Text("Already paid")})
    }
    val person=payer.ifBlank {me}
    Pick(if(isPaid)"Paid by" else "Who will pay?",if(person==me && me.isNotBlank()) "$me (me)" else person,trip.members){payer=it}
    if(me.isNotBlank() && person!=me) LiquidText(onClick={payer=me}){Text(if(isPaid)"I paid" else "I'll pay")}
    AnimatedVisibility(isPaid){Pick("How",method,listOf("Cash","UPI","Card","Other")){method=it}}
    LiquidText(onClick={customize=!customize}){Text("Split equally · "+included.size+" people   ›")}
    AnimatedVisibility(customize) {
        Column {trip.members.forEach { member->
            LiquidChip(member in included,{included=ArrayList(if(member in included)included-member else included+member)},{Text(member)})
        }}
    }
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    LiquidAction(enabled=!busy,onClick={
        runCatching {
            require(me in trip.members){"Choose your name first."}
            require(title.isNotBlank()){"Give the bill a name."}
            val total=paise(amount)
            val split=allocate(total,included.associateWith{"1"},Split.Equal)
            val receipt=if(draft.isBlank()) "" else Base64.encodeToString(File(draft).readBytes(),Base64.NO_WRAP)
            val entry=TripEntry(tripId(),System.currentTimeMillis(),if(isPaid)"Expense" else "Assigned",
                title.trim(),if(isPaid)method else "Other",mapOf(person to total),split,receipt,me)
            trip.copy(entries=trip.entries+entry).totals();save(entry)
        }.onFailure {error=it.message ?: "Check the bill details."}
    },modifier=Modifier.fillMaxWidth()){Text(if(isPaid)"Record paid bill" else "Assign bill")}
    LiquidText(onClick=advanced){Text("Other entries and advanced splits")}
    LiquidText(onClick=cancel){Text("Cancel")}
}

