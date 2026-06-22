package io.github.lijspoop.bookexplorer.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lijspoop.bookexplorer.model.BookChapter
import io.github.lijspoop.bookexplorer.model.BookPage
import io.github.lijspoop.bookexplorer.model.ServicePage
import io.github.lijspoop.bookexplorer.repository.BookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ReaderViewModel(private val repository: BookRepository) : ViewModel() {

    private val _pages = MutableStateFlow<List<BookPage>>(emptyList())
    val pages: StateFlow<List<BookPage>> = _pages.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // Текущая страница (любого типа)
    val currentPage: StateFlow<BookPage?> = combine(pages, currentIndex) { list, index ->
        list.getOrNull(index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Дополнительное свойство: только главы (для счётчика)
    val chapters: StateFlow<List<BookChapter>> = pages.map { list ->
        list.filterIsInstance<BookChapter>()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var currentBookUri: Uri? = null
        private set

    var currentFormat: String? = null
        private set

    private val _editingEnabled = MutableStateFlow(false)
    val editable: StateFlow<Boolean> = _editingEnabled.asStateFlow()

    val hasChanges: StateFlow<Boolean> = _pages
        .map { pages -> pages.any { it.isDirty } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadBook(uri: Uri, format: String) {
        currentBookUri = uri
        currentFormat = format
        _isLoading.value = true
        viewModelScope.launch {
            try {
                _pages.value = when (format) {
                    "epub" -> repository.parseEpub(uri)
//                    "fb2" -> repository.parseFb2(uri).map { it as BookPage }
                    else -> emptyList()
                }
                _currentIndex.value = 0
            } catch (e: Exception) {
                throw e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeBook() {
        _pages.value = emptyList()
        _currentIndex.value = -1
        _editingEnabled.value = false
        currentBookUri = null
        currentFormat = null
    }

    fun goToChapter(index: Int) {
        if (index in _pages.value.indices) {
            _currentIndex.value = index
        }
    }

    fun toggleEditing() {
        _editingEnabled.update { !it }
    }

    // Опционально: метод для пометки без изменения содержимого (не нужен сейчас)
    // fun markCurrentChapterDirty() { ... }

    fun updateCurrentPageHtml(newHtml: String? = null, newBody: String? = null) {
        val index = _currentIndex.value
        val list = _pages.value.toMutableList()
        val page = list.getOrNull(index) ?: return

        val htmlContent = if (newBody != null) repository.mergeBodyIntoHtml(page.htmlContent, newBody)
                          else newHtml ?: return
        val bodyContent = newBody ?: repository.extractBodyContent(htmlContent)

//        android.util.Log.d($$"ReaderViewModel$updateCurrentPageHtml", diffStrings(page.bodyContent, bodyContent, 700))

        list[index] = when (page) {
            is BookChapter -> page.copy(
                htmlContent = htmlContent,
                bodyContent = bodyContent,
                isDirty = htmlContent != page.initialHtml)
            is ServicePage -> page.copy(
                htmlContent = htmlContent,
                bodyContent = bodyContent,
                isDirty = htmlContent != page.initialHtml)
        }
        _pages.value = list
    }

    /**
     * Сохраняет книгу. После успешного сохранения сбрасывает флаги isDirty.
     * @param newUri – если null, перезаписывается исходный файл;
     *                иначе книга сохраняется как новый файл по указанному URI.
     */
    fun saveBook(newUri: Uri? = null) {
        if (_isSaving.value) return
        _isSaving.value = true
        _isLoading.value = true

        val originalUri = currentBookUri ?: return
        val allPages = _pages.value
        val format = currentFormat ?: return
        val target = newUri ?: originalUri
        viewModelScope.launch {
            try {
                repository.saveBookToUri(originalUri, allPages, format, target)

                if (newUri != null) currentBookUri = newUri
                _pages.value = _pages.value.map { page ->
                    if (page.isDirty) {
                        when (page) {
                            is BookChapter -> page.copy(isDirty = false, initialHtml = page.htmlContent)
                            is ServicePage -> page.copy(isDirty = false, initialHtml = page.htmlContent)
                        }
                    } else {
                        page
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BookSave", "Ошибка сохранения", e)
                throw e
            } finally {
                _isSaving.value = false
                _isLoading.value = false
            }
        }
    }
}
