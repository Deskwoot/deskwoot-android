package com.deskwoot.sdk

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Properties

/** The version the SDK sends to the server is the published one: both come from gradle.properties. */
class SdkVersionTest {
    @Test
    fun `the version sent to the server is the one in gradle properties`() {
        val file = listOf(File("../gradle.properties"), File("gradle.properties")).first { it.isFile && it.readText().contains("deskwootSdkVersion") }
        val properties = Properties().apply { file.inputStream().use(::load) }
        assertEquals(properties.getProperty("deskwootSdkVersion"), Deskwoot.SDK_VERSION)
    }
}
