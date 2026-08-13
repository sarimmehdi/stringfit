package dev.stringfit.agp8

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.agp8_roomy_title), maxLines = 1)
        // 72dp is not enough for the German "Herunterladen".
        Box(Modifier.width(72.dp)) {
            Text(
                text = stringResource(R.string.agp8_action_download),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "downloads", widthDp = 360)
@Composable
private fun DownloadsScreenPreview() {
    Surface { DownloadsScreen() }
}
