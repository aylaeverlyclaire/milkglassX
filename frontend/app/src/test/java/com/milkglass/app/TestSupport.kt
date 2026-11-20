package com.milkglass.app

import com.milkglass.app.core.data.AppData
import com.milkglass.app.core.data.Translator
import java.io.File

object TestSupport {

    private val dataDir = File("src/main/assets/data")

    private val testDataDir = File("src/test/resources/data")
    fun read(path: String): String = File(testDataDir, path).takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: File(dataDir, path).readText(Charsets.UTF_8)
    fun resource(path: String): String =
        checkNotNull(TestSupport::class.java.classLoader.getResourceAsStream(path)) { "resource $path" }
            .bufferedReader().use { it.readText() }
    val data: AppData by lazy { AppData.load { read(it) } }
    val translator: Translator by lazy { Translator(data.locale) }
}
