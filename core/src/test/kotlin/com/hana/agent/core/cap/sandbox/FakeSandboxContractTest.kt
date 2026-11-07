package com.hana.agent.core.cap.sandbox

import java.io.File
import java.nio.file.Files

class FakeSandboxContractTest : SandboxPathContract() {
    private val outside: File = Files.createTempDirectory("contract-outside").toFile()
    override fun sandbox(): SandboxPort = FakeSandbox()
    override fun outsideDir(): File = outside
}
