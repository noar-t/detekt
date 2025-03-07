package io.gitlab.arturbosch.detekt.invoke

import io.gitlab.arturbosch.detekt.internal.ClassLoaderCache
import io.gitlab.arturbosch.detekt.internal.GlobalClassLoaderCache
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.VerificationException
import org.gradle.util.GradleVersion
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException

internal interface DetektInvoker {

    fun invokeCli(
        arguments: List<String>,
        classpath: FileCollection,
        taskName: String,
        ignoreFailures: Boolean = false
    )

    companion object {

        fun create(isDryRun: Boolean = false): DetektInvoker =
            if (isDryRun) {
                DryRunInvoker()
            } else {
                DefaultCliInvoker()
            }
    }
}

internal interface DetektWorkParameters : WorkParameters {
    val arguments: ListProperty<String>
    val ignoreFailures: Property<Boolean>
    val dryRun: Property<Boolean>
    val taskName: Property<String>
    val classpath: ConfigurableFileCollection
}

internal abstract class DetektWorkAction : WorkAction<DetektWorkParameters> {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun execute() {
        if (parameters.dryRun.getOrElse(false)) {
            DryRunInvoker().invokeCli(
                parameters.arguments.get(),
                parameters.classpath,
                parameters.taskName.get(),
                parameters.ignoreFailures.getOrElse(false)
            )
            return
        }

        try {
            @Suppress("DEPRECATION")
            val runner = io.gitlab.arturbosch.detekt.cli.buildRunner(
                parameters.arguments.get().toTypedArray(),
                System.out,
                System.err
            )
            runner.execute()
        } catch (e: Exception) {
            processResult(e.message, e, parameters.ignoreFailures.getOrElse(false))
        }
    }
}

internal class DefaultCliInvoker(
    private val classLoaderCache: ClassLoaderCache = GlobalClassLoaderCache
) : DetektInvoker {

    override fun invokeCli(
        arguments: List<String>,
        classpath: FileCollection,
        taskName: String,
        ignoreFailures: Boolean
    ) {
        try {
            val loader = classLoaderCache.getOrCreate(classpath)
            val clazz = loader.loadClass("io.gitlab.arturbosch.detekt.cli.Main")
            val runner = clazz.getMethod(
                "buildRunner",
                Array<String>::class.java,
                PrintStream::class.java,
                PrintStream::class.java
            ).invoke(null, arguments.toTypedArray(), System.out, System.err)
            runner::class.java.getMethod("execute").invoke(runner)
        } catch (reflectionWrapper: InvocationTargetException) {
            processResult(reflectionWrapper.targetException.message, reflectionWrapper, ignoreFailures)
        }
    }
}

private fun isAnalysisFailure(msg: String) = "Analysis failed with" in msg && "issues" in msg

@Suppress("ThrowsCount")
private fun processResult(message: String?, reflectionWrapper: Exception, ignoreFailures: Boolean) {
    if (message != null && isAnalysisFailure(message)) {
        when {
            ignoreFailures -> return
            GradleVersion.current() >= GradleVersion.version("8.2") ->
                throw VerificationException(message, reflectionWrapper)
            GradleVersion.current() >= GradleVersion.version("7.4") -> throw VerificationException(message)
            else -> throw GradleException(message, reflectionWrapper)
        }
    } else {
        throw GradleException(message ?: "There was a problem running detekt.", reflectionWrapper)
    }
}

private class DryRunInvoker : DetektInvoker {

    override fun invokeCli(
        arguments: List<String>,
        classpath: FileCollection,
        taskName: String,
        ignoreFailures: Boolean
    ) {
        println("Invoking detekt with dry-run.")
        println("Task: $taskName")
        println("Arguments: ${arguments.joinToString(" ")}")
        println("Classpath: ${classpath.files}")
        println("Ignore failures: $ignoreFailures")
    }
}
