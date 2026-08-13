package dev.stringfit.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Design-system component. Its strings live in :core-ui but every preview that
 * renders it lives in :app, so neither module can judge the fit on its own.
 */
@Composable
fun StatusBadge(modifier: Modifier = Modifier) {
    Box(modifier.width(64.dp)) {
        Text(
            text = stringResource(R.string.core_status_downloading),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Box(modifier.width(48.dp)) {
        Text(stringResource(R.string.core_badge_new), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
