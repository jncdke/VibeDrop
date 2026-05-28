package com.vibedrop.mobile.nativeapp

import java.io.File

internal object FixtureFiles {
    private val repositoryRoot: File by lazy {
        val workingDirectory = System.getProperty("user.dir")
            ?: error("Missing user.dir system property")
        generateSequence(File(workingDirectory).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "docs/protocol-v1-fixtures").isDirectory }
            ?: error("Cannot locate docs/protocol-v1-fixtures from ${System.getProperty("user.dir")}")
    }

    fun protocolMessage(name: String): String {
        return File(repositoryRoot, "docs/protocol-v1-fixtures/messages/$name").readText()
    }
}
