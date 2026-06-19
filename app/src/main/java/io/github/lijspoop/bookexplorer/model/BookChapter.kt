package io.github.lijspoop.bookexplorer.model

data class BookChapter(
    val title: String,
    val htmlContent: String,      // содержимое главы как HTML
    val internalPath: String = "", // путь к файлу главы внутри EPUB (для последующего сохранения)
    val isDirty: Boolean = false
)
