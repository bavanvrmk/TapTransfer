package com.taptransfer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w420dp-h900dp")
@LooperMode(LooperMode.Mode.PAUSED)
class TripInteractionTest {
    @get:Rule val ui=createComposeRule()
    @Test fun actionHasAccessibleTargetAndDisabledState() {
        var calls=0
        ui.setContent {
            MaterialTheme { Column {
                LiquidAction(onClick={calls++}){androidx.compose.material3.Text("Active action")}
                LiquidAction(enabled=false,onClick={calls++}){androidx.compose.material3.Text("Unavailable action")}
            }}
        }
        ui.onNodeWithText("Active action").assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithText("Unavailable action").assertIsNotEnabled().performTouchInput { click() }
        ui.runOnIdle { Assert.assertEquals(1,calls) }
    }
    @Test fun tripLongPressCancelAndConfirmedDelete() {
        val ctx=RuntimeEnvironment.getApplication()
        val repo=TripFiles(ctx)
        val trip=Trip(tripId(),"Gesture trip",listOf("Alex","Kai"),"Kai")
        repo.save(trip)
        val neighbor=trip.copy(id=tripId(),name="Keep this trip")
        repo.save(neighbor)
        val owner=object:ActivityResultRegistryOwner {
            override val activityResultRegistry=object:ActivityResultRegistry(){
                override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?)=Unit
            }
        }
        ui.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())){TripWorkspace()}}
            }
        }
        ui.waitUntil(15000){ui.onAllNodesWithText(trip.name).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(trip.name).performScrollTo().performClick()
        ui.waitUntil(15000){ui.onAllNodesWithText("← All trips").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("← All trips").performScrollTo().performClick()
        ui.onNodeWithText(trip.name).performScrollTo().performTouchInput { longClick() }
        ui.onNodeWithText("Delete from this phone").performClick()
        ui.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(trip.id in repo.ids())
        ui.onNodeWithText(trip.name).performScrollTo().performTouchInput { longClick() }
        ui.onNodeWithText("Delete from this phone").performClick()
        ui.onNodeWithText("Delete trip").performClick()
        ui.waitUntil(15000){trip.id !in repo.ids()}
        Assert.assertTrue(neighbor.id in repo.ids())
        ui.onNodeWithText("Undo").performClick()
        ui.waitUntil(15000){trip.id in repo.ids()}
        Assert.assertEquals(trip,repo.read(trip.id))
    }
}
