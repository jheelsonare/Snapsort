package com.snapsort.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.ui.AppAction
import com.snapsort.app.ui.SnapSortApp

class MainActivity : ComponentActivity() {
    private val model: SnapSortViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.refresh(galleryAccess())
        if (!galleryAccess().canReadLibrary) model.notify("Gallery access is off. You can still add selected images or enable access in Settings.")
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { /* Some gallery providers only offer a temporary grant. */ }
        }
        if (uris.isNotEmpty()) model.addImages(uris)
    }
    private val trashConfirmation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.onTrashResult(result.resultCode == Activity.RESULT_OK)
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                model.addFolder(uri)
            } catch (_: SecurityException) {
                model.notify("This provider cannot retain folder access. Select individual images instead.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(20, 23, 21)),
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            SnapSortApp(state) { action ->
                when (action) {
                    AppAction.RequestAccess -> requestGalleryAccess()
                    AppAction.ManageAccess -> {
                        if (Build.VERSION.SDK_INT >= 34 && galleryAccess().limited) requestGalleryAccess()
                        else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    }
                    AppAction.AddImages -> imagePicker.launch(arrayOf("image/*"))
                    AppAction.AddFolder -> folderPicker.launch(null)
                    AppAction.DeleteSelected -> lifecycleScope.launch { model.beginTrash()?.let(::launchTrash) }
                    else -> model.action(action)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Runtime grants and the gallery may have changed while the app was away.
        model.refresh(galleryAccess())
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun galleryAccess(): GalleryAccess {
        val full = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES)
            else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val partial = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return GalleryAccess(canReadLibrary = full || partial, limited = !full && partial)
    }

    private fun requestGalleryAccess() {
        val names = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.launch(names)
    }

    private fun launchTrash(uris: List<String>) {
        try {
            val request = MediaStore.createTrashRequest(contentResolver, uris.map(Uri::parse), true)
            trashConfirmation.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (_: SecurityException) {
            model.onTrashFailed("Android could not access one of the selected originals. Review gallery access, then try again.")
        } catch (_: IllegalArgumentException) {
            model.onTrashFailed("One of these images cannot use Android's trash flow. Refresh and review the selection again.")
        } catch (_: android.content.ActivityNotFoundException) {
            model.onTrashFailed("Android's trash confirmation is unavailable. The collection is being refreshed.")
        }
    }
}
