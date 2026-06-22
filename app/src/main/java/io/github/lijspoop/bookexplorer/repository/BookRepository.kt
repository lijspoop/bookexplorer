package io.github.lijspoop.bookexplorer.repository

import android.content.Context
import android.net.Uri
import io.documentnode.epub4j.domain.Book
import io.documentnode.epub4j.epub.EpubReader
import io.documentnode.epub4j.epub.EpubWriter
import io.github.lijspoop.bookexplorer.model.BookChapter
import io.github.lijspoop.bookexplorer.model.BookPage
import io.github.lijspoop.bookexplorer.model.ServicePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(private val context: Context) {

    // ---------- EPUB ----------
    suspend fun parseEpub(uri: Uri): List<BookPage> = withContext(Dispatchers.IO) {
        val pages = mutableListOf<BookPage>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val book: Book = EpubReader().readEpub(stream)
            for (spineRef in book.spine.spineReferences) {
                val resource = spineRef.resource
                val href = resource.href ?: continue
                val isRealChapter = spineRef.isLinear
                        && !isServiceHref(href)
                        && (resource.mediaType?.name in listOf("application/xhtml+xml", "text/html"))

                val title = resource.title ?: href
                val htmlContent = String(resource.data, Charsets.UTF_8)
                val bodyContent = extractBodyContent(htmlContent)
                val cleanHref = href.removePrefix("./")

                val page = if (isRealChapter) {
                    BookChapter(
                        title = title,
                        initialHtml = htmlContent,
                        htmlContent = htmlContent,
                        bodyContent = bodyContent,
                        internalPath = cleanHref)
                } else {
                    ServicePage(
                        title = servicePageTitle(title),
                        initialHtml = htmlContent,
                        htmlContent = htmlContent,
                        bodyContent = bodyContent,
                        internalPath = cleanHref)
                }
                pages.add(page)
            }
        }
        pages
    }

    /**
     * Проверяет, что временный EPUB-файл читается без ошибок.
     */
    private fun validateEpub(file: File): Boolean {
        return try {
            file.inputStream().use { EpubReader().readEpub(it) }
            true
        } catch (e: Exception) {
            android.util.Log.e("BookSave", "Invalid EPUB", e)
            false
        }
    }
    private fun saveEpubInternal(
        originalUri: Uri,
        pages: List<BookPage>,
        targetUri: Uri
    ) {
        val book: Book = context.contentResolver.openInputStream(originalUri)?.use { stream ->
            EpubReader().readEpub(stream)
        } ?: throw Exception("Cannot read original EPUB")

        // 2. Для каждой главы обновляем соответствующий ресурс
        for (page in pages) {
            if (page.isDirty && page.internalPath.isNotEmpty()) {
                // Ищем ресурс по href
                val resource = book.resources.getByHref(page.internalPath)
                if (resource != null) {
                    // Оборачиваем body-контент обратно в полноценный HTML/XHTML
                    val originalHtml = String(resource.data, Charsets.UTF_8)
                    val newHtml = mergeBodyIntoHtml(originalHtml, page.bodyContent)
                    resource.data = newHtml.toByteArray(Charsets.UTF_8)
                } else {
                    android.util.Log.w("BookSave", "Resource not found: ${page.internalPath}")
                }
            }
        }

        // 3. Сохраняем во временный файл и проверяем
        val tmpFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.epub")
        try {
            // Запись во временный файл
            tmpFile.outputStream().use { out ->
                EpubWriter().write(book, out)
            }

            // Валидация – пробуем прочитать
            if (!validateEpub(tmpFile)) {
                throw Exception("Generated EPUB is invalid")
            }

            // Копируем в целевой URI
            val writeMode = if (targetUri == originalUri) "wt" else "w"
            context.contentResolver.openOutputStream(targetUri, writeMode)?.use { out ->
                tmpFile.inputStream().use { it.copyTo(out) }
            } ?: throw Exception("Cannot open file for writing")
        } finally {
            tmpFile.delete()
        }
    }

    // ---------- FB2 ----------
