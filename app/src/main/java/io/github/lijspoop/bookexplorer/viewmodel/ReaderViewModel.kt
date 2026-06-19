package io.github.lijspoop.bookexplorer.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lijspoop.bookexplorer.model.BookChapter
import io.github.lijspoop.bookexplorer.repository.BookRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ReaderViewModel(private val repository: BookRepository) : ViewModel() {

    private val _chapters = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapters: StateFlow<List<BookChapter>> = _chapters.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    val currentChapter: StateFlow<BookChapter?> = combine(
        chapters,
        currentIndex
    ) { list: List<BookChapter>, index: Int ->
        list.getOrNull(index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _editingEnabled = MutableStateFlow(false)
    val editingEnabled: StateFlow<Boolean> = _editingEnabled.asStateFlow()

    var currentBookUri: Uri? = null
        private set

    var currentFormat: String? = null
        private set

    fun loadBook(uri: Uri, format: String) {
        currentBookUri = uri
        currentFormat = format
        viewModelScope.launch {
            val result = when (format) {
                "epub" -> repository.parseEpub(uri)
                "fb2" -> repository.parseFb2(uri)
                else -> emptyList()
            }
            _chapters.value = result
            _currentIndex.value = 0
        }
    }

    fun goToChapter(index: Int) {
        if (index in _chapters.value.indices) {
            _currentIndex.value = index
        }
    }

    fun toggleEditing() {
        _editingEnabled.update { !it }
    }

    fun updateCurrentChapterHtml(newHtml: String) {
        val index = _currentIndex.value
        val list = _chapters.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(htmlContent = newHtml, isDirty = true)
            _chapters.value = list
        }
    }

    // Опционально: метод для пометки без изменения содержимого (не нужен сейчас)
    // fun markCurrentChapterDirty() { ... }

    /**
     * Сохраняет книгу. После успешного сохранения сбрасывает флаги isDirty.
     * @param newUri – если null, перезаписывается исходный файл;
     *                иначе книга сохраняется как новый файл по указанному URI.
     */
    fun saveBook(newUri: Uri? = null) {
        val originalUri = currentBookUri ?: return
        val chapters = _chapters.value
        val format = currentFormat ?: return
        val target = newUri ?: originalUri
        viewModelScope.launch {
            try {
                repository.saveBookToUri(originalUri, chapters, format, target)
                if (newUri != null) {
                    currentBookUri = newUri
                }
                _chapters.value = _chapters.value.map { it.copy(isDirty = false) }
            } catch (e: Exception) {
                android.util.Log.e("BookSave", "Ошибка сохранения", e)
                // Показать пользователю сообщение с краткой ошибкой
                // (можно через MutableStateFlow<String> или через callback)
                throw e // или обработать
            }
        }
    }
}
