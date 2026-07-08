package com.example.ncmdumplsposed.nativebridge

import java.io.IOException

internal object NativeNcmdump {
    init {
        System.loadLibrary("ncmdump_android")
    }

    @Throws(IOException::class)
    external fun decryptToSibling(inputPath: String): String
}