/*
    suspend fun parseFb2(uri: Uri): List<BookChapter> = withContext(Dispatchers.IO) {
        val chapters = mutableListOf<BookChapter>()
        val xmlString = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: return@withContext chapters

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(xmlString.byteInputStream())
        doc.documentElement.normalize()

        val body = doc.getElementsByTagName("body").item(0) as? Element
            ?: return@withContext chapters
        val sections = body.getElementsByTagName("section")
        for (i in 0 until sections.length) {
            val section = sections.item(i) as Element
            val title = section.getElementsByTagName("title").item(0)?.textContent ?: "Section $i"
            val html = fb2SectionToHtml(section)
            chapters.add(
                BookChapter(
                    title = title,
                    htmlContent = html,
                    bodyContent = extractBodyContent(html),
                    internalPath = i.toString() // для FB2 будем использовать индекс
                )
            )
        }
        chapters
    }

    private fun fb2SectionToHtml(section: Element): String {
        val sb = StringBuilder()
        val childNodes = section.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            when (node.nodeType) {
                org.w3c.dom.Node.ELEMENT_NODE -> {
                    val el = node as Element
                    when (el.tagName) {
                        "title" -> sb.append("<h2>${el.textContent}</h2>")
                        "p" -> sb.append("<p>${el.textContent}</p>")
                        "strong" -> sb.append("<strong>${el.textContent}</strong>")
                        "emphasis" -> sb.append("<em>${el.textContent}</em>")
                        "image" -> {
                            val href = el.getAttribute("l:href")?.removePrefix("#") ?: ""
                            sb.append("<img src=\"$href\" />")
                        }
                        // другие теги можно добавить по необходимости
                        else -> sb.append(el.textContent)
                    }
                }
                org.w3c.dom.Node.TEXT_NODE -> sb.append(node.textContent)
            }
        }
        return sb.toString()
    }

    // Заполняет FB2-секцию содержимым главы
    private fun updateFb2Section(section: Element, page: BookPage, doc: org.w3c.dom.Document) {
        // Очищаем секцию
        while (section.hasChildNodes()) {
            section.removeChild(section.firstChild)
        }
        // Добавляем заголовок
        val title = doc.createElement("title")
        title.textContent = page.title
        section.appendChild(title)
        // Преобразуем упрощённый HTML обратно в FB2-теги
        val paragraphs = page.htmlContent.split(Regex("<p>|</p>"))
            .filter { it.isNotBlank() }
        for (pText in paragraphs) {
            val p = doc.createElement("p")
            // Убираем оставшиеся HTML-теги (очень грубо, но для прототипа сойдёт)
            p.textContent = pText.replace(Regex("<[^>]*>"), "")
            section.appendChild(p)
        }
    }

    private fun saveFb2Internal(
        originalUri: Uri,
        pages: List<BookPage>,
        targetUri: Uri
    ) {
        val xmlString = context.contentResolver.openInputStream(originalUri)?.bufferedReader()?.readText()
            ?: throw Exception("Cannot read original FB2 file")

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(xmlString.byteInputStream())
        doc.documentElement.normalize()

        val body = doc.getElementsByTagName("body").item(0) as? Element
            ?: throw Exception("No body found in FB2")

        // Получаем все секции (существующие)
        val sections = body.getElementsByTagName("section")
        // Если число секций совпадает с числом глав — заменяем содержимое,
        // иначе перестраиваем body (упрощённо: удаляем все section и создаём новые)
        if (sections.length == pages.size) {
            for (i in 0 until sections.length) {
                val section = sections.item(i) as Element
                updateFb2Section(section, pages[i], doc)
            }
        } else {
            // Удаляем все section
            val toRemove = mutableListOf<Element>()
            for (i in 0 until sections.length) {
                toRemove.add(sections.item(i) as Element)
            }
            toRemove.forEach { body.removeChild(it) }
            // Добавляем новые секции
            for (chapter in pages) {
                val section = doc.createElement("section")
                updateFb2Section(section, chapter, doc)
                body.appendChild(section)
            }
        }

        // Сохраняем бинарные данные (изображения) без изменений – они уже есть в doc
        // Сериализуем
        val transformer = TransformerFactory.newInstance().newTransformer()
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        val newXml = writer.toString()

        val writeMode = if (targetUri == originalUri) "wt" else "w"
        context.contentResolver.openOutputStream(targetUri, writeMode)?.bufferedWriter()?.use {
            it.write(newXml)
        } ?: throw Exception("Cannot write to target URI")
    }*/

    // ---------- Сохранение ----------
    /**
     * Сохраняет книгу в [targetUri].
     * Если [targetUri] совпадает с [originalUri], происходит перезапись.
     */
    suspend fun saveBookToUri(
        originalUri: Uri,
        pages: List<BookPage>,
        format: String,
        targetUri: Uri
    ) = withContext(Dispatchers.IO) {
        when (format) {
            "epub" -> saveEpubInternal(originalUri, pages, targetUri)
//            "fb2"  -> saveFb2Internal(originalUri, pages, targetUri)
        }
    }

    // ---------- Вспомогательные методы ----------
    fun extractBodyContent(html: String): String {
        val bodyRegex = Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
        return bodyRegex.find(html)?.groupValues?.get(1) ?: html
    }

    /**
     * Вставляет новое содержимое body в оригинальный HTML-файл главы,
     * сохраняя все атрибуты тега <body> и остальную структуру <head>.
     */
    fun mergeBodyIntoHtml(target: String, bodyContent: String): String {
        val bodyRegex = Regex("(<body[^>]*>)(.*?)(</body>)", RegexOption.DOT_MATCHES_ALL)
        return bodyRegex.replaceFirst(target, "$1$bodyContent$3")
    }

    /** Проверяет, является ли href типичным для служебной страницы */
    private fun isServiceHref(href: String) =
        listOf("toc", "nav", "cover", "titlepage", "copyright", "contents", "intro")
            .any { href.contains(it, ignoreCase = true) }

    /** Русифицирует название служебной страницы или оставляет оригинал */
    private fun servicePageTitle(title: String) = when {
        title.contains("intro", ignoreCase = true) -> "Введение"
        title.contains("contents", ignoreCase = true)
                || title.contains("nav", ignoreCase = true)
                || title.contains("toc", ignoreCase = true) -> "Содержание"
        title.contains("cover", ignoreCase = true) -> "Обложка"
        title.contains("titlepage", ignoreCase = true) -> "Титульная страница"
        title.contains("copyright", ignoreCase = true) -> "Авторские права"
        else -> title
    }
}