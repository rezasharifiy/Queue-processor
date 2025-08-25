## 🚀 QueueProcessor

QueueProcessor is a flexible Kotlin library for asynchronous task processing with built-in retries, backoff strategies, and timeouts.
It uses Kotlin Coroutines for efficient, reliable, and responsive background operations.

✨ Features

QueueProcessor offers:

✅ Asynchronous task handling with Kotlin Coroutines

🔄 Configurable retries and backoff delays for transient failures

⏱️ Task-specific or global timeouts

📊 Queue state tracking (PENDING, SENDING, FAILED, TIMED_OUT)

🔌 Pluggable interfaces for QueueRunner, RetryStrategy, and BackoffStrategy

🗄️ In-memory repository for temporary storage

🏗️ How It Works

The QueueProcessor continuously processes PENDING items:

Enqueue → Add items to the queue

Loop → A background coroutine fetches and processes items

Execute → QueueRunner.run() performs the task

Timeout → Tasks respect defined timeouts

Retries → Failures (false return, QueueException, QueueTimeoutException) increment retryCount.

BackoffStrategy determines delays.

After maxRetries, a MaxRetryException is thrown, and QueueRunner.onError() is called.

Success → On true return, the item is removed

Error Handling → All exceptions trigger QueueRunner.onError()

📖 Usage
### 1. Define Your Queue Item

Your items must implement the QueueItem interface.
For convenience, extend BaseQueueItem:
```
data class EmailQueueItem(
    override val id: String,
    override var state: QueueState? = QueueState.PENDING,
    override var retryCount: Int = 0,
    override val timeoutMillis: Long? = 5000, // 5s timeout for sending email
    override var payload: String, // The actual email content or ID
    override val priority: Int = 1
) : BaseQueueItem<EmailQueueItem>(id, state, retryCount, timeoutMillis, payload, priority)
```


### 2. Implement Your Queue Runner

Define the actual task logic:
```
class EmailSenderRunner : QueueRunner<EmailQueueItem> {
    override suspend fun run(model: EmailQueueItem): Boolean {
        println("Attempting to send email for ID: ${model.id} with payload: ${model.payload}")

        // Simulate sending email (network call, etc.)
        return if (System.currentTimeMillis() % 2 == 0L) {
            println("✅ Successfully sent email for ID: ${model.id}")
            true
        } else {
            println("❌ Failed to send email for ID: ${model.id}. Will retry.")
            false // Retry
        }
    }

    override suspend fun onError(item: EmailQueueItem, exception: Exception, retryStrategy: RetryStrategy) {
        println("⚠️ ERROR for email ID: ${item.id}. Exception: ${exception.message}. Retry Strategy: ${retryStrategy.strategy}")
    }
}
```
### 3. Configure Strategies (Optional)

Backoff Strategy
```
class ExponentialBackoffStrategy(private val initialDelayMillis: Long = 1000) : BackoffStrategy {
    override fun nextDelay(retryCount: Int): Long =
        initialDelayMillis * (1 shl retryCount) // 1s, 2s, 4s, 8s...
}
```


Retry Strategy
```
class OnlyRetryOnTimeoutStrategy : RetryStrategy {
    override val strategy: RetryStrategy.Strategy = RetryStrategy.Strategy.RETRY_ON_TIME_OUT
}
```


Fixed Timeout (Global)
```
val defaultFixedTimeout = FixedTimeout(timeoutMillis = 10_000) // 10 seconds
```

###  4. Initialize and Use QueueProcessor
 ```  
@OptIn(DelicateCoroutinesApi::class)
fun main() = runBlocking {
    val emailRunner = EmailSenderRunner()
    val backoffStrategy = ExponentialBackoffStrategy()
    val defaultTimeout = FixedTimeout(timeoutMillis = 7000) // Default 7s

    val processor = QueueProcessor(
        runner = emailRunner,
        backoff = backoffStrategy,
        defaultTimeout = defaultTimeout,
        retryStrategy = DefaultRetryStrategy(),
        maxRetries = 5,
        coroutineContext = newSingleThreadContext("QueueProcessorThread")
    )

    // Enqueue some items
    processor.enqueue(EmailQueueItem(
        id = UUID.randomUUID().toString(),
        payload = "Welcome email to new user"
    ))
    processor.enqueue(EmailQueueItem(
        id = UUID.randomUUID().toString(),
        payload = "Order confirmation for ABC-123",
        timeoutMillis = 10000 // Override default with 10s
    ))
    processor.enqueue(EmailQueueItem(
        id = UUID.randomUUID().toString(),
        payload = "Shipping notification for XYZ-789"
    ))

    println("📬 Items enqueued. Processing will start automatically.")

    delay(20_000L) // Wait for processing (simulation)
    println("🏁 Processing finished or time limit reached.")
    processor.clear()
}
```
### 📦 Installation

Add the dependency to build.gradle.kts:
```
dependencies {
    // Core Coroutines Library
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}
```

Ensure your plugins block includes:
```
plugins {
    kotlin("jvm") // or kotlin("android") for Android
}
```

## 🤝 Contributing

Contributions are welcome! 🎉
If you have ideas for improvements, bug fixes, or new features, please open an issue or submit a pull request.

