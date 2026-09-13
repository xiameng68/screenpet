package com.example.screenpet

import android.webkit.JavascriptInterface

class PetBridge(
    private val onPetClick: () -> Unit,
    private val onDragStart: (Float, Float) -> Unit,
    private val onDragMove: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit
) {
    @JavascriptInterface
    fun onPetClick(): Unit { onPetClick.invoke() }

    @JavascriptInterface
    fun onDragStart(x: Float, y: Float): Unit { onDragStart.invoke(x, y) }

    @JavascriptInterface
    fun onDragMove(x: Float, y: Float): Unit { onDragMove.invoke(x, y) }

    @JavascriptInterface
    fun onDragEnd(): Unit { onDragEnd.invoke() }
}
