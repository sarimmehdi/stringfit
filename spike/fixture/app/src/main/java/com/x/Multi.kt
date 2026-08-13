package com.x
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "light")
@Preview(name = "dark", uiMode = 32)
annotation class ThemePreviews

@ThemePreviews
@Composable
fun BannerPreview() { Banner() }

@Composable
fun Banner() { Text(stringResource(R.string.covered_via_multipreview)) }
