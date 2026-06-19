package io.github.lijspoop.bookexplorer.ui

import android.annotation.SuppressLint
import android.util.JsonReader
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lijspoop.bookexplorer.viewmodel.ReaderViewModel
import java.io.StringReader

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onSaveRequested: () -> Unit = {},     // перезапись исходного файла
    onSaveAsRequested: () -> Unit = {},   // сохранить как новый файл
    onCloseRequested: () -> Unit = {}
) {
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()
    val editingEnabled by viewModel.editingEnabled.collectAsStateWithLifecycle()
    val currentChapter by viewModel.currentChapter.collectAsStateWithLifecycle()

    var webView by remember { mutableStateOf<WebView?>(null) }

    // Функция, которая фиксирует изменения и затем выполняет переданное действие
    fun commitEditingAndRun(action: () -> Unit) {
        if (editingEnabled) {
            webView?.evaluateJavascript("document.body.innerHTML") { result ->
                if (result != null) {
                    val reader = JsonReader(StringReader(result))
                    reader.isLenient = true
                    val html = reader.nextString()
                    reader.close()
                    viewModel.updateCurrentChapterHtml(html)
                    viewModel.toggleEditing() // выключаем режим редактирования
                    action()
                }
            }
        } else {
            // Если не в режиме редактирования, сразу выполняем
            action()
        }
    }

    // Функция, которая вытаскивает HTML из WebView и сохраняет в текущую главу,
    // после чего выполняет переданное действие.
    fun commitCurrentChapter(onDone: () -> Unit) {
        webView?.evaluateJavascript("document.body.innerHTML") { result ->
            if (result != null) {
                val reader = JsonReader(StringReader(result))
                reader.isLenient = true
                val html = reader.nextString()
                reader.close()
                viewModel.updateCurrentChapterHtml(html)
            }
            onDone()
        }
    }

    // Обработчик завершения редактирования (без навигации)
    fun finishEditing() {
        webView?.evaluateJavascript("document.body.innerHTML") { result ->
            if (result != null) {
                val reader = JsonReader(StringReader(result))
                reader.isLenient = true
                val html = reader.nextString()
                reader.close()
                viewModel.updateCurrentChapterHtml(html)
                viewModel.toggleEditing()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхняя панель инструментов
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = {
                if (editingEnabled) {
                    finishEditing()
                } else {
                    viewModel.toggleEditing()
                }
            }) {
                Text(if (editingEnabled) "Закончить правку" else "Редактировать")
            }

            Button(onClick = onCloseRequested) {
                Text("Закрыть")
            }
        }

        // Навигация по главам – перед переходом фиксируем текущую главу
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = {
                if (currentIndex > 0) {
                    commitCurrentChapter {
                        viewModel.goToChapter(currentIndex - 1)
                    }
                }
            }) {
                Text("←")
            }
            Text("Глава ${currentIndex + 1}/${chapters.size}")
            Button(onClick = {
                if (currentIndex < chapters.lastIndex) {
                    commitCurrentChapter {
                        viewModel.goToChapter(currentIndex + 1)
                    }
                }
            }) {
                Text("→")
            }
        }

        // WebView
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = WebViewClient()
                    addJavascriptInterface(EditorBridge(viewModel), "AndroidEditor")
                }
            },
            update = { wv ->
                webView = wv
                val chapterContent = currentChapter?.htmlContent ?: ""
                val fullHtml = buildEditableHtml(chapterContent, editingEnabled)
                wv.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            },
            modifier = Modifier.weight(1f)
        )

        // Кнопки сохранения – обе фиксируют текущую главу перед выполнением
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { commitCurrentChapter { onSaveRequested() } },
                modifier = Modifier.weight(1f)
            ) {
                Text("Сохранить")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { commitCurrentChapter { onSaveAsRequested() } },
                modifier = Modifier.weight(1f)
            ) {
                Text("Сохранить как…")
            }
        }
    }
}

class EditorBridge(private val viewModel: ReaderViewModel) {
    @JavascriptInterface
    fun getEditedHtml(html: String) {
        viewModel.updateCurrentChapterHtml(html)
    }
}

fun buildEditableHtml(bodyContent: String, editable: Boolean): String {
    val editableAttr = if (editable) "contenteditable=\"true\"" else ""
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                body { font-size: 18px; line-height: 1.6; padding: 16px; font-family: serif; }
                img { max-width: 100%; height: auto; }
            </style>
        </head>
        <body $editableAttr>
            $bodyContent
        </body>
        </html>
    """.trimIndent()
}