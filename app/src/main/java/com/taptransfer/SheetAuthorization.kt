package com.taptransfer

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

@Composable
internal fun rememberSheetAuthorization(onToken:(String)->Unit, onProblem:(String)->Unit):()->Unit {
    val ctx=LocalContext.current
    val client=remember(ctx){Identity.getAuthorizationClient(ctx)}
    val ready by rememberUpdatedState(onToken)
    val failed by rememberUpdatedState(onProblem)
    fun reject(e:Exception) {
        failed("Google connection failed. Check the Google account and TapTransfer OAuth configuration. "+e.localizedMessage.orEmpty())
    }
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        if(result.resultCode!=Activity.RESULT_OK)failed("Google authorization was cancelled.")
        else try {
            ready(requireNotNull(client.getAuthorizationResultFromIntent(result.data).accessToken))
        } catch(e:Exception){reject(e)}
    }
    return {
        val request=AuthorizationRequest.builder().setRequestedScopes(listOf(
            Scope("https://www.googleapis.com/auth/spreadsheets"),
            Scope("https://www.googleapis.com/auth/drive.file")
        )).build()
        client.authorize(request).addOnSuccessListener { result->
            try {
                if(result.hasResolution())consent.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build())
                else ready(requireNotNull(result.accessToken))
            } catch(e:Exception){reject(e)}
        }.addOnFailureListener {reject(it)}
    }
}
