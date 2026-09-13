package com.example.screenpet

import android.webkit.JavascriptInterface

class PetBridge(
    private val onPetClick: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit
) {
    @JavascriptInterface
    fun onPetClick(): Unit {
        onPetClick.invoke()
    }

    @JavascriptInterface
    fun onDrag(dx: Float, dy: Float): Unit {
        onDrag.invoke(dx, dy)
    }

    @JavascriptInterface
    fun onDragEnd(): Unit {
        onDragEnd.invoke()
    }
}
