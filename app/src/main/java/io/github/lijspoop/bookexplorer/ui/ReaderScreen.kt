package io.github.lijspoop.bookexplorer.ui

import android.annotation.SuppressLint
import android.util.JsonReader
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lijspoop.bookexplorer.model.BookChapter
import io.github.lijspoop.bookexplorer.repository.BookRepository
import io.github.lijspoop.bookexplorer.viewmodel.ReaderViewModel
import io.github.lijspoop.bookexplorer.ui.theme.BookExplorerTheme
import io.github.lijspoop.bookexplorer.ui.theme.ReaderColors
import java.io.StringReader

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onCloseRequested: () -> Unit = {},
    onSaveRequested: () -> Unit = {},     // перезапись исходного файла
    onSaveAsRequested: () -> Unit = {}    // сохранить как новый файл
) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()

    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentIndex.collectAsStateWithLifecycle()

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val editable by viewModel.editable.collectAsStateWithLifecycle()

    var webView by remember { mutableStateOf<WebView?>(null) }

    val readerColors = ReaderColors.from(MaterialTheme.colorScheme)

    var isUIVisible by remember { mutableStateOf(false) }

/*    // обработка отступов
    val density = LocalDensity.current.density

    var targetTopPadding by remember { mutableFloatStateOf(0f) }
    var targetBottomPadding by remember { mutableFloatStateOf(0f) }

    var isAtTop by remember { mutableStateOf(true) }
    var isAtBottom by remember { mutableStateOf(false) }

    val topThreshold = targetTopPadding / density
    val bottomThreshold = targetBottomPadding / density

    val onScrollChanged = remember { { scrollY: Int, contentHeight: Int, viewHeight: Int ->
        isAtTop = scrollY <= 60
        isAtBottom = scrollY + viewHeight >= contentHeight - 30
    } }

    val topPadding by animateFloatAsState(
        targetValue = if ((isUIVisible || editable) && isAtTop) topThreshold else 0f,
        animationSpec = tween(300)
    )
    val bottomPadding by animateFloatAsState(
        targetValue = if ((isUIVisible || editable) && isAtBottom) bottomThreshold else 0f,
        animationSpec = tween(300)
    )*/

    // Функция, которая вытаскивает HTML из WebView и сохраняет в текущую главу,
    // после чего выполняет переданное действие.
    fun commitCurrentChapter(onDone: () -> Unit) {
        val currentBody = currentPage?.bodyContent ?: ""

        webView?.evaluateJavascript("document.body.innerHTML") { rawBody ->
            if (rawBody != null) {

                val reader = JsonReader(StringReader(rawBody))
                reader.isLenient = true
                val newBody = reader.nextString().removeSuffix("\n        ")
                reader.close()

                if (newBody != currentBody) {
                    viewModel.updateCurrentPageHtml(newBody = newBody)
                } else {
                    Log.d("commitCurrentChapter", "Изменений нет (длина: ${currentBody.length})")
                }
            }
            onDone()
        }
    }

    @Composable
    fun ContentView(modifier: Modifier) {
        val toggleUI = remember { { isUIVisible = !isUIVisible } }
        // WebView
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false

                    webViewClient = object : WebViewClient() {
                        // обработка ссылок
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url ?: return false
                            Log.d("ReaderScreen", "URL: $url")

                            // Открываем http(s) ссылки во внешнем браузере
                            if (url.scheme == "http" || url.scheme == "https") {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Не удалось открыть ссылку в браузере", Toast.LENGTH_SHORT).show()
                                }
                                return true
                            }

                            // Обрабатываем только нашу внутреннюю схему app://local/
                            if (url.scheme != "app") return false

                            val path = url.path?.trimStart('/') ?: ""
                            val fragment = url.fragment
                            Log.d("ReaderScreen", "path='$path', fragment='$fragment'")

                            // Если путь пустой – это якорь на текущей странице, разрешаем прокрутку
                            if (path.isEmpty()) return false

                            // Ищем страницу по internalPath (точное совпадение или суффикс)
                            var index = pages.indexOfFirst {
                                it.internalPath.equals(path, ignoreCase = true)
                            }
                            if (index < 0) {
                                index = pages.indexOfFirst {
                                    it.internalPath.endsWith(path, ignoreCase = true)
                                }
                            }

                            if (index >= 0) {
                                if (editable) {
                                    commitCurrentChapter { viewModel.goToChapter(index) }
                                } else {
                                    viewModel.goToChapter(index)
                                }
                                return true
                            }

                            // Страница не найдена
                            Toast.makeText(context, "Страница не найдена", Toast.LENGTH_SHORT).show()
                            return true // блокируем переход в пустоту
                        }
                    }

                    /*setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        val contentHeight = (contentHeight * resources.displayMetrics.density).toInt()
                        val viewHeight = height
                        onScrollChanged(scrollY, contentHeight, viewHeight)
                    }*/

                    if (editable) addJavascriptInterface(EditorBridge(viewModel), "AndroidEditor")

                    // Жест для переключения панелей (тап, не ссылка)
                    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            val hit = hitTestResult
                            // Не переключаем UI, если тап был по ссылке
                            if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE && !editable) {
                                toggleUI()
                            }
                            return false // даём WebView обработать тап (например, переход по ссылке)
                        }
                    })

                    setOnTouchListener { view, event ->
                        if (gestureDetector.onTouchEvent(event)) {
                            view.performClick()
                        }
                        false // всегда пропускаем события к WebView
                    }

                    setBackgroundColor(Color.Transparent.toArgb())
                }
            },
            update = { wv ->
                webView = wv
                val bodyContent = currentPage?.bodyContent ?: ""
                val data = buildEditableHtml(bodyContent, editable, readerColors)
                wv.loadDataWithBaseURL("app://local/", data, "text/html", "UTF-8", null)

//                val bodyRegex = Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
//                val newBody = bodyRegex.find(data)?.groupValues?.get(1) ?: data
//                android.util.Log.d($$"ContentView$update", diffStrings(bodyContent, newBody, 200))
            },
            modifier = modifier
        )
    }

    @Composable
    fun NavBar() {
        // Номер текущей главы среди всех глав (если текущая страница – глава)
        val currentChapterNumber = remember(currentIndex, chapters) {
            if (currentPage is BookChapter) {
                chapters.indexOfFirst { it.internalPath == currentPage?.internalPath } + 1
            } else null
        }

        val label = if (currentChapterNumber != null) {
            "Глава $currentChapterNumber/${chapters.size}"
        } else {
            currentPage?.title ?: ""
        }

        // Навигация по главам – перед переходом фиксируем текущую главу
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(modifier = Modifier.width(48.dp), onClick = {
                if (currentIndex > 0) commitCurrentChapter { viewModel.goToChapter(currentIndex - 1) }
            }) { Text("<", textAlign = TextAlign.Center) }

            Text(label, color = readerColors.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))

            Button( modifier = Modifier.width(48.dp), onClick = {
                if (currentIndex < pages.lastIndex) commitCurrentChapter { viewModel.goToChapter(currentIndex + 1) }
            }) { Text(">", textAlign = TextAlign.Center) }
        }
    }

    @Composable
    fun TopBar(modifier: Modifier = Modifier) {
        val canEdit = currentPage != null

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(readerColors.background)
        ) {
            // Верхняя панель инструментов
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = {
                        if (editable) commitCurrentChapter { viewModel.toggleEditing() }
                        else viewModel.toggleEditing()
                    },
                    enabled = canEdit && !isSaving
                ) {
                    Text(if (editable) "Закончить правку" else "Редактировать")
                }

                Button(onClick = onCloseRequested) {
                    Text("Закрыть")
                }
            }
            AnimatedVisibility(
                visible = !editable,
            ) {
                NavBar()
            }
        }
    }

    @Composable
    fun SaveButtons() {
        // Кнопки сохранения – обе фиксируют текущую главу перед выполнением
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(readerColors.background)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { commitCurrentChapter { onSaveRequested() } },
                enabled = hasChanges && !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text("Сохранить")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { commitCurrentChapter { onSaveAsRequested() } },
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text("Сохранить как…")
            }
        }
    }

    @Composable
    fun BottomBar(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(readerColors.background)
        ) {
            AnimatedVisibility(
                visible = editable || hasChanges,
            ) {
                SaveButtons()
            }
        }
    }

    // отмена изменений
    BackHandler(enabled = editable) {
        viewModel.toggleEditing()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            AnimatedVisibility(
                visible = isUIVisible,
//            modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopBar(/*Modifier.onSizeChanged { size -> targetTopPadding = size.height.toFloat() }*/)
            }

            if (LocalInspectionMode.current) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Содержимое книги", color = readerColors.text)
                }
            } else {
                ContentView(
                    modifier = Modifier
                        .weight(1f)
//                .fillMaxSize()
//                .padding(top = topPadding.dp, bottom = bottomPadding.dp)
                )
            }

            AnimatedVisibility(
                visible = isUIVisible,
//            modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomBar(/*Modifier.onSizeChanged { size -> targetBottomPadding  = size.height.toFloat() }*/)
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = readerColors.headline)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ReaderScreenPreview() {
    val context = LocalContext.current
    BookExplorerTheme(true) {
        ReaderScreen(viewModel { ReaderViewModel(BookRepository(context)) })
    }
}

