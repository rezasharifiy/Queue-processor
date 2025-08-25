package com.rqueue.queue


import android.util.Log
import com.example.queue.QueueProcessor
import com.example.queue.exception.MaxRetryException
import com.example.queue.exception.QueueException
import com.example.queue.model.BackoffStrategy
import com.example.queue.model.BaseQueueItem
import com.example.queue.model.QueueRunner
import com.example.queue.model.QueueState
import com.example.queue.model.RetryStrategy
import com.example.queue.repo.InMemoryQueueRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

// Mock Android's Log class to prevent crashes and capture logs
object MockLog {
    @JvmStatic fun d(tag: String, msg: String) = println("DEBUG: $tag: $msg")
    @JvmStatic fun i(tag: String, msg: String) = println("INFO: $tag: $msg")
    @JvmStatic fun w(tag: String, msg: String) = println("WARN: $tag: $msg")
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable? = null) = println("ERROR: $tag: $msg: ${tr?.message}")
}

// Replace actual Log with mock
@Suppress("unused")
val <T> T.Log: MockLog
    get() = MockLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class QueueProcessorTest {
    // Test dispatcher for controlling coroutine execution time
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // Mocks for dependencies
    @Mock
    private lateinit var mockQueueRunner: QueueRunner<TestQueueItem>
    @Mock
    private lateinit var mockBackoffStrategy: BackoffStrategy
    @Mock
    private lateinit var mockRetryStrategy: RetryStrategy

    // MockedStatic for android.util.Log
    private lateinit var mockedLog: MockedStatic<android.util.Log> // Changed type to android.util.Log

    // The QueueProcessor instance under test
    private lateinit var queueProcessor: QueueProcessor<TestQueueItem>

    // Custom QueueItem for testing
    data class TestQueueItem(
        override val id: String,
        override var state: QueueState? = QueueState.PENDING,
        override var retryCount: Int = 0,
        override val timeoutMillis: Long? = 0,
        override var payload: String = "",
        override val priority: Int = 1
    ) : BaseQueueItem<TestQueueItem>(id, state, retryCount, timeoutMillis, payload, priority)

    @Before
    fun setup() {
        // Initialize Mockito's static mock for android.util.Log using its fully qualified name.
        // This will intercept all static calls to Log.d, Log.i, Log.w, Log.e
        mockedLog = mockStatic(android.util.Log::class.java) // Explicitly use android.util.Log

        // Define behavior for Log methods to simply print to console or do nothing
        mockedLog.`when`<Any> { android.util.Log.d(anyString(), anyString()) }.thenAnswer { invocation ->
            println("MOCKED_LOG_D: ${invocation.getArgument<String>(0)}: ${invocation.getArgument<String>(1)}")
            0 // Log.d returns an int (number of bytes written)
        }
        mockedLog.`when`<Any> { android.util.Log.i(anyString(), anyString()) }.thenAnswer { invocation ->
            println("MOCKED_LOG_I: ${invocation.getArgument<String>(0)}: ${invocation.getArgument<String>(1)}")
            0
        }
        mockedLog.`when`<Any> { android.util.Log.w(anyString(), anyString()) }.thenAnswer { invocation ->
            println("MOCKED_LOG_W: ${invocation.getArgument<String>(0)}: ${invocation.getArgument<String>(1)}")
            0
        }
        mockedLog.`when`<Any> { android.util.Log.e(anyString(), anyString()) }.thenAnswer { invocation ->
            println("MOCKED_LOG_E: ${invocation.getArgument<String>(0)}: ${invocation.getArgument<String>(1)}")
            0
        }
        mockedLog.`when`<Any> { android.util.Log.e(anyString(), anyString(), any(Throwable::class.java)) }.thenAnswer { invocation ->
            val tag = invocation.getArgument<String>(0)
            val msg = invocation.getArgument<String>(1)
            val tr = invocation.getArgument<Throwable>(2)
            println("MOCKED_LOG_E: $tag: $msg: ${tr.message}")
            0
        }


        // Initialize QueueProcessor with mocks and test dispatcher
        queueProcessor = QueueProcessor(
            runner = mockQueueRunner,
            backoff = mockBackoffStrategy,
            retryStrategy = mockRetryStrategy,
            maxRetries = 3,
            coroutineContext = testDispatcher
        )

        // Mock the internal repository using reflection for testing purposes.
        val repositoryField = QueueProcessor::class.java.getDeclaredField("repository")
        repositoryField.isAccessible = true
        val inMemoryRepo = InMemoryQueueRepository<TestQueueItem>()
        repositoryField.set(queueProcessor, inMemoryRepo)

        // Reset all mocks (excluding the static Log mock if you want to verify its calls) before each test
        reset(mockQueueRunner, mockBackoffStrategy, mockRetryStrategy)

        // Default mock behavior for retry strategy
        whenever(mockRetryStrategy.strategy).thenReturn(RetryStrategy.Strategy.RETRY_WHEN_UN_SUCCESS)
    }

    @After
    fun teardown() {
        // Clear the processor after each test to ensure clean state
        testScope.runTest {
            queueProcessor.clear()
        }
        // Close the static mock for android.util.Log after each test
        mockedLog.close()
    }

    // --- Test Cases ---

    @Test
    fun `enqueue single item adds to repository and triggers processing`() = testScope.runTest {
        val item = TestQueueItem("item1")

        // Use a MutableStateFlow to signal when processing is expected to start
        val processingStarted = MutableStateFlow(false)
        val job = launch {
            queueProcessor.enqueue(item)
            processingStarted.value = true
        }

        // Wait for the processing to be triggered
        processingStarted.first { it }
        advanceTimeBy(1) // Allow coroutines to run

        // Verify item is in PENDING state initially
        val repoItem = queueProcessor.getQueueModelWithId("item1")
        assert(repoItem?.state == QueueState.PENDING)

        // Mock successful run for the item
        whenever(mockQueueRunner.run(item)).thenReturn(true)
        advanceTimeBy(1) // Allow processing to complete

        // Verify run was called and item was deleted (successful processing)
        verify(mockQueueRunner, times(1)).run(item)
        assert(queueProcessor.getQueueModelWithId("item1") == null)

        job.cancelAndJoin()
    }

    @Test
    fun `enqueue multiple items adds to repository and triggers processing`() = testScope.runTest {
        val items = listOf(TestQueueItem("item1"), TestQueueItem("item2"))

        val processingStarted = MutableStateFlow(false)
        val job = launch {
            queueProcessor.enqueue(items)
            processingStarted.value = true
        }

        processingStarted.first { it }
        advanceTimeBy(1)

        assert(queueProcessor.getQueue().size == 2)
        assert(queueProcessor.getQueueModelWithId("item1")?.state == QueueState.PENDING)
        assert(queueProcessor.getQueueModelWithId("item2")?.state == QueueState.PENDING)

        whenever(mockQueueRunner.run(any())).thenReturn(true)
        advanceTimeBy(1)

        verify(mockQueueRunner, times(1)).run(items[0]) // First item processed
        verify(mockQueueRunner, times(1)).run(items[1]) // Second item processed
        assert(queueProcessor.getQueue().isEmpty())

        job.cancelAndJoin()
    }

    @Test
    fun `process succeeds on first attempt`() = testScope.runTest {
        val item = TestQueueItem("item1")
        queueProcessor.addToQueue(item) // Manually add for direct process test

        whenever(mockQueueRunner.run(item)).thenReturn(true)

        queueProcessor.process() // Call private process through public method

        verify(mockQueueRunner, times(1)).run(item)
        assert(queueProcessor.getQueueModelWithId("item1") == null) // Item should be deleted
    }

    @Test
    fun `process retries on QueueException and succeeds`() = testScope.runTest {
        val item = TestQueueItem("item1")
        queueProcessor.addToQueue(item)

        // First attempt fails, second succeeds
        whenever(mockQueueRunner.run(item))
            .thenReturn(false) // Simulates an explicit failure leading to QueueException
            .thenReturn(true)

        // Mock backoff strategy
        whenever(mockBackoffStrategy.nextDelay(any())).thenReturn(100L)

        queueProcessor.process()
        advanceTimeBy(100) // Advance time for the delay

        verify(mockQueueRunner, times(2)).run(item)
        assert(queueProcessor.getQueueModelWithId("item1") == null)
    }

    @Test
    fun `process retries on QueueTimeoutException and succeeds`() = testScope.runTest {
        val item = TestQueueItem("item1", timeoutMillis = 50L) // Item with a timeout
        queueProcessor.addToQueue(item)

        // Mock runner to simulate timeout (return null from withTimeoutOrNull)
        whenever(mockQueueRunner.run(item))
            .thenAnswer { // First call simulates timeout
                testScope.advanceTimeBy(100) // Simulate work taking longer than timeout
                null // Return null to indicate timeout
            }
            .thenReturn(true) // Second call succeeds

        whenever(mockBackoffStrategy.nextDelay(any())).thenReturn(100L)
        whenever(mockRetryStrategy.strategy).thenReturn(RetryStrategy.Strategy.RETRY_ON_TIME_OUT)


        queueProcessor.process()
        advanceTimeBy(100) // For the delay after timeout

        verify(mockQueueRunner, times(2)).run(item)
        assert(queueProcessor.getQueueModelWithId("item1") == null)
    }

    @Test
    fun `process hits max retries with QueueException`() = testScope.runTest {
        val item = TestQueueItem("item1")
        queueProcessor.addToQueue(item)

        // All attempts fail
        whenever(mockQueueRunner.run(item)).thenReturn(false)
        whenever(mockBackoffStrategy.nextDelay(any())).thenReturn(100L)

        // Max retries is 3, so runner.run will be called 4 times (initial + 3 retries)
        queueProcessor.process()
        advanceTimeBy(300) // Advance time for all delays

        verify(mockQueueRunner, times(4)).run(item) // Initial attempt + 3 retries
        verify(mockQueueRunner).onError(eq(item), any(MaxRetryException::class.java), eq(mockRetryStrategy))
        assert(queueProcessor.getQueueModelWithId("item1") == null) // Item should be deleted
    }

    @Test
    fun `process hits max retries with QueueTimeoutException`() = testScope.runTest {
        val item = TestQueueItem("item1", timeoutMillis = 50L)
        queueProcessor.addToQueue(item)

        // All attempts timeout
        whenever(mockQueueRunner.run(item)).thenAnswer {
            testScope.advanceTimeBy(100) // Simulate work taking longer than timeout
            null
        }
        whenever(mockBackoffStrategy.nextDelay(any())).thenReturn(100L)
        whenever(mockRetryStrategy.strategy).thenReturn(RetryStrategy.Strategy.RETRY_ON_TIME_OUT)

        // Max retries is 3, so runner.run will be called 4 times
        queueProcessor.process()
        advanceTimeBy(300) // Advance time for all delays

        verify(mockQueueRunner, times(4)).run(item)
        verify(mockQueueRunner).onError(eq(item), any(MaxRetryException::class.java), eq(mockRetryStrategy))
        assert(queueProcessor.getQueueModelWithId("item1") == null)
    }

    @Test
    fun `process handles general Exception and deletes item`() = testScope.runTest {
        val item = TestQueueItem("item1")
        queueProcessor.addToQueue(item)

        // Runner throws a general exception
        whenever(mockQueueRunner.run(item)).thenThrow(RuntimeException("Simulated network error"))

        queueProcessor.process()

        verify(mockQueueRunner, times(1)).run(item)
        verify(mockQueueRunner).onError(eq(item), any(Exception::class.java), eq(mockRetryStrategy))
        assert(queueProcessor.getQueueModelWithId("item1") == null) // Item should be deleted
    }

    @Test
    fun `process does not retry if NO_RETRY strategy is set`() = testScope.runTest {
        val item = TestQueueItem("item1")
        queueProcessor.addToQueue(item)

        whenever(mockQueueRunner.run(item)).thenReturn(false)
        whenever(mockRetryStrategy.strategy).thenReturn(RetryStrategy.Strategy.NO_RETRY)

        queueProcessor.process()

        verify(mockQueueRunner, times(1)).run(item) // Should only run once
        verify(mockQueueRunner).onError(eq(item), any(QueueException::class.java), eq(mockRetryStrategy))
        assert(queueProcessor.getQueueModelWithId("item1") == null)
    }

    @Test
    fun `clear empties the repository and stops processing`() = testScope.runTest {
        val item1 = TestQueueItem("item1")
        val item2 = TestQueueItem("item2")
        queueProcessor.addToQueue(item1)
        queueProcessor.addToQueue(item2)

        assert(queueProcessor.getQueue().size == 2)

        queueProcessor.clear()

        assert(queueProcessor.getQueue().isEmpty())
        assert(!queueProcessor.isProcessing())
    }

    @Test
    fun `update modifies an existing item in the repository`() = testScope.runTest {
        val item = TestQueueItem("item1", payload = "original")
        queueProcessor.addToQueue(item)

        val updatedItem = item.copy(payload = "updated")
        queueProcessor.update(updatedItem)

        val retrievedItem = queueProcessor.getQueueModelWithId("item1")
        assert(retrievedItem?.payload == "updated")
    }

    @Test
    fun `removeProcess by state removes items with that state`() = testScope.runTest {
        val item1 = TestQueueItem("item1", state = QueueState.PENDING)
        val item2 = TestQueueItem("item2", state = QueueState.FAILED)
        queueProcessor.addToQueue(item1)
        queueProcessor.addToQueue(item2)

        assert(queueProcessor.getQueue().size == 2)
        assert(queueProcessor.getQueue(QueueState.PENDING).size == 1)
        assert(queueProcessor.getQueue(QueueState.FAILED).size == 1)

        queueProcessor.removeProcess(QueueState.FAILED)

        assert(queueProcessor.getQueue().size == 1)
        assert(queueProcessor.getQueue(QueueState.PENDING).size == 1)
        assert(queueProcessor.getQueue(QueueState.FAILED).isEmpty())
    }

    @Test
    fun `removeProcess by id removes specific item`() = testScope.runTest {
        val item1 = TestQueueItem("item1", state = QueueState.PENDING)
        val item2 = TestQueueItem("item2", state = QueueState.PENDING)
        queueProcessor.addToQueue(item1)
        queueProcessor.addToQueue(item2)

        assert(queueProcessor.getQueue().size == 2)

        queueProcessor.removeProcess("item1")

        assert(queueProcessor.getQueue().size == 1)
        assert(queueProcessor.getQueueModelWithId("item1") == null)
        assert(queueProcessor.getQueueModelWithId("item2") != null)
    }

    @Test
    fun `getQueueModelWithId returns correct item`() = testScope.runTest {
        val item = TestQueueItem("item1")
        queueProcessor.addToQueue(item)

        val retrievedItem = queueProcessor.getQueueModelWithId("item1")
        assert(retrievedItem == item)
    }

    @Test
    fun `updatePayload modifies payload of existing item`() = testScope.runTest {
        val item = TestQueueItem("item1", payload = "old")
        queueProcessor.addToQueue(item)

        queueProcessor.updatePayload("item1", "new")

        val retrievedItem = queueProcessor.getQueueModelWithId("item1")
        assert(retrievedItem?.payload == "new")
    }

    @Test
    fun `getQueueModelWithPayload returns correct item`() = testScope.runTest {
        val item = TestQueueItem("item1", payload = "testPayload")
        queueProcessor.addToQueue(item)

        val retrievedItem = queueProcessor.getQueueModelWithPayload("testPayload")
        assert(retrievedItem == item)
    }

    @Test
    fun `getQueue returns all items if state is null`() = testScope.runTest {
        val item1 = TestQueueItem("item1", state = QueueState.PENDING)
        val item2 = TestQueueItem("item2", state = QueueState.FAILED)
        queueProcessor.addToQueue(item1)
        queueProcessor.addToQueue(item2)

        val allItems = queueProcessor.getQueue(null)
        assert(allItems.size == 2)
        assert(allItems.contains(item1))
        assert(allItems.contains(item2))
    }

    @Test
    fun `getQueue returns items filtered by state`() = testScope.runTest {
        val item1 = TestQueueItem("item1", state = QueueState.PENDING)
        val item2 = TestQueueItem("item2", state = QueueState.FAILED)
        queueProcessor.addToQueue(item1)
        queueProcessor.addToQueue(item2)

        val pendingItems = queueProcessor.getQueue(QueueState.PENDING)
        assert(pendingItems.size == 1)
        assert(pendingItems.contains(item1))

        val failedItems = queueProcessor.getQueue(QueueState.FAILED)
        assert(failedItems.size == 1)
        assert(failedItems.contains(item2))
    }
}
