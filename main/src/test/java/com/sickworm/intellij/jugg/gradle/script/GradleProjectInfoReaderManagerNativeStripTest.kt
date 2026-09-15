package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfoRequestItem
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that the external build collector reproduces AGP's single-file strip contract without
 * executing the APK owner strip task or adding its native task graph to the invocation.
 */
class GradleProjectInfoReaderManagerNativeStripTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `strips native libraries with the ABI strip tool of the APK owner`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")
        val stripTool = createStripTool(project.root, exitCode = 0)

        val output = strip(project, mergeOutput, stripTool = stripTool)

        assertEquals("stripped", File(output, "arm64-v8a/libapp.so").readText())
        val invocation = project.stripLog.readText()
        assertTrue(invocation.contains("--strip-unneeded"), invocation)
        assertTrue(invocation.contains(File(mergeOutput, "lib/arm64-v8a/libapp.so").path), invocation)
    }

    @Test
    fun `packages libraries matching keepDebugSymbols as is`() {
        val project = createProject(keepDebugSymbols = listOf("*/arm64-v8a/libkeep.so"))
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libkeep.so", "with-debug-symbols")
        val stripTool = createStripTool(project.root, exitCode = 0)

        val output = strip(project, mergeOutput, stripTool = stripTool)

        assertEquals("with-debug-symbols", File(output, "arm64-v8a/libkeep.so").readText())
        assertTrue(!project.stripLog.exists(), "keepDebugSymbols matches must not run the strip tool")
    }

    @Test
    fun `packages libraries as is without a strip tool for the ABI`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")

        val output = strip(project, mergeOutput, stripTool = null)

        assertEquals("with-debug-symbols", File(output, "arm64-v8a/libapp.so").readText())
    }

    @Test
    fun `packages libraries as is when the strip tool fails`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")
        val stripTool = createStripTool(project.root, exitCode = 1)

        val output = strip(project, mergeOutput, stripTool = stripTool)

        assertEquals("with-debug-symbols", File(output, "arm64-v8a/libapp.so").readText())
        assertEquals(1, project.stripLog.readLines().size, "a failed strip command must not be retried")
    }

    @Test
    fun `reads the strip tool map keyed by the AGP Abi type`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")
        val stripTool = createStripTool(project.root, exitCode = 0)

        val output = strip(project, mergeOutput, stripTool = stripTool, keyAsAbiType = true)

        assertEquals("stripped", File(output, "arm64-v8a/libapp.so").readText())
    }

    @Test
    fun `normalizes every ABI of the merge output into the invocation directory`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "arm64")
        writeLib(project.root, "lib/armeabi-v7a/libapp.so", "armv7")
        val stripTool = createStripTool(project.root, exitCode = 0)

        val output = strip(project, mergeOutput, stripTool = stripTool)

        assertEquals(
            setOf("arm64-v8a/libapp.so", "armeabi-v7a/libapp.so"),
            output.walkTopDown().filter(File::isFile).map { it.relativeTo(output).path }.toSet(),
        )
    }

    @Test
    fun `fails the round when the request has no APK owner`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")

        val error = assertFailsWith<IllegalStateException> {
            strip(project, mergeOutput, stripTool = null, withApkOwner = false)
        }

        assertTrue(error.message!!.contains("APK owner is missing"), error.message!!)
    }

    @Test
    fun `fails the round when the APK owner strip task is missing`() {
        val project = createProject(registerStripTask = false)
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")

        val error = assertFailsWith<IllegalStateException> {
            runStrip(project, mergeOutput)
        }

        assertTrue(error.message!!.contains("stripDebugDebugSymbols"), error.message!!)
    }

    @Test
    fun `fails the round when the strip tool capability is unreadable`() {
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libapp.so", "with-debug-symbols")
        project.stripTask!!.sdkBuildService = "not-a-sdk-build-service"

        val error = assertFailsWith<IllegalStateException> {
            runStrip(project, mergeOutput)
        }

        assertTrue(error.message!!.contains("executable finder is unreadable"), error.message!!)
    }

    @Test
    fun `fails the round when a stripped library exceeds the deploy limit`() {
        Assume.assumeFalse("creating a sparse file larger than 2 GiB is not portable", isWindows)
        val project = createProject()
        val mergeOutput = writeLib(project.root, "lib/arm64-v8a/libhuge.so", "with-debug-symbols")
        RandomAccessFile(File(mergeOutput, "lib/arm64-v8a/libhuge.so"), "rw")
            .use { it.setLength(Int.MAX_VALUE + 1L) }

        val error = assertFailsWith<IllegalStateException> {
            strip(project, mergeOutput, stripTool = null)
        }

        assertTrue(error.message!!.contains("exceeding the ${Int.MAX_VALUE} bytes deploy limit"), error.message!!)
    }

    @Test
    fun `runs the collector after the selected module tasks without depending on them`() {
        val root = temporaryFolder.newFolder("collector-graph")
        val moduleMerge = File(root, "module-merge").apply { mkdirs() }
        val request = """{"invocationId":"invocation-1","items":[{"moduleName":"app",""" +
                """"moduleRootDir":"${root.path}","buildVariant":"debug",""" +
                """"taskPath":":app:mergeDebugNativeLibs","type":"Cpp"}]}"""
        val requestFile = File(root, "request.json").apply { writeText(request) }
        val rootProject = ProjectBuilder.builder().withProjectDir(root).build()
        // ProjectBuilder does not load gradle.properties, so the invocation parameters are injected
        // through extra properties, which the collector reads from project.properties as well.
        val invocationProperties = rootProject.extensions.extraProperties
        invocationProperties.set(
            GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_REQUEST, requestFile.path,
        )
        invocationProperties.set(
            GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_OUTPUT, File(root, "output").path,
        )
        invocationProperties.set(
            GradleProjectInfoReaderManager.PARAM_EXTERNAL_BUILD_INVOCATION, "invocation-1",
        )
        val appProject = ProjectBuilder.builder().withName("app").withParent(rootProject)
            .withProjectDir(root).build()
        val moduleMergeTask = appProject.tasks.create("mergeDebugNativeLibs", TestNativeMergeTask::class.java)
            .apply { outputDir = moduleMerge }
        val stripTask = appProject.tasks.create("stripDebugDebugSymbols", TestStripSymbolsTask::class.java)

        GradleProjectInfoReaderManager(rootProject, emptyList()).configureExternalBuildInfoCollector()

        val collector = rootProject.tasks.getByName(GradleProjectInfoReaderManager.COLLECT_EXTERNAL_BUILD_INFO_TASK_NAME)
        assertEquals(
            setOf(moduleMergeTask),
            collector.mustRunAfter.getDependencies(collector),
            "the collector must only be ordered after the selected module tasks",
        )
        assertTrue(
            collector.taskDependencies.getDependencies(collector).none { it == stripTask || it == moduleMergeTask },
            "the collector must not depend on the app strip or the module merge task",
        )
    }

    private class StripProject(
        val root: File,
        val rootProject: Project,
        val stripTask: TestStripSymbolsTask?,
        val stripLog: File,
    )

    private fun createProject(
        keepDebugSymbols: List<String> = emptyList(),
        registerStripTask: Boolean = true,
    ): StripProject {
        val root = temporaryFolder.newFolder("strip-project")
        val rootProject = ProjectBuilder.builder().withProjectDir(root).build()
        val appProject = ProjectBuilder.builder().withName("app").withParent(rootProject)
            .withProjectDir(File(root, "app").apply { mkdirs() }).build()
        val stripTask = if (registerStripTask) {
            appProject.tasks.create("stripDebugDebugSymbols", TestStripSymbolsTask::class.java).apply {
                this.keepDebugSymbols = TestProvider(keepDebugSymbols.toSet())
                ndkHandlerInput = Any()
                sdkBuildService = TestProvider(
                    TestSdkBuildService(TestVersionedNdkHandler(TestStripExecutableFinder(emptyMap()))),
                )
            }
        } else {
            null
        }
        return StripProject(root, rootProject, stripTask, File(root, "strip-invocation.log"))
    }

    /** Runs one selective strip round and returns the invocation-scoped stripped output directory. */
    private fun strip(
        project: StripProject,
        mergeOutput: File,
        stripTool: File?,
        keyAsAbiType: Boolean = false,
        withApkOwner: Boolean = true,
    ): File {
        val executables = if (stripTool == null) {
            emptyMap()
        } else if (keyAsAbiType) {
            mapOf(TestAbi("arm64-v8a") as Any to stripTool)
        } else {
            mapOf("arm64-v8a" as Any to stripTool)
        }
        project.stripTask!!.sdkBuildService =
            TestProvider(TestSdkBuildService(TestVersionedNdkHandler(TestStripExecutableFinder(executables))))
        return runStrip(project, mergeOutput, withApkOwner)    }

    private fun runStrip(
        project: StripProject,
        mergeOutput: File,
        withApkOwner: Boolean = true,
    ): File {
        val appRoot = File(project.root, "app")
        val request = ExternalBuildInfoRequestItem(
            moduleName = "app",
            moduleRootDir = appRoot,
            buildVariant = "debug",
            taskPath = ":app:mergeDebugNativeLibs",
            type = ExternalBuildType.Cpp,
            apkOwnerModuleRootDir = appRoot.takeIf { withApkOwner },
            apkOwnerBuildVariant = "debug".takeIf { withApkOwner },
        )
        return GradleProjectInfoReaderManager(project.rootProject, emptyList())
            .stripExternalNativeOutput(request, mergeOutput, File(project.root, "invocation-output"))
    }

    /** Writes one merge output library below its `lib/<abi>` path and returns the merge output root. */
    private fun writeLib(root: File, relativePath: String, content: String): File {
        val mergeOutput = File(root, "app/build/merged_native_libs/debug/out").apply { mkdirs() }
        File(mergeOutput, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }
        return mergeOutput
    }

    /** Writes a fake strip executable that honors `-o` and logs its invocation. */
    private fun createStripTool(root: File, exitCode: Int): File {
        return File(root, "fake-llvm-strip").apply {
            writeText(
                """
                #!/bin/bash
                echo "${'$'}@" >> "${File(root, "strip-invocation.log").path}"
                output=""
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        -o) output="${'$'}2"; shift 2 ;;
                        *) shift ;;
                    esac
                done
                printf stripped > "${'$'}output"
                exit $exitCode
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }
}

/** Fake AGP provider exposing the value the collector reads through a `get` call. */
class TestProvider<T>(private val value: T) {
    fun get(): T = value
}

/** Fake AGP strip symbols task exposing the properties read by the collector. */
open class TestStripSymbolsTask : DefaultTask() {
    lateinit var keepDebugSymbols: Any
    lateinit var ndkHandlerInput: Any
    lateinit var sdkBuildService: Any
}

/** Fake SDK components build service resolving the versioned NDK handler. */
class TestSdkBuildService(private val handler: TestVersionedNdkHandler) {
    fun versionedNdkHandler(input: Any): TestVersionedNdkHandler = handler
}

/** Fake versioned NDK handler exposing the strip executable finder provider. */
class TestVersionedNdkHandler(private val finder: TestStripExecutableFinder?) {
    fun getStripExecutableFinderProvider(): TestProvider<TestStripExecutableFinder> =
        TestProvider(finder!!)
}

/** Fake strip executable finder exposing the per-ABI tool map. */
class TestStripExecutableFinder(private val executables: Map<Any, File>) {
    fun getStripExecutables(): Map<Any, File> = executables
}

/** Mimics the AGP internal `Abi` enum used as the strip tool map key before AGP 8.11. */
class TestAbi(val tag: String)