/*fun diffStrings(old: String, new: String, context: Int = 80): String {
    val sb = StringBuilder()
    val len = minOf(old.length, new.length)
    var i = 0
    while (i < len && old[i] == new[i]) i++
    if (i == len && old.length == new.length) return "Строки идентичны"

    val start = maxOf(0, i - context)
    val oldEnd = minOf(old.length, i + context)
    val newEnd = minOf(new.length, i + context)

    sb.appendLine("Первое различие на позиции $i (показано ± $context символов)")
    sb.appendLine("Старая версия: ...${old.substring(start, oldEnd).escapeWhitespace()}...")
    sb.appendLine("Новая версия : ...${new.substring(start, newEnd).escapeWhitespace()}...")
    sb.appendLine("Различающиеся байты:")
    sb.append("  старый: ")
    for (k in i until minOf(i + 10, old.length)) {
        sb.append("${old[k].code.toString(16)} ")
    }
    sb.appendLine()
    sb.append("  новый : ")
    for (k in i until minOf(i + 10, new.length)) {
        sb.append("${new[k].code.toString(16)} ")
    }
    sb.appendLine()
    sb.appendLine("Длины: старая=${old.length}, новая=${new.length}")
    return sb.toString()
}*/

/*fun String.escapeWhitespace(): String = this
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
    .replace(" ", "·")   // точки для пробелов*/

