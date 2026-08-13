package com.x
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "phone", widthDp = 360)
@Preview(name = "tablet", widthDp = 800)
@Composable
private fun ScreenPreview() {
    // a comment mentioning R.string.dead_string should NOT count
    Text(stringResource(R.string.covered_direct))
    Screen()
}

@Composable
fun Screen(modifier: Modifier = Modifier) {
    val s = stringResource(R.string.covered_nested)
    val p = pluralStringResource(R.plurals.covered_plural, 1)
    Card { Text(s + p) }
}

@Composable
fun Orphan() = Text(stringResource(R.string.uncovered_composable))

@Composable
fun Text(t: String) {}
@Composable
fun Card(content: @Composable () -> Unit) { content() }
