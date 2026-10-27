package com.snapsort.app.flow

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.snapsort.app.MainActivity
import com.snapsort.app.SnapSortViewModel
import com.snapsort.app.data.TrashOperationStore
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.PendingTrashOperation
import com.snapsort.app.ui.AppAction
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host phases are driven by tools/check-process-death.py on a disposable device. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    )
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as Application
    private val resolver = context.contentResolver
    private val fixtureFile get() = File(context.filesDir, "process-death-fixtures.json")
    private val readyFile get() = File(context.filesDir, "process-death-ready.json")
    private val journal get() = TrashOperationStore(context)

    @Test fun recoversRemainingOriginalsWithoutResumingConfirmation() = runBlocking {
        when (val phase = InstrumentationRegistry.getArguments().getString("death_phase")) {
            "prepare-dialog", "prepare-between-batches" -> prepareForHost(phase)
            "verify" -> verifyForHost()
            "cleanup" -> cleanupForHost()
            null -> ordinaryRecreationCheck()
            else -> error("Unknown process-death fixture phase: $phase")
        }
    }

    private suspend fun ordinaryRecreationCheck() {
        val previous = context.getSharedPreferences("pending-trash", 0).getString("operation", null)
        val images = listOf(insertImage(), insertImage())
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        try {
            journal.write(PendingTrashOperation.reviewed(images.map(Uri::toString)))
            val first = newModel(firstStore)
            await { first.state.value.cleanupRecoveryPending && first.state.value.selected.size == 2 }
            assertFalse(first.state.value.busyDeleting)
            instrumentation.runOnMainSync { firstStore.clear() }
            resolver.update(images.first(), ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) }, null, null)
            val second = newModel(secondStore)
            await { second.state.value.cleanupRecoveryPending && second.state.value.selected == setOf(images.last().toString()) }
            assertFalse(second.state.value.busyDeleting)
            assertEquals(listOf(images.last().toString()), journal.read()!!.targets)
            assertTrue(journal.read()!!.inFlight.isEmpty())
            assertTrue(journal.read()!!.reviewRequired)
        } finally {
            instrumentation.runOnMainSync { firstStore.clear(); secondStore.clear() }
            images.forEach { resolver.delete(it, null, null) }
            val edit = context.getSharedPreferences("pending-trash", 0).edit()
            if (previous == null) edit.remove("operation") else edit.putString("operation", previous)
            check(edit.commit())
        }
    }

    private suspend fun prepareForHost(phase: String) {
        check(!fixtureFile.exists()) { "Prior fixture file exists; run the cleanup phase first" }
        check(journal.read() == null) { "Host driver requires a disposable test app without a pending cleanup" }
        val images = listOf(insertImage(), insertImage())
        val fixture = JSONObject().put("phase", phase).put("uris", JSONArray(images.map(Uri::toString)))
        fixtureFile.writeText(fixture.toString())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: SnapSortViewModel
        scenario.onActivity { model = ViewModelProvider(it)[SnapSortViewModel::class.java] }
        await { model.state.value.library.items.count { it.uri in images.map(Uri::toString) } == 2 }
        val device = UiDevice.getInstance(instrumentation)
        if (phase == "prepare-dialog") {
            instrumentation.runOnMainSync { images.forEach { model.action(AppAction.ToggleSelection(it.toString())) } }
            await { model.state.value.selected.size == 2 }
            val batch = checkNotNull(model.beginTrash())
            val request = MediaStore.createTrashRequest(resolver, batch.map(Uri::parse), true)
            scenario.onActivity { it.startIntentSenderForResult(request.intentSender, 419, null, 0, 0, 0) }
            assertNotNull("A real Android trash confirmation must be visible before the host kills the app",
                device.wait(Until.findObject(By.res("android", "button2")), 90_000))
            assertTrue(journal.read()!!.inFlight.isNotEmpty())
        } else {
            // A small explicit checkpoint fixture exercises the same persisted between-batch state
            // without claiming to be a 2,001-image load test. The first image is really confirmed.
            val operation = PendingTrashOperation(images.map(Uri::toString), listOf(images.first().toString()))
            journal.write(operation)
            val request = MediaStore.createTrashRequest(resolver, listOf(images.first()), true)
            scenario.onActivity { it.startIntentSenderForResult(request.intentSender, 420, null, 0, 0, 0) }
            val confirm = device.wait(Until.findObject(By.res("android", "button1")), 90_000)
            assertNotNull("Android confirmation must appear for the first fixture", confirm)
            confirm!!.click()
            await { images.first().toString() in journal.observe(images.map(Uri::toString)).trashed }
            journal.write(operation.reconcile(setOf(images.first().toString()), confirmed = true))
            assertEquals(1, journal.read()!!.confirmedBatches)
        }
        readyFile.writeText(fixture.toString())
        // The host deliberately force-stops this process, including the instrumentation runner.
        // Its aborted output is expected evidence, never counted as a passing instrumented test.
        repeat(240) { delay(5_000) }
        error("Host did not kill the process after the checkpoint became ready")
    }

    private suspend fun verifyForHost() {
        val fixture = JSONObject(fixtureFile.readText())
        val images = fixture.getJSONArray("uris").let { a -> List(a.length()) { a.getString(it) } }
        val between = fixture.getString("phase") == "prepare-between-batches"
        val expected = if (between) images.drop(1).toSet() else images.toSet()
        val store = ViewModelStore()
        try {
            val model = newModel(store)
            await { model.state.value.cleanupRecoveryPending && model.state.value.selected == expected }
            assertFalse("Recovery must never automatically submit another request", model.state.value.busyDeleting)
            val recovered = checkNotNull(journal.read())
            assertEquals(expected, recovered.targets.toSet())
            assertTrue(recovered.reviewRequired)
            assertTrue(recovered.inFlight.isEmpty())
            assertEquals(if (between) 1 else 0, recovered.confirmedBatches)
            val observation = journal.observe(images)
            assertEquals(expected, observation.present)
            if (between) assertEquals(setOf(images.first()), observation.trashed)
            else assertTrue("Killing a confirmation must preserve originals", observation.trashed.isEmpty())
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    private fun cleanupForHost() {
        if (fixtureFile.exists()) {
            val fixture = JSONObject(fixtureFile.readText())
            val images = fixture.getJSONArray("uris").let { a -> List(a.length()) { a.getString(it) } }
            images.forEach { resolver.delete(Uri.parse(it), null, null) }
            val pending = journal.read()
            if (pending != null && pending.targets.all { it in images }) journal.write(null)
            fixtureFile.delete()
        }
        readyFile.delete()
    }

    private fun newModel(store: ViewModelStore): SnapSortViewModel {
        lateinit var model: SnapSortViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[SnapSortViewModel::class.java]
            model.refresh(GalleryAccess(canReadLibrary = true))
        }
        return model
    }

    private fun insertImage(): Uri {
        val inserted = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_ProcessDeath_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        resolver.openOutputStream(inserted)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        resolver.update(inserted, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return resolver.query(inserted, arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media._ID), null, null, null)!!.use {
            check(it.moveToFirst()); ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(it.getString(0)), it.getLong(1))
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(120_000) { while (!predicate()) delay(200) }
    }
}
