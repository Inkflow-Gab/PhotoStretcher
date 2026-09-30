package com.photostretcher.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photostretcher.app.ui.EditorScreen
import com.photostretcher.app.ui.HomeScreen
import com.photostretcher.app.ui.PhotoViewModel
import com.photostretcher.app.ui.theme.PhotoStretcherTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoStretcherTheme {
                PhotoStretcherApp()
            }
        }
    }
}

/**
 * Two screens, one piece of state: no photo open means the start screen, otherwise the editor.
 * That is all the navigation this app needs.
 */
@Composable
private fun PhotoStretcherApp(viewModel: PhotoViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.state

    // The system photo picker. No storage permission is needed to pick, on any Android version.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            keepAccess(context, uri)
            viewModel.open(uri)
        }
    }

    if (state.photoUri == null) {
        HomeScreen(
            onPickPhoto = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    } else {
        EditorScreen(
            viewModel = viewModel,
            onExit = viewModel::close,
        )
    }
}

/**
 * The system picker hands out a temporary permission. Asking to keep it is harmless and lets the
 * app read the photo again if Android rebuilds the task while the editor is open.
 */
private fun keepAccess(context: android.content.Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
