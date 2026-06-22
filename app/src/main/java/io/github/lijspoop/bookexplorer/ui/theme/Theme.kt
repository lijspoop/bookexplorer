package io.github.lijspoop.bookexplorer.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

data class ReaderColors(
    val text: Color,
    val headline: Color,
    val background: Color,
    val link: Color
) {
    companion object {
        fun from(scheme: ColorScheme) = ReaderColors(
            headline = scheme.primary,
            text = scheme.onBackground,
            background = scheme.background,
            link = scheme.primary
        )
    }
}

val LocalDarkTheme = compositionLocalOf { false }
val LocalReaderColors = compositionLocalOf {
    ReaderColors(
        headline = Color.Unspecified,
        text = Color.Unspecified,
        background = Color.Unspecified,
        link = Color.Unspecified
    )
}

@Composable
fun BookExplorerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val readerColors = ReaderColors.from(colorScheme)

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalReaderColors provides readerColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(readerColors.background)
                    .statusBarsPadding(), // для enableEdgeToEdge()
            ) {
                content()
            }
        }
    }
}