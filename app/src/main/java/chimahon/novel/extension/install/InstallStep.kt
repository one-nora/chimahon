package chimahon.novel.extension.install

sealed class InstallStep(error: String? = null) {
    object Success : InstallStep(null)
    object Downloading : InstallStep(null)
    object Idle : InstallStep()
    data class Error(val error: String) : InstallStep(error)

    fun isFinished(): Boolean = this is Idle || this is Error || this is Success
    fun isLoading(): Boolean = this is Downloading
}