fun buildEditableHtml(
    bodyContent: String,
    editable: Boolean,
    colors : ReaderColors
): String {
    val editableAttr = if (editable) "contenteditable" else ""
    val textHex = String.format("#%06X", 0xFFFFFF and colors.text.toArgb())
    val bgHex = String.format("#%06X", 0xFFFFFF and colors.background.toArgb())
    val linkHex = String.format("#%06X", 0xFFFFFF and colors.link.toArgb())
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <base href="app://local/">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * {
                    -webkit-user-select: text;
                    user-select: text;
                    -webkit-user-drag: none;
                    user-drag: none;
                }
                body {
                    color: $textHex;
                    background-color: $bgHex;
                    
                    font-family: 'Roboto', sans-serif;
                    font-size: 18px;
                    line-height: 1.6;
                    padding: 16px;
                    
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    overflow-x: hidden;
                }
                a {
                    color: $linkHex;
                    text-decoration: none;
                }
                img {
                    max-width: 100%;
                    height: auto;
                }
            </style>
        </head>
        <body $editableAttr>$bodyContent</body>
        </html>
    """.trimIndent()
}

// интерфейсы
class EditorBridge(private val viewModel: ReaderViewModel) {
    @JavascriptInterface
    fun getEditedHtml(html: String) {
        viewModel.updateCurrentPageHtml(newHtml = html)
    }
}

/*
class ScrollBridge(
    private val onScroll: (isAtTop: Boolean, isAtBottom: Boolean) -> Unit
) {
    @JavascriptInterface
    fun onScrollChanged(scrollTop: Int, clientHeight: Int, scrollHeight: Int) {
        val isAtTop = scrollTop == 0
        val isAtBottom = scrollTop + clientHeight >= scrollHeight - 5  // небольшой допуск
        onScroll(isAtTop, isAtBottom)
    }
}*/
