package com.example.screenpet

import android.webkit.JavascriptInterface

class PetBridge(
    private val onPetClick: () -> Unit,
    private val onDragTo: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit
) {
    @JavascriptInterface
    fun onPetClick(): Unit {
        onPetClick.invoke()
    }

    @JavascriptInterface
    fun onDragTo(x: Float, y: Float): Unit {
        onDragTo.invoke(x, y)
    }

    @JavascriptInterface
    fun onDragEnd(): Unit {
        onDragEnd.invoke()
    }
}
