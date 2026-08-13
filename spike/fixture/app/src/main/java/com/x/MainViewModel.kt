package com.x
class MainViewModel(private val app: Application) {
    fun error(): String = app.getString(R.string.in_viewmodel)
}
