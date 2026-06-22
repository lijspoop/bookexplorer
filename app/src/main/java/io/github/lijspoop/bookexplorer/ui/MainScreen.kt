package io.github.lijspoop.bookexplorer.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.lijspoop.bookexplorer.ui.theme.BookExplorerTheme
import io.github.lijspoop.bookexplorer.ui.theme.LocalReaderColors

@Composable
fun MainScreen(onBookSelected: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val openBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val fileName = run {
                var name: String? = null
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx)
                    }
                }
                name
            }

            val format = when {
                fileName?.endsWith(".epub", ignoreCase = true) == true -> "epub"
//                fileName?.endsWith(".fb2", ignoreCase = true) == true -> "fb2"
                else -> {
                    val mimeType = context.contentResolver.getType(uri)
                    when (mimeType) {
                        "application/epub+zip" -> "epub"
//                        "application/x-fictionbook+xml" -> "fb2"
//                        "application/x-fictionbook" -> "fb2"
                        else -> null
                    }
                }
            }

            if (format == null) {
                Toast.makeText(context, "Неподдерживаемый формат", Toast.LENGTH_SHORT).show()
                return@let
            }

            // Запрашиваем права на чтение и запись
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            onBookSelected(uri, format)
        }
    }

    val readerColors = LocalReaderColors.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("BookExplorer",
            color = readerColors.headline,
            style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            openBookLauncher.launch(
                arrayOf(
                    "application/epub+zip",
//                    "application/x-fictionbook+xml",
//                    "*/*"
                )
            )
        }) {
            Text("Открыть книгу (EPUB)")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    BookExplorerTheme(true) {
        MainScreen { _, _ -> }
    }
}