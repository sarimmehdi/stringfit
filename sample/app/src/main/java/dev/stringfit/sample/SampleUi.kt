package dev.stringfit.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Sample UI whose only job is to contain, in one place, every case that makes
 * preview-derived measurement hard. Each composable is labelled with what it is
 * meant to prove.
 */

// ---------------------------------------------------------------------------
// CASE A: rendered directly by the preview.
// CASE B: reached one hop down, through LibraryRow.
// CASE C: behind a branch the preview does not take.
// ---------------------------------------------------------------------------

@Composable
fun LibraryScreen(
    isOffline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.screen_title),      // CASE A
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (isOffline) {
            // CASE C: statically reachable from the preview below, but the
            // preview passes isOffline = false, so it never composes.
            Text(stringResource(R.string.error_offline))
        }

        LibraryRow()

        // CASE D: item 40 of a lazy list -- composed only if it scrolls in.
        LazyColumn(Modifier.fillMaxWidth()) {
            items((1..40).toList()) { index ->
                if (index == 40) {
                    Text(stringResource(R.string.footer_note))
                } else {
                    Text("Row $index")
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(R.string.item_subtitle),     // CASE B
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // MULTI-SITE, tight instance: a 72dp chip.
        NarrowChip(label = stringResource(R.string.action_cancel))
    }
}

// ---------------------------------------------------------------------------
// MULTI-SITE: action_cancel renders in two places with wildly different room.
// This is the case where one translation must satisfy two budgets.
// ---------------------------------------------------------------------------

@Composable
fun NarrowChip(label: String, modifier: Modifier = Modifier) {
    Box(modifier.width(72.dp)) {                               // hard constraint
        Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ConfirmDialogButtons(
    confirmText: String = stringResource(R.string.button_confirm),  // CASE E
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        // MULTI-SITE, roomy instance: same string, ~150dp of space.
        Button(onClick = {}) {
            Text(stringResource(R.string.action_cancel), maxLines = 1)
        }
        Button(onClick = {}) { Text(confirmText, maxLines = 1) }
    }
}

// ---------------------------------------------------------------------------
// CASE F: format argument. The budget depends on the substituted value, and
// the preview supplies a short one.
// CASE G: deliberately tight -- fits at fontScale 1.0, clips at 1.3.
// ---------------------------------------------------------------------------

@Composable
fun GreetingBar(userName: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.greeting, userName),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NarrowChip(label = stringResource(R.string.action_download_all))
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "library", widthDp = 360)
@Composable
private fun LibraryScreenPreview() {
    Surface { LibraryScreen(isOffline = false) }
}

@Preview(name = "dialog", widthDp = 360)
@Composable
private fun ConfirmDialogButtonsPreview() {
    Surface { ConfirmDialogButtons() }
}

@Preview(name = "greeting", widthDp = 360)
@Preview(name = "greeting-large-font", widthDp = 360, fontScale = 1.3f)
@Composable
private fun GreetingBarPreview() {
    Surface { GreetingBar(userName = "Sam") }
}
