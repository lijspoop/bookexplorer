package io.github.lijspoop.bookexplorer.model


sealed class BookPage {
    abstract val title: String
    abstract val initialHtml: String
    abstract val htmlContent: String
    abstract val bodyContent: String
    abstract val internalPath: String
    abstract val isDirty: Boolean
}

data class BookChapter(
    override val title: String,
    override val initialHtml: String,
    override val htmlContent: String,
    override val bodyContent: String,
    override val internalPath: String = "",
    override val isDirty: Boolean = false
) : BookPage()

data class ServicePage(
    override val title: String,
    override val initialHtml: String,
    override val htmlContent: String,
    override val bodyContent: String,
    override val internalPath: String = "",
    override val isDirty: Boolean = false
) : BookPage()
