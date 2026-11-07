package app.pumping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

/** Shared volume-editor header. The title is centered on the screen, not in the remaining gap. */
@Composable
fun EditorHeader(
    title: String,
    inputUnit: String,
    cancel: () -> Unit,
    toggleUnit: () -> Unit,
    save: () -> Unit,
    saveEnabled: Boolean,
    unitEnabled: Boolean = true,
) {
    val unitStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val unitTextSize =
        listOf("oz", "mL").maxOf { unit ->
            val size =
                measurer.measure(unit, style = unitStyle, maxLines = 1, softWrap = false).size
            maxOf(size.width, size.height)
        }
    val unitFaceSize = with(LocalDensity.current) { maxOf(36.dp, unitTextSize.toDp() + 8.dp) }
    val unitTargetSize = maxOf(48.dp, unitFaceSize)
    Layout(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("editor-header"),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(cancel, Modifier.weight(1f, fill = false).testTag("editor-cancel")) {
                    Text("Cancel")
                }
                Box(
                    modifier =
                        Modifier.size(unitTargetSize)
                            .clip(CircleShape)
                            .clickable(
                                enabled = unitEnabled,
                                role = Role.Button,
                                onClick = toggleUnit,
                            )
                            .semantics {
                                contentDescription = "Input units"
                                stateDescription = inputUnit
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(unitFaceSize).testTag("editor-unit-face"),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                inputUnit,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = unitStyle,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            Button(save, enabled = saveEnabled, modifier = Modifier.testTag("editor-save")) {
                Text("Save")
            }
            Text(
                title,
                modifier = Modifier.testTag("editor-title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val saveButton = measurables[1].measure(loose)
        val left =
            measurables[0].measure(
                loose.copy(maxWidth = (width - saveButton.width - gap).coerceAtLeast(0))
            )
        val titleText = measurables[2].measure(loose)
        val sideWidth = maxOf(left.width, saveButton.width)
        val singleRow = titleText.width + 2 * (sideWidth + gap) <= width
        val controlsHeight = maxOf(left.height, saveButton.height)
        // At narrow widths or large font sizes, give the centered title its own row instead of
        // overlapping controls, truncating the title, or reducing the user's chosen font size.
        val height =
            if (singleRow) maxOf(controlsHeight, titleText.height)
            else controlsHeight + gap + titleText.height
        layout(width, height) {
            val rowHeight = if (singleRow) height else controlsHeight
            left.placeRelative(0, (rowHeight - left.height) / 2)
            saveButton.placeRelative(width - saveButton.width, (rowHeight - saveButton.height) / 2)
            titleText.placeRelative(
                (width - titleText.width) / 2,
                if (singleRow) (height - titleText.height) / 2 else controlsHeight + gap,
            )
        }
    }
}
