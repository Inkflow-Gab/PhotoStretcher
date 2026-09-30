package com.photostretcher.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.photostretcher.app.R
import com.photostretcher.app.engine.ImageSaver
import com.photostretcher.app.engine.SaveFormat
import com.photostretcher.app.model.Axis
import com.photostretcher.app.ui.theme.Accent
import com.photostretcher.app.ui.theme.Warn

/**
 * The editor: photo on top, controls at the bottom.
 *
 * Everything the user changes here is throw away UI state, apart from the stretches committed
 * with "Apply", which live in [PhotoViewModel] so undo, redo and the export can use them.
 */
@Composable
fun EditorScreen(
    viewModel: PhotoViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state
    val controls = remember { EditorControls() }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askToDiscard by remember { mutableStateOf(false) }

    fun saveNow() {
        viewModel.save(
            ops = state.ops + listOfNotNull(controls.pendingOp()),
            format = controls.format,
            smoothEdges = controls.smoothEdges,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) saveNow() }

    fun save() {
        if (ImageSaver.hasLegacyWritePermission(context)) {
            saveNow()
        } else {
            permissionLauncher.launch(ImageSaver.legacyWritePermission())
        }
    }

    fun share() {
        val saved = state.save as? SaveState.Done ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = controls.format.mimeType
            putExtra(Intent.EXTRA_STREAM, saved.image.shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser)))
    }

    fun leave() {
        if (controls.isDirty || state.ops.isNotEmpty()) askToDiscard = true else onExit()
    }

    LaunchedEffect(state.save) {
        when (val save = state.save) {
            is SaveState.Done -> {
                val message = context.getString(R.string.saved, ImageSaver.FOLDER) +
                    if (save.reduced) " " + context.getString(R.string.saved_smaller) else ""
                snackbar.showSnackbar(message)
            }
            is SaveState.Failed -> snackbar.showSnackbar(save.message)
            else -> Unit
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
    }

    BackHandler { leave() }

    if (askToDiscard) {
        AlertDialog(
            onDismissRequest = { askToDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askToDiscard = false
                    onExit()
                }) { Text(stringResource(R.string.discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askToDiscard = false }) {
                    Text(stringResource(R.string.discard_keep))
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        TopBar(
            saving = state.save is SaveState.Working,
            canSave = state.ops.isNotEmpty() || controls.canSave,
            canShare = state.save is SaveState.Done,
            onBack = ::leave,
            onSave = ::save,
            onShare = ::share,
        )

        SnackbarHost(hostState = snackbar, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = state.result
            val image = remember(bitmap) { bitmap?.asImageBitmap() }
            if (image == null) {
                CircularProgressIndicator()
            } else {
                PhotoCanvas(
                    image = image,
                    modifier = Modifier.fillMaxSize(),
                    axis = controls.axis,
                    lines = controls.lines,
                    factor = controls.factor,
                    smoothEdges = controls.smoothEdges,
                    onTap = controls::onTap,
                    onDragLine = controls::onDragLine,
                    onDragBand = controls::onDragBand,
                )
            }
        }

        BottomPanel(
            controls = controls,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            appliedCount = state.ops.size,
            onReset = controls::resetLines,
            onApply = {
                controls.pendingOp()?.let(viewModel::apply)
                controls.afterApply()
            },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onRestart = {
                viewModel.restart()
                controls.resetLines()
            },
        )
    }
}

@Composable
private fun TopBar(
    saving: Boolean,
    canSave: Boolean,
    canShare: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (canShare) {
            TextButton(onClick = onShare) { Text(stringResource(R.string.action_share)) }
        }
        if (saving) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
            Spacer(Modifier.width(12.dp))
        } else {
            TextButton(onClick = onSave, enabled = canSave) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun BottomPanel(
    controls: EditorControls,
    canUndo: Boolean,
    canRedo: Boolean,
    appliedCount: Int,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRestart: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = hint(controls, appliedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = if (controls.hasBand) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Accent
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (controls.hasBand) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (controls.squash) R.string.control_squash else R.string.control_stretch,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${controls.percent}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (controls.squash) Warn else Accent,
                    )
                }
                Slider(
                    value = controls.percent.toFloat(),
                    onValueChange = controls::setPercent,
                    valueRange = controls.stretchRange,
                    colors = SliderDefaults.colors(
                        thumbColor = if (controls.squash) Warn else Accent,
                        activeTrackColor = if (controls.squash) Warn else Accent,
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleChip(
                    text = stringResource(
                        if (controls.axis == Axis.VERTICAL) R.string.control_vertical else R.string.control_horizontal,
                    ),
                    active = controls.axis == Axis.HORIZONTAL,
                    onClick = controls::toggleAxis,
                )
                ToggleChip(
                    text = stringResource(R.string.control_smooth),
                    active = controls.smoothEdges,
                    onClick = controls::toggleSmooth,
                )
                ToggleChip(
                    text = controls.format.extension.uppercase(),
                    active = controls.format == SaveFormat.PNG,
                    onClick = controls::toggleFormat,
                    activeColor = Warn,
                )
            }

            ButtonRow {
                EqualWidth {
                    ActionButton(stringResource(R.string.action_reset), onReset, enabled = controls.isDirty)
                }
                EqualWidth {
                    ActionButton(
                        text = stringResource(R.string.action_apply),
                        onClick = onApply,
                        enabled = controls.canSave,
                    )
                }
                EqualWidth { ActionButton(stringResource(R.string.action_undo), onUndo, enabled = canUndo) }
                EqualWidth { ActionButton(stringResource(R.string.action_redo), onRedo, enabled = canRedo) }
                if (appliedCount > 0) {
                    EqualWidth { ActionButton(stringResource(R.string.action_restart), onRestart) }
                }
            }
        }
    }
}

@Composable
private fun hint(controls: EditorControls, appliedCount: Int): String = when {
    appliedCount > 0 && !controls.hasBand -> stringResource(R.string.hint_applied)
    controls.lines.isEmpty() -> stringResource(R.string.hint_first)
    controls.lines.size == 1 -> stringResource(R.string.hint_second)
    else -> stringResource(R.string.hint_ready)
}
