package io.github.lijspoop.bookexplorer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lijspoop.bookexplorer.repository.BookRepository
import io.github.lijspoop.bookexplorer.ui.ReaderScreen
import io.github.lijspoop.bookexplorer.viewmodel.ReaderViewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = BookRepository(applicationContext)

        setContent {
            MaterialTheme {
                var showReader by remember { mutableStateOf(false) }

                val readerViewModel: ReaderViewModel = viewModel {
                    ReaderViewModel(repository)
                }

                if (!showReader) {
                    MainScreen(onBookSelected = { uri, format ->
                        readerViewModel.loadBook(uri, format)
                        showReader = true
                    })
                } else {
                    val context = LocalContext.current

                    // Перезапись (кнопка «Сохранить»)
                    val onSaveRequested: () -> Unit = {
                        try {
                            readerViewModel.saveBook()
                            Toast.makeText(context, "Книга перезаписана", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Ошибка перезаписи: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    val folderPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { treeUri ->
                        treeUri?.let {
                            // Получаем persistent права на папку
                            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            contentResolver.takePersistableUriPermission(it, takeFlags)

                            // Определяем имя подпапки (название книги без расширения)
                            val originalName = getOriginalFileName(readerViewModel) ?: "book"
                            val bookFolder = DocumentFile.fromTreeUri(this@MainActivity, it)
                            var subFolder = bookFolder?.findFile(originalName)
                            if (subFolder == null || !subFolder.isDirectory) {
                                subFolder = bookFolder?.createDirectory(originalName)
                            }
                            if (subFolder != null) {
                                // Генерируем имя файла внутри подпапки
                                val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                val ext = if (readerViewModel.currentFormat == "fb2") "fb2" else "epub"
                                val fileName = "${dateStr}_${originalName}.$ext"
                                val file = subFolder.createFile(
                                    if (ext == "fb2") "application/x-fictionbook+xml"
                                    else "application/epub+zip",
                                    fileName
                                )
                                file?.uri?.let { fileUri ->
                                    try {
                                        readerViewModel.saveBook(fileUri)
                                        Toast.makeText(context, "Сохранено в $fileName", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                } ?: Toast.makeText(context, "Не удалось создать файл", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Не удалось создать папку", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    val onSaveAsRequested: () -> Unit = {
                        folderPickerLauncher.launch(null)  // null – выбор любой папки
                    }

//                    // Сохранить как новый файл (кнопка «Сохранить как…»)
//                    val saveLauncher = rememberLauncherForActivityResult(
//                        contract = ActivityResultContracts.CreateDocument(
//                            when (readerViewModel.currentFormat) {
//                                "epub" -> "application/epub+zip"
//                                "fb2"  -> "application/x-fictionbook+xml"
//                                else  -> "*/*"
//                            }
//                        )
//                    ) { uri ->
//                        uri?.let {
//                            try {
//                                readerViewModel.saveBook(it)   // сохраняем как новый файл
//                                Toast.makeText(context, "Файл сохранён", Toast.LENGTH_SHORT).show()
//                            } catch (e: Exception) {
//                                Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
//                            }
//                        }
//                    }
//
//                    val onSaveAsRequested: () -> Unit = {
//                        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//                        val ext = if (readerViewModel.currentFormat == "fb2") "fb2" else "epub"
//                        saveLauncher.launch("book_edited_$dateStr.$ext")
//                    }

                    val onCloseRequested: () -> Unit = {
                        showReader = false
                    }

                    ReaderScreen(
                        viewModel = readerViewModel,
                        onSaveRequested = onSaveRequested,
                        onSaveAsRequested = onSaveAsRequested,
                        onCloseRequested = onCloseRequested
                    )
                }
            }
        }
    }

    // Вспомогательная функция: извлекает имя исходного файла книги (без расширения)
    private fun getOriginalFileName(viewModel: ReaderViewModel): String? {
        val uri = viewModel.currentBookUri ?: return null
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex)?.substringBeforeLast(".")
                else null
            } else null
        }
    }
}

@Composable
fun MainScreen(onBookSelected: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val openBookLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(uri) ?: return@let
            val format = when {
                mimeType == "application/epub+zip" || uri.toString().endsWith(".epub") -> "epub"
                mimeType == "application/x-fictionbook+xml" || uri.toString().endsWith(".fb2") -> "fb2"
                else -> {
                    Toast.makeText(context, "Неподдерживаемый формат", Toast.LENGTH_SHORT).show()
                    return@let
                }
            }
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            onBookSelected(uri, format)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Читалка-редактор", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            openBookLauncher.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/x-fictionbook+xml"
                )
            )
        }) {
            Text("Открыть книгу (EPUB/FB2)")
        }
    }
}