package com.taptransfer

import android.content.*
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private var themeMode by mutableStateOf("Dark")
    private var historyQuery by mutableStateOf("")
    private var historyFilter by mutableStateOf("All")
    private var selectedActivity by mutableStateOf<String?>(null)
    private var clearHistory by mutableStateOf(false)
    private var templates by mutableStateOf(emptyList<Pair<String,String>>())
    private var privateScreen by mutableStateOf(false)
    private fun saveTemplates(updated: List<Pair<String,String>>) {
        templates = updated.distinct().take(8)
        val data = JSONArray()
        templates.forEach { (value, memo) -> data.put(JSONArray().put(value).put(memo)) }
        prefs.edit().putString("templates", data.toString()).apply()
    }
    private fun applyPrivacy() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if(privateScreen) window.addFlags(flag) else window.clearFlags(flag)
    }
    private var presets by mutableStateOf(listOf("100", "250", "500"))
    private var pendingPaymentId: String? = null
    private var tripsEnabled by mutableStateOf(false)
    private var page by mutableStateOf("Home")
    private var name by mutableStateOf("")
    private var upi by mutableStateOf("")
    private var amount by mutableStateOf("")
    private var note by mutableStateOf("")
    private var request by mutableStateOf<PaymentRequest?>(null)
    private var incoming by mutableStateOf<PaymentRequest?>(null)
    private var history by mutableStateOf(listOf<String>())
    private var error by mutableStateOf("")
    private var scanning by mutableStateOf(false)
    private var defaultUpi by mutableStateOf("")
    private var upiApps by mutableStateOf(emptyList<UpiTarget>())
    private fun handlers(intent: Intent): List<UpiTarget> =
        packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.enabled }
            .map {
                val activity = it.activityInfo
                UpiTarget(activity.packageName, ComponentName(activity.packageName, activity.name).flattenToString(),
                    it.loadLabel(packageManager).toString())
            }
            .distinctBy { it.component }
    private fun refreshUpiApps() {
        val probe = android.net.Uri.parse("upi://pay?pa=sample%40bank&pn=Sample&am=1.00&cu=INR")
        upiApps = handlers(Intent(Intent.ACTION_VIEW, probe)).distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }
    private val defaultLabel: String get() = UpiRouting.resolve(defaultUpi, upiApps)?.label
        ?: prefs.getString("defaultLabel", null) ?: defaultUpi.substringBefore('/')
    private fun saveDefault(value: String) {
        defaultUpi = value
        prefs.edit().putString("defaultUpi", value)
            .putString("defaultLabel", UpiRouting.resolve(value, upiApps)?.label).apply()
    }
    private fun dismissPayment() {
        incoming = null
        scanning = page == "Pay"
        configure()
    }
    private val busy = AtomicBoolean(false)
    private val adapter get() = NfcAdapter.getDefaultAdapter(this)
    private val prefs by lazy { getSharedPreferences("taptransfer", MODE_PRIVATE) }
    private val paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingPaymentId?.let { id ->
            history = history.map { raw ->
                val j = JSONObject(raw)
                if (j.getString("id") == id) j.put("status", "Payment app returned • unverified").toString() else raw
            }
            persistHistory()
        }
        pendingPaymentId = null
        error = "Check your UPI app or bank for the final payment status. Do not retry until you have checked."
        incoming = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = prefs.getString("name", "") ?: ""
        upi = prefs.getString("upi", "") ?: ""
        defaultUpi = prefs.getString("defaultUpi", "") ?: ""
        themeMode = prefs.getString("theme", "Dark") ?: "Dark"
        presets = (prefs.getString("presets", "100,250,500") ?: "100,250,500").split(",").filter { it.isNotBlank() }.take(5)
        page = savedInstanceState?.getString("page") ?: when (intent.action) {
            "com.taptransfer.PAY" -> "Pay"
            "com.taptransfer.RECEIVE" -> "Receive"
            else -> "Home"
        }
        amount = savedInstanceState?.getString("amount") ?: ""
        note = savedInstanceState?.getString("note") ?: ""
        pendingPaymentId = savedInstanceState?.getString("pending")
        historyQuery = savedInstanceState?.getString("query") ?: ""
        val stored = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        history = (0 until stored.length()).map { stored.getString(it) }
        privateScreen = prefs.getBoolean("privateScreen", false)
        applyPrivacy()
        val savedTemplates = JSONArray(prefs.getString("templates","[]"))
        templates = (0 until savedTemplates.length()).map { index ->
            val pair = savedTemplates.getJSONArray(index)
            pair.getString(0) to pair.getString(1)
        }
        fun restoreRequest(key: String) = savedInstanceState?.getString(key)?.let {
            runCatching { PaymentRequest.parse(it) }.getOrNull()
        }
        request = restoreRequest("outgoing")
        incoming = restoreRequest("incoming")
        tripsEnabled = prefs.getBoolean("tripsEnabled", false)
        if(!tripsEnabled && page=="Trips") page="Home"
        setContent { App() }
    }
    override fun onResume() { super.onResume(); refreshUpiApps(); scanning = page == "Pay" && incoming == null; configure() }
    override fun onPause() { scanning = false; stopRadio(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("outgoing", request?.json())
        outState.putString("incoming", incoming?.json())
        outState.putString("page", page)
        outState.putString("amount", amount)
        outState.putString("note", note)
        outState.putString("pending", pendingPaymentId)
        outState.putString("query", historyQuery)
        super.onSaveInstanceState(outState)
    }
    private fun persistHistory() { prefs.edit().putString("history", JSONArray(history).toString()).apply() }
    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "Share activity"))
    }
    private fun activityText(raw: String): String {
        val j = JSONObject(raw)
        return "TapTransfer request activity — NOT proof of payment\n₹" + j.getString("amount") +
            "\n" + j.getString("name") + "\n" + j.getString("upi") +
            "\n" + j.getString("status") + "\nReference: " + j.getString("id") +
            "\n" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(j.getLong("time")))
    }
    private fun stopRadio() {
        Session.armed = false
        adapter?.let { runCatching { it.disableReaderMode(this); CardEmulation.getInstance(it).unsetPreferredService(this) } }
    }
    private fun configure() {
        stopRadio()
        val nfc = adapter ?: run { scanning = false; Session.status.value = "NFC unavailable"; return }
        if (!nfc.isEnabled) { scanning = false; Session.status.value = "NFC is switched off"; return }
        if (page == "Receive" && request?.expires?.let { it > System.currentTimeMillis() } == true) {
            Session.request = request; Session.armed = true
            runCatching { CardEmulation.getInstance(nfc).setPreferredService(this, ComponentName(this, HelloService::class.java)) }
        }
        if (scanning && page == "Pay") {
            nfc.enableReaderMode(this, { tag ->
                if (busy.compareAndSet(false, true)) {
                    try {
                        val iso = IsoDep.get(tag) ?: error("This tag is not a TapTransfer request")
                        val payment = iso.use {
                            it.connect(); it.timeout = 4000
                            require(it.transceive(Protocol.select).contentEquals("TT1".toByteArray()+Protocol.ok)) { "Open Receive on the other phone" }
                            val bytes = java.io.ByteArrayOutputStream()
                            var complete = false
                            for (i in 0..11) {
                                val offset = bytes.size()
                                val response = it.transceive(byteArrayOf(0x80.toByte(), 0x10, (offset shr 8).toByte(), offset.toByte(), 0))
                                require(response.size >= 3) { "Request unavailable or expired" }
                                val status = response.takeLast(2).toByteArray()
                                require(status.contentEquals(Protocol.ok) || status.contentEquals(byteArrayOf(0x61,0))) { "Invalid response" }
                                bytes.write(response, 0, response.size-2)
                                require(bytes.size() <= 2048) { "Request too large" }
                                if (status.contentEquals(Protocol.ok)) { complete = true; break }
                            }
                            require(complete) { "Incomplete request. Tap again." }
                            PaymentRequest.parse(bytes.toString("UTF-8"))
                        }
                        runOnUiThread {
                            if (page != "Pay" || !scanning || isFinishing || isDestroyed) return@runOnUiThread
                            scanning = false; stopRadio(); incoming = payment
                            window.decorView.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    } catch (e: Exception) { runOnUiThread { error = e.message ?: "Tap interrupted. Try again." } }
                    finally { busy.set(false) }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        }
    }
    private fun record(p: PaymentRequest, status: String) {
        val entry = JSONObject().put("id",p.id).put("name",p.name).put("upi",p.upi).put("amount",p.amount).put("status",status).put("time",System.currentTimeMillis()).toString()
        history = (listOf(entry) + history.filter { JSONObject(it).getString("id") != p.id }).take(100)
        prefs.edit().putString("history", JSONArray(history).toString()).apply()
    }
    // Entries live only until the corresponding request expires, independently of visible history.
    private val handoffs by lazy { getSharedPreferences("recent_handoffs", MODE_PRIVATE) }
    private fun reserveHandoff(p: PaymentRequest) {
        val now = System.currentTimeMillis()
        val edit = handoffs.edit()
        handoffs.all.forEach { (key, expiry) -> if ((expiry as? Long ?: 0L) <= now) edit.remove(key) }
        require(handoffs.getLong(p.id, 0L) <= now) { "This request was already opened. Check your UPI app." }
        check(edit.putLong(p.id, p.expires).commit()) { "Could not save this request. Please try again." }
    }
    private fun pay(p: PaymentRequest) {
        if (pendingPaymentId != null) return
        runCatching {
            p.validate()
            require(history.none { JSONObject(it).getString("id") == p.id }) { "This request was already opened. Check your UPI app before paying again." }
            val intent = Intent(Intent.ACTION_VIEW, p.uri())
            val launchIntent = if (defaultUpi.isBlank()) {
                Intent.createChooser(intent, "Pay with")
            } else {
                val target = UpiRouting.resolve(defaultUpi, handlers(intent))
                    ?: error("$defaultLabel cannot open this request. Choose another default on Pay, or select Ask every time.")
                val component = requireNotNull(ComponentName.unflattenFromString(target.component))
                Intent(intent).setComponent(component)
            }
            reserveHandoff(p)
            pendingPaymentId = p.id
            try {
                paymentLauncher.launch(launchIntent)
            } catch (failure: RuntimeException) {
                handoffs.edit().remove(p.id).apply()
                throw failure
            }
            record(p, "UPI handoff requested • unverified")
        }.onFailure { pendingPaymentId = null; error = it.message ?: "Could not open the selected UPI app." }
    }
    @Composable private fun App() {
        val dark = themeMode == "Dark" || (themeMode == "System" && isSystemInDarkTheme())
        val colors = if (!dark) lightColorScheme(primary=Color(0xFF386600), background=Color(0xFFF6FAF1), surface=Color(0xFFEAF0E4)) else darkColorScheme(primary=Color(0xFFB0C9FF), background=Color.Black, surface=Color(0xFF101014), surfaceContainer=Color(0xFF202025), surfaceContainerHigh=Color(0xFF2C2C32),secondaryContainer=Color(0xFF29364D),onSecondaryContainer=Color.White,onPrimary=Color(0xFF0C1F39))
        androidx.activity.compose.BackHandler(enabled=page!="Home" && incoming==null && error.isBlank()) {
            navigate("Home")
        }
        MaterialTheme(colorScheme=colors) {
            Surface(Modifier.fillMaxSize(),color=colors.background) {
                GlassBackdrop(page)
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal=22.dp)) {
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) {
                        Text("TapTransfer", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                    }
                    Spacer(Modifier.height(22.dp))
                    AnimatedContent(page, modifier=Modifier.weight(1f), transitionSpec={
                        val order=listOf("Home","Pay","Receive","History","Trips","Profile")
                        val sign=if(order.indexOf(targetState)>=order.indexOf(initialState)) 1 else -1
                        (slideInHorizontally(tween(320)) { sign*it/5 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(240)) { -sign*it/6 } + fadeOut(tween(180)))
                    }, label="page") { current ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            when(current) {
                                "Home" -> {
                                    Text(if(name.isBlank()) "Welcome" else "Hello, $name", style=MaterialTheme.typography.headlineLarge, fontWeight=FontWeight.Bold)
                                    GlassPanel {
                                        Text("PERSONAL UPI",style=MaterialTheme.typography.labelLarge,color=colors.primary)
                                        Text(upi.ifBlank { "Set up your UPI ID" },style=MaterialTheme.typography.headlineSmall)
                                        LiquidOutline(onClick={navigate("Profile")}) { Text(if(upi.isBlank()) "Set up profile" else "Edit profile") }
                                    }
                                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        LiquidAction(onClick={navigate("Pay")},modifier=Modifier.weight(1f).height(60.dp)) { Text("↗ Pay") }
                                        LiquidOutline(onClick={navigate("Receive")},modifier=Modifier.weight(1f).height(60.dp)) { Text("↙ Receive") }
                                    }
                                    LiquidCard(onClick={
                                        if(!tripsEnabled) navigate("Profile") else navigate("Trips")
                                    },modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)) {
                                        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                            Icon(androidx.compose.ui.res.painterResource(R.drawable.nav_trips),contentDescription=null)
                                            Column(Modifier.weight(1f)) {
                                                Text("Trip money",style=MaterialTheme.typography.titleLarge)
                                                Text(if(tripsEnabled) "Open trips and shared wallets" else "Enable shared bills and wallets in your profile",style=MaterialTheme.typography.bodyMedium)
                                            }
                                            Text("→")
                                        }
                                    }
                                    Panel("Review before paying", "Confirm the recipient’s bank-verified name in your UPI app.")

                                }
                                "Trips" -> if(tripsEnabled) TripWorkspace()
                                "Profile" -> {
                                    Text("Optional features",style=MaterialTheme.typography.titleMedium)
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                        Text("Trip money",modifier=Modifier.weight(1f))
                                        Switch(checked=tripsEnabled,onCheckedChange={
                                            tripsEnabled=it;prefs.edit().putBoolean("tripsEnabled",it).apply()
                                        })
                                    }
                                    Text("Track shared bills, cash repayments and a group wallet. Turning this off keeps your trips.")

                                    Heading("Your identity", "Where payment requests should point.")
                                    Field(name,"Display name") { name=it.take(60) }
                                    Field(upi,"Personal UPI ID") { upi=it.take(195) }
                                    DefaultUpiPicker()
                                    LiquidAction(onClick={
                                        runCatching { PaymentRequest.create(upi,name,"1","") }.onSuccess {
                                            prefs.edit().putString("name",name.trim()).putString("upi",upi.trim()).apply()
                                            navigate("Home")
                                        }.onFailure { error=it.message ?: "Invalid profile" }
                                    },modifier=Modifier.fillMaxWidth()) { Text("Save profile") }
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                        Text("Screen privacy",modifier=Modifier.weight(1f))
                                        Switch(checked=privateScreen,onCheckedChange={
                                            privateScreen=it
                                            prefs.edit().putBoolean("privateScreen",it).apply()
                                            applyPrivacy()
                                        })
                                    }
                                    Text("Hide this app in screenshots and recent-app previews.")
                                    Text("Appearance", style=MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        listOf("System","Light","Dark").forEach { mode ->
                                            LiquidChip(selected=themeMode==mode,onClick={themeMode=mode;prefs.edit().putString("theme",mode).apply()},label={Text(mode)})
                                        }
                                    }
                                    Text("Stored on this phone. We never ask for your UPI PIN.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                "Receive" -> Receive()
                                "Pay" -> {
                                    Heading("Tap. Review. Pay.", "Bring your phone close to the receiver's phone.")
                                    Pulse()
                                    Text(if(scanning) "Looking for a request…" else if(incoming != null) "Review your request" else "Enable NFC to scan", style=MaterialTheme.typography.headlineSmall)
                                    DefaultUpiPicker()
                                    LiquidOutline(onClick={startActivity(Intent(Settings.ACTION_NFC_SETTINGS))}) { Text("NFC settings") }
                                    Text("A tap only reads the request. You choose whether to pay after reviewing it.")
                                }
                                "History" -> {
                                    Heading("Your activity", "Request activity, not a bank statement.")
                                    Field(historyQuery,"Search name, UPI ID or amount") { historyQuery=it }
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        listOf("All","Requests","Payments").forEach { filter ->
                                            LiquidChip(selected=historyFilter==filter,onClick={historyFilter=filter},label={Text(filter)})
                                        }
                                    }
                                    val visible = history.filter { raw ->
                                        val j=JSONObject(raw)
                                        val isRequest=j.getString("status").startsWith("Request created")
                                        (historyFilter=="All" || (historyFilter=="Requests" && isRequest) || (historyFilter=="Payments" && !isRequest)) &&
                                        listOf("name","upi","amount").any { j.getString(it).contains(historyQuery,ignoreCase=true) }
                                    }
                                    if(visible.isEmpty()) Panel("Nothing here yet", "Try another search or create a payment request.")
                                    visible.forEach { raw ->
                                        val j=JSONObject(raw)
                                        LiquidCard(onClick={selectedActivity=raw},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)) {
                                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                                Text("₹"+j.getString("amount")+" · "+j.getString("name"),style=MaterialTheme.typography.titleMedium)
                                                Text(j.getString("upi"))
                                                Text(j.getString("status"),style=MaterialTheme.typography.bodySmall)
                                                Text("View details →",color=MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                    if(history.isNotEmpty()) {
                                        LiquidText(onClick={shareText(visible.joinToString("\n\n") { activityText(it) })},enabled=visible.isNotEmpty()) { Text("Share filtered activity") }
                                        LiquidText(onClick={clearHistory=true}) { Text("Clear history") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    NavigationBar(modifier=Modifier.glass(),containerColor=Color.Transparent,windowInsets=WindowInsets(0,0,0,0)) {
                        (listOf("Home","Pay","Receive","History")+if(tripsEnabled) listOf("Trips") else emptyList()).forEach { tab ->
                            NavigationBarItem(selected=page==tab,onClick={navigate(tab)},icon={
                                val resource=when(tab) {
                                    "Home" -> R.drawable.nav_home
                                    "Pay" -> R.drawable.nav_pay
                                    "Receive" -> R.drawable.nav_receive
                                    "History" -> R.drawable.nav_history
                                    else -> R.drawable.nav_trips
                                }
                                Icon(androidx.compose.ui.res.painterResource(resource),contentDescription=null)
                            },label={Text(tab)})
                        }
                    }
                }
                if(error.isNotBlank()) AlertDialog(onDismissRequest={error=""},title={Text("A quick update")},text={Text(error)},confirmButton={LiquidText(onClick={error=""}){Text("Got it")}})
                if(clearHistory) AlertDialog(onDismissRequest={clearHistory=false},
                    title={Text("Clear local activity?")},text={Text("This removes history on this phone. It does not cancel or reverse bank payments.")},
                    confirmButton={LiquidText(onClick={history=emptyList();persistHistory();clearHistory=false}){Text("Clear history")}},
                    dismissButton={LiquidText(onClick={clearHistory=false}){Text("Keep history")}})
                selectedActivity?.let { raw ->
                    AlertDialog(onDismissRequest={selectedActivity=null},title={Text("Request details")},
                        text={Text(activityText(raw))},
                        confirmButton={LiquidText(onClick={shareText(activityText(raw))}){Text("Share")}},
                        dismissButton={LiquidText(onClick={selectedActivity=null}){Text("Close")}})
                }
                incoming?.takeIf { error.isBlank() }?.let { p ->
                    AlertDialog(onDismissRequest={dismissPayment()}, title={Text("Pay ₹${p.amount}?")}, text={
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(p.name,style=MaterialTheme.typography.headlineSmall)
                            Text(p.upi); if(p.note.isNotBlank()) Text(p.note)
                            DefaultUpiPicker()
                            Text("Verify the recipient's name in your UPI app. This request is not independently verified.")
                        }
                    }, confirmButton={LiquidAction(onClick={pay(p)}){Text(if(defaultUpi.isBlank()) "Choose UPI app" else "Pay with " + (defaultLabel))}},dismissButton={
                        Row { LiquidText(onClick={copy(p.upi)}){Text("Copy ID")}; LiquidText(onClick={dismissPayment()}){Text("Cancel")} }
                    })
                }
            }
        }
    }
    private fun navigate(target: String) {
        scanning=false; stopRadio()
        if(page=="Receive" && target!="Receive") {request=null; Session.request=null}
        incoming=null; page=target; scanning=target=="Pay"; refreshUpiApps(); configure()
    }
    @Composable private fun DefaultUpiPicker() {
        var expanded by remember { mutableStateOf(false) }
        Text("Default UPI app", style=MaterialTheme.typography.titleMedium)
        Box {
            LiquidOutline(onClick={refreshUpiApps(); expanded=true},modifier=Modifier.fillMaxWidth()) {
                Text(if(defaultUpi.isBlank()) "Ask every time" else defaultLabel)
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                DropdownMenuItem(text={Text("Ask every time")},onClick={saveDefault("");expanded=false})
                upiApps.forEach { target ->
                    DropdownMenuItem(text={Text(target.label)},onClick={saveDefault(target.component);expanded=false})
                }
            }
        }
        if(upiApps.isEmpty()) Text("No UPI handlers are visible here. You can still try the Android app chooser when paying.", style=MaterialTheme.typography.bodySmall)
    }
    private fun copy(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UPI",value))
        android.widget.Toast.makeText(this,"Copied",android.widget.Toast.LENGTH_SHORT).show()
    }
    @Composable private fun Receive() {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(request?.id) {
            while(true) { now=System.currentTimeMillis(); if(request?.expires?.let{it<=now}==true) stopRadio(); delay(1000) }
        }
        val p=request
        val status by Session.status.collectAsStateWithLifecycle()
        Heading("Request a payment", "Your phone becomes the payment request.")
        if(p==null) {
            Text(upi.ifBlank{"Set up your UPI ID in Profile first."})
            SplitBillCard { amount=it }
            Field(amount,"Amount in INR", decimal=true) { amount=it.take(9) }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                presets.forEach { value -> LiquidChip(selected=amount==value,onClick={amount=value},label={Text("₹"+value)}) }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                LiquidText(onClick={
                    runCatching { PaymentRequest.create("preset@bank","Preset",amount,"") }.onSuccess {
                        presets=(presets+amount).distinct().takeLast(5)
                        prefs.edit().putString("presets",presets.joinToString(",")).apply()
                    }.onFailure { error=it.message ?: "Enter an amount first" }
                }) { Text("Save amount preset") }
                if(presets.isNotEmpty()) LiquidText(onClick={presets=emptyList();prefs.edit().putString("presets","").apply()}) { Text("Clear presets") }
            }
            Field(note,"Note (optional)") { note=it.take(80) }
            if (templates.isNotEmpty()) {
                Text("Saved requests",style=MaterialTheme.typography.titleMedium)
                templates.forEach { item ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        LiquidText(onClick={amount=item.first;note=item.second},modifier=Modifier.weight(1f)) { Text("₹"+item.first+" · "+item.second) }
                        LiquidText(onClick={saveTemplates(templates-item)}) { Text("Remove") }
                    }
                }
            }
            LiquidText(onClick={
                runCatching {
                    require(note.isNotBlank()) { "Add a note to name your template." }
                    val draft = PaymentRequest.create(upi,name,amount,note)
                    val item = draft.amount to draft.note
                    require(item in templates || templates.size<8) { "Remove a template before adding another." }
                    saveTemplates(listOf(item)+templates)
                }.onFailure {error=it.message ?: "Check your details"}
            }) {Text("Save amount and note as template")}
            LiquidAction(onClick={
                runCatching{PaymentRequest.create(upi,name,amount,note)}.onSuccess{
                    request=it; Session.status.value="Waiting for a tap"; record(it,"Request created • unverified"); configure()
                }.onFailure{error=it.message ?: "Check your details"}
            },modifier=Modifier.fillMaxWidth()) { Text("Create request") }
        } else {
            DisposableEffect(p.id, now < p.expires) {
                val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if(now < p.expires) window.addFlags(flag)
                onDispose { window.clearFlags(flag) }
            }
            Text("₹${p.amount}",style=MaterialTheme.typography.displayLarge,fontWeight=FontWeight.Bold)
            Text(p.upi)
            Text(if(now<p.expires) "Ready for ${(p.expires-now)/1000}s" else "Expired • create a new request",color=MaterialTheme.colorScheme.primary)
            if(now<p.expires) {
                Qr(p.uri().toString())
                LiquidOutline(onClick={shareQr(p)}) { Text("Share QR image") }
                Text(status)
                Text("Tap phones or scan with a UPI app. The QR/UPI link itself does not enforce this app's expiry.")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    LiquidOutline(onClick={copy(p.upi)}) { Text("Copy ID") }
                    LiquidOutline(onClick={
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,p.uri().toString())},"Share request"))
                    }) { Text("Share link") }
                }
            }
            LiquidAction(onClick={request=null; Session.request=null; stopRadio()}) {Text("New request")}
            Text("Confirm receipt in your bank or UPI app. Sharing a request does not confirm payment.")
        }
    }
    private fun renderQr(value: String): Bitmap {
        val side = 600
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, side, side)
        val pixels = IntArray(side * side) { i ->
            if (matrix[i % side, i / side]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }
    private fun shareQr(p: PaymentRequest) {
        runCatching {
            p.validate()
            val directory = java.io.File(cacheDir, "request-codes").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 86400000L
            directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = java.io.File(directory, p.id + ".png")
            file.outputStream().use { check(renderQr(p.uri().toString()).compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".files", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "UPI request", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share payment QR"))
        }.onFailure { error = it.message ?: "Could not share the QR code." }
    }
    @Composable private fun Qr(value: String) {
        val bitmap = remember(value) { renderQr(value).asImageBitmap() }
        Image(bitmap, contentDescription="UPI payment QR code", modifier=Modifier.fillMaxWidth().height(250.dp))
    }
    @Composable private fun Heading(title:String,subtitle:String) {
        Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable private fun Field(value:String,label:String,decimal:Boolean=false,change:(String)->Unit) {
        OutlinedTextField(value=value,onValueChange=change,label={Text(label)},singleLine=true,colors=glassFieldColors(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=if(decimal) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp))
    }
    @Composable private fun Panel(title:String,body:String) {
        LiquidCard(shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(title,fontWeight=FontWeight.Bold); Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    @Composable private fun Pulse() {
        val transition=rememberInfiniteTransition(label="tap")
        val pulse by transition.animateFloat(0.65f,1f,infiniteRepeatable(tween(1600),RepeatMode.Reverse),label="pulse")
        val color=MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            for(i in 1..3) drawCircle(color.copy(alpha=0.10f*i),radius=size.minDimension*0.15f*i*pulse,style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            drawCircle(color,radius=18.dp.toPx())
        }
    }
}



