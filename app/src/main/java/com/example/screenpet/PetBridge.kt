package com.example.screenpet

import android.webkit.JavascriptInterface

class PetBridge(
    private val onPetClick: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit
) {
    @JavascriptInterface
    fun onPetClick() = onPetClick()

    @JavascriptInterface
    fun onDrag(dx: Float, dy: Float) = onDrag(dx, dy)

    @JavascriptInterface
    fun onDragEnd() = onDragEnd()
}