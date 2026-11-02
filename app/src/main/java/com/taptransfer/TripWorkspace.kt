package com.taptransfer

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable internal fun Pick(label:String,current:String,options:List<String>,choose:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LiquidOutline(onClick={open=true}) { Text(label+" · "+current.ifEmpty{"Choose"}) }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            options.forEach { DropdownMenuItem(text={Text(it)},onClick={choose(it);open=false}) }
        }
    }
}
@Composable internal fun Input(label:String,value:String,number:Boolean=false,change:(String)->Unit) {
    OutlinedTextField(value,change,label={Text(label)},singleLine=true,colors=glassFieldColors(),modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=if(number) KeyboardType.Decimal else KeyboardType.Text))
}
private fun fileIntent(ctx:Context,file:File,type:String,share:Boolean) {
    val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".files",file)
    val intent=if(share) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM,uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri,type)
    intent.clipData=ClipData.newRawUri("Trip attachment",uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent,if(share) "Share trip with members" else "View receipt"))
}
@OptIn(ExperimentalFoundationApi::class)
@Composable fun TripWorkspace() {
    val ctx=LocalContext.current
    val links=remember {ctx.getSharedPreferences("trip_links",Context.MODE_PRIVATE)}
    var networkPanel by remember { mutableStateOf(false) }
    var joinText by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var serverKey by remember { mutableStateOf("") }
    var reverseEntry by remember { mutableStateOf<TripEntry?>(null) }
    var link by remember { mutableStateOf("") }
    val files=remember { TripFiles(ctx) }; val scope=rememberCoroutineScope()
    var names by remember { mutableStateOf(emptyList<Pair<String,String>>()) }
    var active by remember { mutableStateOf<Trip?>(null) }
    var selected by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("Overview") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<TripEntry?>(null) }
    var form by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var search by rememberSaveable(selected) { mutableStateOf("") }
    var activityKind by rememberSaveable(selected) { mutableStateOf("All entries") }
    androidx.activity.compose.BackHandler(enabled=!busy && (active!=null || form)) {
        if(form) form=false else {active=null;selected="";tab="Overview"}
    }
    var pending by remember { mutableStateOf<Trip?>(null) }
    fun task(block:suspend ()->Unit) {
        if(busy) return
        busy=true
        scope.launch { try { block() } catch(e:Exception) { message=e.message ?: "Could not complete action." } finally { busy=false } }
    }
    suspend fun accept(t:Trip) {
        withContext(Dispatchers.IO){files.save(t)}
        active=t;selected=t.id;link=links.getString(t.id,"").orEmpty();names=(names.filterNot{it.first==t.id}+(t.id to t.name));form=false;tab="Overview"
    }
    val sheetLinks=remember {ctx.getSharedPreferences("trip_sheets",Context.MODE_PRIVATE)}
    var sheetKey by remember {mutableIntStateOf(0)}
    val sheet=remember(selected,sheetKey){sheetLinks.getString(selected,null)}
    var token by remember {mutableStateOf("")}
    var inviteLink by rememberSaveable {mutableStateOf("")}
    var inviteEmail by rememberSaveable {mutableStateOf("")}
    var sheetStatus by remember(selected){mutableStateOf("")}
    var deleteTarget by remember {mutableStateOf<Pair<String,String>?>(null)}
    var menuTarget by remember {mutableStateOf<Pair<String,String>?>(null)}
    var undoDelete by remember { mutableStateOf<(suspend ()->Unit)?>(null) }
    val haptic=LocalHapticFeedback.current
    val authorize=rememberSheetAuthorization({token=it;message="Google connected."},{message=it})
    suspend fun syncSheet(local:Trip, id:String) {
        val merged=withContext(Dispatchers.IO){SharedSheet(token).sync(id,local)}
        withContext(Dispatchers.IO){files.save(merged)}
        if(selected==local.id)active=merged
        sheetStatus="Up to date · "+java.text.DateFormat.getTimeInstance().format(java.util.Date())
    }
    LaunchedEffect(selected,sheet,token,form) {
        val id=sheet
        if(id!=null && token.isNotBlank() && !form) {
            while(true) {
                if(!busy && active?.id==selected) {
                    busy=true
                    try { syncSheet(requireNotNull(active),id) }
                    catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(e:Exception){sheetStatus="Sync paused · "+e.message.orEmpty()}
                    finally {busy=false}
                }
                kotlinx.coroutines.delay(60000)
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            names=withContext(Dispatchers.IO){files.ids().map{it to files.read(it).name}}
            if(selected.isNotBlank()) {active=withContext(Dispatchers.IO){files.read(selected)};link=links.getString(selected,"").orEmpty()}
        } catch(e:Exception){message=e.message ?: "Could not load trips."}
    }
    val copyState=remember { ctx.getSharedPreferences("trip_copy_history",Context.MODE_PRIVATE) }
    var exportTripId by rememberSaveable { mutableStateOf("") }
    var copyRevision by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var importCount by remember { mutableIntStateOf(0) }
    var addedCount by remember { mutableIntStateOf(0) }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->
        val id=exportTripId
        exportTripId=""
        if(uri!=null && id.isNotBlank()) task {
            val count=withContext(Dispatchers.IO) {
                val snapshot=files.read(id)
                requireNotNull(ctx.contentResolver.openOutputStream(uri,"wt")) { "Could not open the destination." }
                    .use { it.write(snapshot.encode().toByteArray(Charsets.UTF_8)) }
                snapshot.entries.size
            }
            copyState.edit().putInt("count."+id,count).putLong("time."+id,System.currentTimeMillis()).apply()
            copyRevision++
            message="Copy saved to the selected location. If you chose the shared Drive folder, members can import it there."
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){ uris->
        if(uris.isNotEmpty()) task {
            val expected=active?.id
            val preview=withContext(Dispatchers.IO) {
                require(uris.size<=20) { "Choose up to 20 copies of one trip at a time." }
                val copies = uris.distinct().asSequence().map { uri ->
                    val stream = requireNotNull(ctx.contentResolver.openInputStream(uri)) { "Could not read the selected file." }
                    decodeTrip(stream.bounded(TRIP_LIMIT).toString(Charsets.UTF_8))
                }.iterator()
                val first = copies.next()
                require(expected == null || expected == first.id) { "This copy belongs to another trip. Open All trips to import it." }
                val existing = if (first.id in files.ids()) files.read(first.id) else null
                val baseline = existing ?: first
                val result = baseline.mergeCopies(sequenceOf(first) + copies.asSequence())
                require(result.encode().toByteArray().size <= TRIP_LIMIT) { "Combined trip exceeds the file size limit." }
                result to (result.entries.size - (existing?.entries?.size ?: 0))
            }
            pending=preview.first;addedCount=preview.second;importCount=uris.distinct().size
        }
    }
    fun export(t:Trip) {
        exportTripId=t.id
        val title=t.name.filter { it.isLetterOrDigit() || it in " -_" }.take(48).ifBlank { "Trip" }
        exporter.launch(title+"-"+java.util.UUID.randomUUID().toString().take(8)+".taptrip")
    }
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val t=active
    if(t==null) {
        TripBanner("Travel together","TRIP MONEY","Bills, cash and shared wallets in one place.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            LiquidAction(onClick={creating=!creating}) {Text(if(creating)"Close form" else "New trip")}
            LiquidOutline(onClick={importer.launch(arrayOf("*/*"))},enabled=!busy){Text("Import from Drive")}
        }
        if(!creating) Text(if(names.isEmpty()) "Create a trip or import a member’s copy to get started." else "Your trips",style=MaterialTheme.typography.titleLarge)
        names.forEach { item->
            fun openTrip()=task {
                active=withContext(Dispatchers.IO){files.read(item.first)}
                selected=item.first;link=links.getString(selected,"").orEmpty();tab="Overview";form=false
            }
            Row(Modifier.fillMaxWidth().glass().combinedClickable(onClickLabel="Open trip",onClick=::openTrip,
                onLongClickLabel="Trip actions",onLongClick={haptic.performHapticFeedback(HapticFeedbackType.LongPress);menuTarget=item})
                .padding(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(item.second,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                LiquidText(onClick={menuTarget=item},enabled=!busy){Text("Actions")}
            }
        }
        GlassPanel {
            Text("Join a shared Sheet",style=MaterialTheme.typography.titleLarge)
            Input("Google Sheets invite link",inviteLink){inviteLink=it}
            if(token.isBlank()) LiquidAction(onClick=authorize){Text("Connect Google")}
            else LiquidAction(enabled=!busy && inviteLink.isNotBlank(),onClick={task{
                val id=sheetIdFrom(inviteLink)
                val remote=withContext(Dispatchers.IO){SharedSheet(token).sync(id)}
                val old=sheetLinks.getString(remote.id,null)
                require(old==null || old==id){"This trip is already linked to a different Sheet."}
                val merged=withContext(Dispatchers.IO){if(remote.id in files.ids()) files.read(remote.id).merge(remote) else remote}
                accept(merged)
                sheetLinks.edit().putString(remote.id,id).apply();sheetKey++;inviteLink="";tab="Sharing"
                message="Joined "+remote.name+". Add a My Drive shortcut from Sharing."
            }}){Text("Join trip")}
        }
        if(creating) {
        var tripName by rememberSaveable { mutableStateOf("") }
        var people by rememberSaveable { mutableStateOf("") }
        val members=people.split(",").map{it.trim()}.filter{it.isNotEmpty()}
        var keeper by rememberSaveable { mutableStateOf("") }
        Input("New trip name",tripName){tripName=it.take(80)}
        Input("Members, separated by commas",people){people=it.take(1500)}
        Pick("Wallet keeper",keeper,members){keeper=it}
        val createTrip: suspend (Boolean)->Unit = { shared ->
            val fresh=Trip(tripId(),tripName.trim(),members,keeper)
            accept(fresh);creating=false
            if(shared) {
                tab="Sharing"
                val id=withContext(Dispatchers.IO){SharedSheet(token).create(fresh)}
                sheetLinks.edit().putString(fresh.id,id).apply();sheetKey++;sheetStatus="Ready to invite members"
            }
        }
        if(token.isBlank()) LiquidOutline(onClick=authorize){Text("Connect Google for shared trips")}
        else LiquidAction(onClick={task{createTrip(true)}},enabled=!busy){Text("Create shared trip")}
        LiquidOutline(onClick={task{createTrip(false)}},enabled=!busy){Text("Create on this phone")}
        }
    } else {
        TripBanner(t.name,"TRIP LEDGER",t.members.size.toString()+" members   ·   "+t.entries.size+" entries")
        LiquidText(onClick={active=null;selected="";form=false},enabled=!busy){Text("← All trips")}
        LiquidText(onClick={menuTarget=t.id to t.name},enabled=!busy){Text("Trip actions")}
        fun append(entry:TripEntry) = task {
            val updated=t.copy(entries=t.entries+entry)
            accept(updated)
            if(sheet!=null && token.isNotBlank()) {
                try { syncSheet(updated,sheet) } catch(e:Exception){sheetStatus="Saved locally · "+e.message.orEmpty()}
            }
            if(sheet==null && link.isNotBlank()) {
                try { accept(withContext(Dispatchers.IO){TripSync.sync(link,updated)}) }
                catch(e:Exception){message="Saved on this phone. Sync again in Sharing. "+e.message.orEmpty()}
            }
        }
        AnimatedContent(if(form) "Bill" else tab,transitionSpec={
            (fadeIn(tween(210))+slideInVertically(tween(260)){it.coerceAtMost(160)/4}) togetherWith fadeOut(tween(120))
        },label="trip section"){scene->
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
        if(scene=="Bill") {
            key(t.id) {
                if(advanced) TripEntryForm(t,{form=false},::append)
                else BillComposer(t,{advanced=true},{form=false},::append)
            }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Overview","Activity","Sharing").forEach { LiquidChip(tab==it,{tab=it},{Text(it)}) }
            }
            if(scene!="Sharing") LiquidAction(onClick={advanced=false;form=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!busy) {Text("＋  Add a bill")}
            val totals=remember(t){t.totals()}
            if(scene=="Overview") {
                val open=remember(t){t.openAssignments()}
                if(open.isNotEmpty()) GlassPanel {
                    Text("To pay",style=MaterialTheme.typography.titleLarge)
                    open.forEach { bill ->
                        LiquidOutline(onClick={resolving=bill},modifier=Modifier.fillMaxWidth()){
                            Text(bill.note+" · ₹"+rupees(bill.paid.values.sum())+" · "+bill.paid.keys.single())
                        }
                    }
                }
                WalletOverview(t,totals)
                if(totals.wallet>0) Text("The wallet still holds ₹"+rupees(totals.wallet)+". Record any cash returned as a withdrawal before final settlement.")
                else {
                    Text("Suggested repayments",style=MaterialTheme.typography.titleMedium)
                    val moves=settlements(totals.balances)
                    if(moves.isEmpty()) { Text("Everyone is settled.") }
                    moves.forEach { (a,b,v)->Text(a+" → "+b+" · ₹"+rupees(v)) }
                }
            } else if(scene=="Sharing") {
                val status=remember(t,copyRevision) {
                    val last=copyState.getLong("time."+t.id,0L)
                    val count=copyState.getInt("count."+t.id,0)
                    if(last==0L) "Saved on this phone · No copy exported yet"
                    else {
                        val date=java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))
                        val extra=t.entries.size-count
                        if(extra>0) "$extra new entries since your last export" else "Last copy saved $date"
                    }
                }
                GlassPanel {
                    Text("Collaborative Sheet",style=MaterialTheme.typography.headlineSmall)
                    Text(if(sheet==null) "Create the group's ledger in your Google Drive. Every member uses the same Sheet." else sheetStatus.ifBlank { "Linked to one shared Sheet" })
                    if(token.isBlank()) LiquidAction(onClick=authorize){Text("Connect Google")}
                    else if(sheet==null) LiquidAction(enabled=!busy,onClick={task{
                        val id=withContext(Dispatchers.IO){SharedSheet(token).create(t)}
                        sheetLinks.edit().putString(t.id,id).apply();sheetKey++;sheetStatus="Sheet initialized"
                    }}){Text("Create shared Sheet")}
                    else {
                        LiquidAction(enabled=!busy,onClick={task{syncSheet(t,sheet)}}){Text("Sync now")}
                        Input("Member’s Google email",inviteEmail){inviteEmail=it}
                        LiquidOutline(enabled=!busy && inviteEmail.isNotBlank(),onClick={task{
                            withContext(Dispatchers.IO){SharedSheet(token).invite(sheet,inviteEmail)}
                            inviteEmail="";message="Google sent the editor invitation. The member can paste the Sheet link into Join a shared Sheet."
                        }}){Text("Invite editor by email")}
                        LiquidOutline(enabled=!busy,onClick={task{
                            withContext(Dispatchers.IO){SharedSheet(token).shortcut(sheet,t.name)}
                            message="My Drive shortcut is ready."
                        }}){Text("Add shortcut to My Drive")}
                    }
                    if(token.isNotBlank()) LiquidText(onClick=authorize){Text("Reconnect Google")}
                    if(sheet!=null) {
                        LiquidText(onClick={ctx.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(sheetLink(sheet))))}){Text("Open shared Sheet")}
                        LiquidText(onClick={ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,sheetLink(sheet)),"Share Sheet link"))}){Text("Share Sheet link")}
                    }
                }
                DriveCard(status,busy,{export(t)},{importer.launch(arrayOf("*/*"))})
            } else {
                Input("Search descriptions or members",search){search=it}
                Pick("Show",activityKind,listOf("All entries","Assigned","Payment","Cancelled","Expense","Contribution","Withdrawal","Repayment","Correction")){activityKind=it}
                val visible=remember(t,search,activityKind) {
                    t.entries.filter { e->
                        (activityKind=="All entries" || e.kind==activityKind) &&
                            (listOf(e.note,e.by,e.method)+e.paid.keys+e.owed.keys+e.items.map{it.label}).any{it.contains(search.trim(),true)}
                    }.sortedByDescending { it.at }
                }
                Text(visible.size.toString()+" entries",style=MaterialTheme.typography.labelLarge)
                if(visible.isEmpty()) Text(if(t.entries.isEmpty()) "Add your first expense or wallet contribution." else "No entries match these filters.")
                visible.forEach { e->
                    LiquidCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(e.note+" · ₹"+rupees(e.paid.values.sum()),style=MaterialTheme.typography.titleMedium)
                        Text(e.kind+" · "+e.method+" · entered by "+e.by)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.at)))
                        fun breakdown(m:Map<String,Long>)=m.entries.joinToString { (k,v)->(if(k==POT) "Group wallet" else k)+" ₹"+rupees(v) }
                        Text((if(e.kind=="Assigned") "Assigned to: " else "From: ")+breakdown(e.paid));Text((if(e.kind in setOf("Expense","Assigned","Payment")) "Shares: " else "To: ")+breakdown(e.owed))
                        if(e.foreign.isNotBlank())Text("Original currency: "+e.foreign)
                        if(e.kind !in setOf("Correction","Assigned","Cancelled") && t.entries.none{it.reverses==e.id}) {
                            LiquidText(onClick={reverseEntry=e}){Text("Reverse entry")}
                        }
                        e.items.forEach { Text(it.label+" · "+breakdown(it.shares)) }
                        if(e.receipt.isNotEmpty()) LiquidText(onClick={task{
                            val (f,mime)=withContext(Dispatchers.IO){
                                val data=Base64.decode(e.receipt,Base64.DEFAULT);val type=receiptType(data)
                                val dir=File(ctx.cacheDir,"trip-share").apply{mkdirs()}
                                File(dir,e.id+"."+type.substringAfter('/')).apply{writeBytes(data)} to type
                            };fileIntent(ctx,f,mime,false)
                        }}){Text("View receipt")}
                    }}
                }
            }
            resolving?.let { bill->
                var actor by remember(bill.id){mutableStateOf(ctx.getSharedPreferences("trip_identity",Context.MODE_PRIVATE).getString(t.id,"").orEmpty())}
                var method by remember(bill.id){mutableStateOf("Cash")}
                AlertDialog(onDismissRequest={resolving=null},title={Text(bill.note)},text={
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text(bill.paid.keys.single()+" is assigned ₹"+rupees(bill.paid.values.sum()))
                        Pick("Recorded by",actor,t.members){actor=it}
                        Pick("Paid with",method,listOf("Cash","UPI","Card","Other")){method=it}
                        LiquidText(enabled=actor in t.members,onClick={resolving=null;append(bill.resolveBill(false,actor,"Other"))}){Text("Cancel assignment")}
                    }
                },confirmButton={LiquidAction(enabled=actor in t.members,onClick={resolving=null;append(bill.resolveBill(true,actor,method))}){Text("Mark as paid")}},
                    dismissButton={LiquidText(onClick={resolving=null}){Text("Close")}})
            }
            reverseEntry?.let { original->
                AlertDialog(onDismissRequest={reverseEntry=null},title={Text("Reverse this entry?")},text={Text("Its amount will be removed from balances. The original and correction stay in activity.")},
                    confirmButton={LiquidText(onClick={reverseEntry=null;run{
                        val reversal=TripEntry(tripId(),System.currentTimeMillis(),"Correction","Correction: "+original.note.take(178),
                            original.method,original.owed,original.paid,by=original.by,reverses=original.id)
                        append(reversal)

                    }}){Text("Reverse")}},dismissButton={LiquidText(onClick={reverseEntry=null}){Text("Keep entry")}})
            }
            if(scene=="Sharing") {
            LiquidOutline(onClick={task{
                val f=withContext(Dispatchers.IO){
                    val dir=File(ctx.cacheDir,"trip-share").apply{mkdirs()}
                    File(dir,tripId()+".csv").apply{writeText(t.report())}
                };fileIntent(ctx,f,"text/csv",true)
            }}){Text("Export spreadsheet report")}
            LiquidOutline(onClick={task{
                val f=withContext(Dispatchers.IO){
                    val dir=File(ctx.cacheDir,"trip-share").apply{mkdirs()}
                    File(dir,tripId()+".taptrip").apply{writeText(t.encode())}
                };fileIntent(ctx,f,"application/octet-stream",true)
            }}){Text("Send copy via another app")}
            }
        }
        }
        }
    }
    if(!form && (t==null || (tab=="Sharing" && sheet==null))) {
        LiquidOutline(onClick={networkPanel=!networkPanel}) {Text(if(networkPanel)"Close server options" else "Advanced · Private server")}
        if(networkPanel) {
            if(t==null) {
                Input("Private trip invite",joinText){joinText=it}
                LiquidAction(enabled=!busy,onClick={task{
                    val invite=joinText.trim()
                    val joined=withContext(Dispatchers.IO){
                        val remote=TripSync.sync(invite)
                        if(remote.id in files.ids()) TripSync.sync(invite,files.read(remote.id)) else remote
                    }
                    links.edit().putString(joined.id,invite).apply();accept(joined);joinText="";message="Joined "+joined.name
                }}){Text("Join shared trip")}
            } else if(link.isBlank()) {
                Text("Connect this trip to your group's HTTPS trip server. The organizer supplies the address and creation key.")
                Input("Server address",serverUrl){serverUrl=it}
                OutlinedTextField(serverKey,{serverKey=it},label={Text("Server creation key")},singleLine=true,colors=glassFieldColors(),
                    visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                LiquidAction(enabled=!busy,onClick={task{
                    val invite=withContext(Dispatchers.IO){TripSync.create(serverUrl,serverKey,t)}
                    links.edit().putString(t.id,invite).apply();link=invite;serverKey="";message="Shared trip created."
                }}) {Text("Enable online sharing")}
            } else {
                Text("Anyone with the private invite can read receipts and add entries. Only share it with trip members.")
                LiquidAction(enabled=!busy,onClick={task{
                    val merged=withContext(Dispatchers.IO){TripSync.sync(link,t)}
                    accept(merged);message="Trip is up to date."
                }}) {Text("Sync trip now")}
                LiquidOutline(onClick={ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT,link),"Invite trip members"))}){Text("Share private invite")}
            }
        }

    }
    menuTarget?.let { item->
        AlertDialog(onDismissRequest={menuTarget=null},title={Text(item.second)},text={Column {
            LiquidOutline(enabled=!busy,onClick={menuTarget=null;task{
                active=withContext(Dispatchers.IO){files.read(item.first)};selected=item.first;link=links.getString(selected,"").orEmpty();tab="Sharing";form=false
            }}){Text("Sharing settings")}
            LiquidText(enabled=!busy,onClick={menuTarget=null;deleteTarget=item}){Text("Delete from this phone",color=MaterialTheme.colorScheme.error)}
        }},confirmButton={LiquidText(onClick={menuTarget=null}){Text("Close")}})
    }
    deleteTarget?.let { item->
        AlertDialog(onDismissRequest={deleteTarget=null},title={Text("Delete "+item.second+"?")},
            text={Text("This removes the local ledger, receipts and sharing settings. Any unshared entries will be lost. Members keep their shared Google Sheet.")},
            confirmButton={LiquidAction(enabled=!busy,onClick={task{
                val backup=withContext(Dispatchers.IO){files.read(item.first).also { files.delete(item.first) }}
                val oldServer=links.getString(item.first,null)
                val oldSheet=sheetLinks.getString(item.first,null)
                val identities=ctx.getSharedPreferences("trip_identity",Context.MODE_PRIVATE)
                val identity=identities.getString(item.first,null)
                val exportTime=copyState.getLong("time."+item.first,0L)
                val count=copyState.getInt("count."+item.first,0)
                undoDelete={
                    accept(backup)
                    links.edit().putString(item.first,oldServer).apply()
                    sheetLinks.edit().putString(item.first,oldSheet).apply()
                    identities.edit().putString(item.first,identity).apply()
                    copyState.edit().putLong("time."+item.first,exportTime).putInt("count."+item.first,count).apply()
                    link=oldServer.orEmpty();sheetKey++;copyRevision++
                }
                links.edit().remove(item.first).apply();sheetLinks.edit().remove(item.first).apply()
                copyState.edit().remove("count."+item.first).remove("time."+item.first).apply()
                ctx.getSharedPreferences("trip_identity",Context.MODE_PRIVATE).edit().remove(item.first).apply()
                names=names.filterNot{it.first==item.first}
                if(selected==item.first){selected="";active=null;form=false;link="";creating=false}
                deleteTarget=null;sheetKey++
            }}){Text("Delete trip")}},dismissButton={LiquidText(onClick={deleteTarget=null}){Text("Cancel")}})
    }
    undoDelete?.let { restore->
        AlertDialog(onDismissRequest={undoDelete=null},title={Text("Trip deleted")},text={Text("The local copy was removed.")},
            confirmButton={LiquidAction(onClick={task{restore();undoDelete=null}}){Text("Undo")}},
            dismissButton={LiquidText(onClick={undoDelete=null}){Text("Done")}})
    }
    pending?.let { other->
        AlertDialog(onDismissRequest={pending=null},title={Text("Import "+other.name+"?")},
            text={Text(importCount.toString()+" copies · "+other.members.size+" members. "+addedCount+" new entries will be added. Existing matching entries are kept once.")},
            confirmButton={LiquidText(onClick={pending=null;task{
                val merged=withContext(Dispatchers.IO){if(other.id in files.ids()) files.read(other.id).merge(other) else other}
                accept(merged)
            }}){Text("Import")}},dismissButton={LiquidText(onClick={pending=null}){Text("Cancel")}})
    }
    if(message.isNotBlank()) AlertDialog(onDismissRequest={message=""},title={Text("Trip money")},text={Text(message)},confirmButton={LiquidText(onClick={message=""}){Text("OK")}})
}
@Composable private fun TripEntryForm(trip:Trip,cancel:()->Unit,save:(TripEntry)->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf("Expense") }
    var who by rememberSaveable { mutableStateOf(trip.keeper) }
    var payer by rememberSaveable { mutableStateOf(trip.keeper) }
    var recipient by rememberSaveable { mutableStateOf(trip.members.first { it!=trip.keeper }) }
    var method by rememberSaveable { mutableStateOf("Cash") }
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(Split.Equal.name) }
    var multi by rememberSaveable { mutableStateOf(false) }
    var itemized by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }
    val shares=remember { mutableStateMapOf<String,String>().apply { trip.members.forEach { put(it,"1") } } }
    val funding=remember { mutableStateMapOf<String,String>() }
    val lines=remember { mutableStateListOf<TripLine>() }
    var forex by rememberSaveable { mutableStateOf(false) }
    var currency by rememberSaveable { mutableStateOf("") }
    var original by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var receipt by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) {loading=true;scope.launch {
            try { receipt=withContext(Dispatchers.IO){
                val bytes=requireNotNull(ctx.contentResolver.openInputStream(uri)).bounded(2*1024*1024)
                receiptType(bytes);Base64.encodeToString(bytes,Base64.NO_WRAP)
            }} catch(e:Exception){error=e.message ?: "Could not read receipt."} finally{loading=false}
        }}
    }
    fun runChecked(block:()->Unit) { try{block();error=""}catch(e:Exception){error=e.message ?: "Check the entry."} }
    Pick("Record",kind,listOf("Expense","Contribution","Withdrawal","Repayment")){kind=it}
    Pick("Entered by",who,trip.members){who=it}
    Input("Description",title){title=it.take(200)}
    Pick("Payment method",method,listOf("Cash","UPI","Card","Other")){method=it}
    val accounts=trip.members+POT
    fun display(k:String)=if(k==POT)"Group wallet (@wallet)" else k
    fun account(label:String)=if(label=="Group wallet (@wallet)") POT else label
    if(kind=="Expense") {
        Pick("Funding",if(multi)"Multiple payers" else "One payer",listOf("One payer","Multiple payers")){multi=it=="Multiple payers"}
        if(multi) accounts.forEach { k->Input(display(k)+" paid (₹)",funding[k].orEmpty(),true){funding[k]=it.take(11)} }
        else Pick("Paid by",display(payer),accounts.map(::display)){payer=account(it)}
        Pick("Bill layout",if(itemized)"Itemized" else "One total",listOf("One total","Itemized")){itemized=it=="Itemized"}
        if(itemized) Input("Item name",label){label=it.take(80)}
        if(itemized || !forex) Input(if(itemized)"Item price (₹)" else "Bill total (₹)",amount,true){amount=it.take(11)}
        if(!itemized) {
            LiquidText(onClick={forex=!forex}){Text(if(forex)"Use INR directly" else "Convert a foreign bill")}
            if(forex) {
                Input("Currency code (e.g. EUR)",currency){currency=it.uppercase().take(3)}
                Input("Amount in original currency",original,true){original=it.take(12)}
                Input("INR per currency unit",rate,true){rate=it.take(13)}
                Text("Converted total ₹"+(runCatching{convertedInr(original,rate)}.getOrNull() ?: "—"))
            }
        }
        Pick("Split",mode,Split.entries.map{it.name}){choice->
            mode=choice;shares.clear();if(mode=="Equal")trip.members.forEach{shares[it]="1"}
        }
        if(mode=="Equal") {
            Text("Who shares this cost?")
            trip.members.forEach { person->LiquidChip(!shares[person].isNullOrBlank(),{if(person in shares)shares.remove(person) else shares[person]="1"},{Text(person)}) }
        } else {
            Text(when(mode){"Exact"->"Enter each person's cost in rupees.";"Percent"->"Enter percentages adding to 100." ;else->"Use relative shares, e.g. 2 and 1. Leave nonparticipants blank."})
            trip.members.forEach { person->Input(person,shares[person].orEmpty(),true){shares[person]=it.take(11)} }
        }
        if(itemized) {
            LiquidOutline(onClick={runChecked{
                require(label.isNotBlank()){"Enter an item name."}
                require(lines.size<100){"Maximum 100 items per bill."}
                lines+=TripLine(label.trim(),allocate(paise(amount),shares,Split.valueOf(mode)))
                label="";amount=""
            }}){Text("Add item to bill")}
            lines.forEachIndexed { i,line->LiquidText(onClick={lines.removeAt(i)}){Text(line.label+" · ₹"+rupees(line.shares.values.sum())+" · Remove")} }
            Text("Bill total ₹"+rupees(lines.sumOf{it.shares.values.sum()}))
        }
    } else {
        Text(when(kind){"Contribution"->"Record money actually handed to the group wallet.";"Withdrawal"->"Return money from the wallet to a member.";else->"Record money already repaid between members."})
        if(kind!="Withdrawal")Pick("From",if(payer==POT)trip.keeper else payer,trip.members){payer=it}
        if(kind!="Contribution")Pick("To",recipient,trip.members){recipient=it}
        Input("Amount (₹)",amount,true){amount=it.take(11)}
    }
    LiquidOutline(onClick={picker.launch(arrayOf("image/jpeg","image/png","application/pdf"))},enabled=!loading){
        Text(if(loading)"Reading receipt…" else if(receipt.isEmpty())"Attach receipt (up to 2 MB)" else "Replace attached receipt")
    }
    if(receipt.isNotEmpty()) LiquidText(onClick={receipt=""}){Text("Remove receipt")}
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    LiquidAction(onClick={runChecked{
        require(title.isNotBlank()){"Enter a description."}
        val converting=kind=="Expense" && !itemized && forex
        if(converting)require(currency.matches(Regex("[A-Z]{3}"))){"Enter a three-letter currency code."}
        val billAmount=if(converting) convertedInr(original,rate) else amount
        val itemList=if(kind=="Expense" && itemized)lines.toList() else emptyList()
        val owed=if(kind=="Expense" && itemized){
            require(label.isBlank() && amount.isBlank()){"Add the current item before saving."}
            require(lines.isNotEmpty()){"Add at least one item."}
            val sum=mutableMapOf<String,Long>();lines.forEach{l->l.shares.forEach{(k,v)->sum[k]=(sum[k] ?: 0L)+v}};sum
        } else if(kind=="Expense") allocate(paise(billAmount),shares,Split.valueOf(mode))
        else mapOf((if(kind=="Contribution") POT else recipient) to paise(amount))
        val paid=if(kind=="Expense" && multi) funding.filterValues{it.isNotBlank()}.mapValues{paise(it.value)}.filterValues{it>0}
        else mapOf((when {kind=="Withdrawal"->POT;kind!="Expense" && payer==POT->trip.keeper;else->payer}) to owed.values.sum())
        val entry=TripEntry(tripId(),System.currentTimeMillis(),kind,title.trim(),method,paid,owed,receipt,who,itemList,foreign=if(converting) "$currency $original @ ₹$rate per unit" else "")
        trip.copy(entries=trip.entries+entry).totals();save(entry)
    }},enabled=!loading){Text("Save entry")}
    LiquidText(onClick=cancel){Text("Cancel")}
}

