###🚀 QueueProcessor

QueueProcessor is a flexible Kotlin library for asynchronous task processing with built-in retries, backoff strategies, and timeouts. It uses Kotlin Coroutines for efficient, reliable, and responsive background operations.✨ FeaturesQueueProcessor offers:Asynchronous task handling with Kotlin Coroutines.Configurable retries and backoff delays for transient failures.Task-specific or global timeouts.Queue state tracking (PENDING, SENDING, FAILED, TIMED_OUT).Pluggable interfaces for QueueRunner, RetryStrategy, and BackoffStrategy.An in-memory repository for temporary storage.🏗️ How It WorksThe QueueProcessor continuously processes PENDING items.Enqueue: Add items to the queue.Loop: A background coroutine fetches and processes PENDING items.Execute: QueueRunner.run() performs the task.Timeout: Tasks respect defined timeouts.Retries: Failures (run returns false, QueueException, QueueTimeoutException) increment retryCount, with BackoffStrategy determining delays. After maxRetries, MaxRetryException is thrown, and QueueRunner.onError() is called.Success: On run returning true, the item is removed.Error Handling: All exceptions trigger QueueRunner.onError().📖 Usage1. Define Your Queue ItemYour items must implement the QueueItem interface. You can extend BaseQueueItem for convenience:// Example: EmailQueueItem.kt
data class EmailQueueItem(
    override val id: String,
    override var state: QueueState? = QueueState.PENDING,
    override var retryCount: Int = 0,
    override val timeoutMillis: Long? = 5000, // 5 seconds timeout for sending email
    override var payload: String, // Contains the actual email content or ID
    override val priority: Int = 1
) : BaseQueueItem<EmailQueueItem>(id, state, retryCount, timeoutMillis, payload, priority)
2. Implement Your Queue RunnerThis is where your actual task logic resides.// Example: EmailSenderRunner.kt
import com.rqueue.queueprocessor.model.QueueItem
import com.rqueue.queueprocessor.model.QueueRunner
import com.rqueue.queueprocessor.model.RetryStrategy
import java.lang.Exception

class EmailSenderRunner : QueueRunner<EmailQueueItem> {
    override suspend fun run(model: EmailQueueItem): Boolean {
        println("Attempting to send email for ID: ${model.id} with payload: ${model.payload}")
        // Simulate sending email. This could be an actual network call.
        // For demonstration, let's randomly succeed or fail.
        return if (System.currentTimeMillis() % 2 == 0L) {
            println("Successfully sent email for ID: ${model.id}")
            true
        } else {
            println("Failed to send email for ID: ${model.id}. Will retry.")
            false // Indicate failure for retry
        }
    }

    override suspend fun onError(item: EmailQueueItem, exception: Exception, retryStrategy: RetryStrategy) {
        println("ERROR for email ID: ${item.id}. Exception: ${exception.message}. Retry Strategy: ${retryStrategy.strategy}")
        // Here you might log the error to a monitoring system,
        // or move the item to a dead-letter queue if it's a MaxRetryException.
    }
}
3. Configure Strategies (Optional, but Recommended)Backoff Strategy// Example: ExponentialBackoffStrategy.kt
import com.rqueue.queueprocessor.model.BackoffStrategy

class ExponentialBackoffStrategy(private val initialDelayMillis: Long = 1000) : BackoffStrategy {
    override fun nextDelay(retryCount: Int): Long {
        return initialDelayMillis * (1 shl retryCount) // 1s, 2s, 4s, 8s...
    }
}
Retry Strategy// Example: OnlyRetryOnTimeoutStrategy.kt
import com.rqueue.queueprocessor.model.RetryStrategy

class OnlyRetryOnTimeoutStrategy : RetryStrategy {
    override val strategy: RetryStrategy.Strategy = RetryStrategy.Strategy.RETRY_ON_TIME_OUT
}
Fixed Timeout (Global)If you want a default timeout for all items that don't specify their own.// Example: FixedTimeout.kt
import com.rqueue.queueprocessor.model.FixedTimeout

val defaultFixedTimeout = FixedTimeout(timeoutMillis = 10_000) // 10 seconds
4. Initialize and Use QueueProcessorimport com.rqueue.queueprocessor.QueueProcessor
import com.rqueue.queueprocessor.model.DefaultRetryStrategy
import com.rqueue.queueprocessor.model.FixedTimeout
import com.rqueue.queueprocessor.EmailQueueItem // Added import
import com.rqueue.queueprocessor.EmailSenderRunner // Added import
import com.rqueue.queueprocessor.ExponentialBackoffStrategy // Added import
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay // Added import
import java.util.UUID

@OptIn(DelicateCoroutinesApi::class)
fun main() = runBlocking {
    val emailRunner = EmailSenderRunner()
    val backoffStrategy = ExponentialBackoffStrategy()
    val defaultTimeout = FixedTimeout(timeoutMillis = 7000) // Default 7 seconds

    // Create a QueueProcessor instance
    val processor = QueueProcessor(
        runner = emailRunner,
        backoff = backoffStrategy,
        defaultTimeout = defaultTimeout,
        retryStrategy = DefaultRetryStrategy(), // Or your custom strategy
        maxRetries = 5, // Max 5 retries for each item
        coroutineContext = newSingleThreadContext("QueueProcessorThread") // Dedicated thread context
    )

    // Enqueue some items
    processor.enqueue(EmailQueueItem(
        id = UUID.randomUUID().toString(),
        payload = "Welcome email to new user"
    ))
    processor.enqueue(EmailQueueItem(
        id = UUID.randomUUID().toString(),
        payload = "Order confirmation for ABC-123",
        timeoutMillis = 10000 // Override default with 10 seconds for this specific item
    ))
    processor.enqueue(EmailQueueItem(
        id = UUID.randomUUID().toString(),
        payload = "Shipping notification for XYZ-789"
    ))

    println("Items enqueued. Processing will start automatically.")

    // Give some time for processing to occur (in a real app, this would be managed by your app lifecycle)
    // For demonstration, we'll wait a bit.
    kotlinx.coroutines.delay(20000L)

    println("Processing finished or time limit reached.")
    processor.clear() // Clear the queue and stop processing
}
📦 InstallationAdd the following dependencies to your build.gradle.kts file:// build.gradle.kts

dependencies {
    // Core Coroutines Library
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0") // Use the latest stable version

    // (Your existing project dependencies like AndroidX, Material, etc.)
}
Make sure your plugins block includes kotlin("jvm") (or kotlin("android") if it's an Android project).🤝 ContributingContributions are welcome! If you have ideas for improvements, bug fixes, or new features, please open an issue or submit a pull request.📜 LicenseThis project is licensed under the MIT License.
