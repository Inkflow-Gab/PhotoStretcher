package com.photostretcher.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.photostretcher.app.R
import com.photostretcher.app.ui.theme.Accent

/**
 * The start screen: one button, a short explanation and a drawing of what the app does.
 */
@Composable
fun HomeScreen(
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
        StretchIllustration(Modifier.fillMaxWidth().height(180.dp))
        Spacer(Modifier.height(28.dp))

        BigButton(text = stringResource(R.string.home_pick_photo), onClick = onPickPhoto)

        Spacer(Modifier.height(28.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Step(number = "1", text = stringResource(R.string.home_step_1))
                Step(number = "2", text = stringResource(R.string.home_step_2))
                Step(number = "3", text = stringResource(R.string.home_step_3))
                Step(number = "4", text = stringResource(R.string.home_step_4))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.home_privacy),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = number,
            style = MaterialTheme.typography.titleSmall,
            color = Accent,
            modifier = Modifier.size(width = 24.dp, height = 24.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small drawing of a photo whose middle band is being pulled taller. */
@Composable
private fun StretchIllustration(modifier: Modifier = Modifier) {
    val accent = Accent
    val frame = Color(0xFF2C3644)
    Canvas(modifier) {
        val width = size.width * 0.62f
        val height = size.height
        val left = (size.width - width) / 2f
        val top = 0f
        val bandTop = height * 0.32f
        val bandBottom = height * 0.78f
        val radius = 12.dp.toPx()

        // The photo: a rounded frame, a band, and a long body.
        drawRoundRect(
            color = frame.copy(alpha = 0.45f),
            topLeft = Offset(left, top),
            size = Size(width, bandTop - 6.dp.toPx()),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.16f)),
                startY = bandTop,
                endY = bandBottom,
            ),
            topLeft = Offset(left, bandTop),
            size = Size(width, bandBottom - bandTop),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        )
        drawRoundRect(
            color = frame.copy(alpha = 0.45f),
            topLeft = Offset(left, bandBottom + 6.dp.toPx()),
            size = Size(width, height - bandBottom - 6.dp.toPx()),
            cornerRadius = CornerRadius(radius, radius),
        )
        // The two lines
        drawLine(
            color = accent,
            start = Offset(left - 8.dp.toPx(), bandTop),
            end = Offset(left + width + 8.dp.toPx(), bandTop),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = accent,
            start = Offset(left - 8.dp.toPx(), bandBottom),
            end = Offset(left + width + 8.dp.toPx(), bandBottom),
            strokeWidth = 2.dp.toPx(),
        )
        // Up and down arrows in the middle of the band
        val middle = Offset(size.width / 2f, (bandTop + bandBottom) / 2f)
        drawCircle(color = accent.copy(alpha = 0.18f), radius = 20.dp.toPx(), center = middle)
        drawLine(
            color = accent,
            start = Offset(middle.x, middle.y + 12.dp.toPx()),
            end = Offset(middle.x, middle.y - 12.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = accent,
            start = Offset(middle.x - 6.dp.toPx(), middle.y - 5.dp.toPx()),
            end = Offset(middle.x, middle.y - 13.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent,
            start = Offset(middle.x + 6.dp.toPx(), middle.y + 5.dp.toPx()),
            end = Offset(middle.x, middle.y + 13.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
