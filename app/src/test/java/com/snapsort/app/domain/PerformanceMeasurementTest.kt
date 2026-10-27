package com.snapsort.app.domain

import java.io.File
import java.lang.reflect.Method
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reproducible host-JVM metadata microbenchmark, not an Android/device benchmark.
 * Timing is recorded as evidence, never used as a pass/fail threshold.
 */
class PerformanceMeasurementTest {
    private val now = 1_800_000_000_000L
    private val warmups = 5
    private val repetitions = 20

    @Test fun measureOneAndTenThousandMetadataRecords() {
        val reports = listOf(1_000, 10_000).map(::benchmark)
        val output = File("build/reports/benchmarks/metadata-performance.json")
        output.parentFile?.mkdirs()
        output.writeText(buildString {
            append("{\n")
            append("  \"kind\": \"host-jvm-synthetic-metadata\",\n")
            append("  \"measured_at\": ${json(Instant.now().toString())},\n")
            append("  \"java\": ${json(System.getProperty("java.runtime.version") ?: "unknown")},\n")
            append("  \"os\": ${json((System.getProperty("os.name") ?: "unknown") + " " + (System.getProperty("os.arch") ?: "unknown"))},\n")
            append("  \"available_processors\": ${Runtime.getRuntime().availableProcessors()},\n")
            append("  \"warmups\": $warmups,\n  \"samples_per_operation\": $repetitions,\n")
            append("  \"limitations\": \"No Android UI, gallery I/O, image decode, OCR, battery or device-memory measurement. Heap and allocation values are host JVM estimates.\",\n")
            append("  \"libraries\": [\n${reports.joinToString(",\n")}\n  ]\n}\n")
        })
        println("SnapSort synthetic metadata benchmark: ${output.absolutePath}")
        reports.forEach(::println)
    }

    private fun benchmark(count: Int): String {
        gcForEstimate()
        val heapBefore = heapUsed()
        val items = fixture(count)
        val selected = items.filterIndexed { index, _ -> index % 10 == 1 }.mapTo(hashSetOf()) { it.uri }
        val summary = LibrarySummary(items)
        // The comparison may reuse only library-derived data. Per-control query/filter work remains.
        assertEquals(CleanupRules.selectDuplicatesToRemove(items), summary.duplicateExtras)
        assertEquals(
            CleanupRules.filter(items, ReviewCollection.DUPLICATES, nowMillis = now).map { it.uri },
            CleanupRules.filter(summary.duplicateMembers.filterNot { it.keep }, ReviewCollection.ALL, nowMillis = now).map { it.uri },
        )
        assertEquals(uncachedDerive(items, selected), cachedDerive(items, selected, summary))
        gcForEstimate()
        val retainedHeapEstimate = (heapUsed() - heapBefore).coerceAtLeast(0L)
        val operations = listOf(
            measure("browse_by_date") { CleanupRules.filter(items, ReviewCollection.ALL, nowMillis = now).size.toLong() },
            measure("search_ocr_two_words") {
                CleanupRules.filter(items, ReviewCollection.ALL, query = "study theorem", nowMillis = now).size.toLong()
            },
            measure("older_90_days") {
                CleanupRules.filter(items, ReviewCollection.OLDER, olderThanDays = 90, nowMillis = now).size.toLong()
            },
            measure("large_by_bytes") { CleanupRules.filter(items, ReviewCollection.LARGE, nowMillis = now).size.toLong() },
            measure("exact_duplicate_view") {
                CleanupRules.filter(items, ReviewCollection.DUPLICATES, nowMillis = now).size.toLong()
            },
            measure("select_duplicate_extras") { CleanupRules.selectDuplicatesToRemove(items).size.toLong() },
            measure("selected_byte_estimate") { CleanupRules.selectedBytes(items, selected) },
            measure("duplicate_summary_rebuild") { LibrarySummary(items).duplicateExtras.size.toLong() },
            measure("derive_duplicate_view_uncached") { uncachedDerive(items, selected) },
            measure("derive_duplicate_view_reused_summary") { cachedDerive(items, selected, summary) },
        )
        return """    {
      "items": $count,
      "fixture": "30 percent in exact-copy groups of three, 70 percent unique; deterministic dates, OCR text, categories and Keep flags",
      "approximate_retained_fixture_and_summary_heap_bytes": $retainedHeapEstimate,
      "operations": [
${operations.joinToString(",\n")}
      ]
    }"""
    }

    private fun fixture(count: Int): List<Screenshot> = List(count) { index ->
        val duplicate = index % 10 < 3
        val identity = if (duplicate) "duplicate-${index / 10}" else "unique-$index"
        val group = if (duplicate) index / 10 else index
        Screenshot(
            uri = "content://media/external_primary/images/media/${index + 1}",
            name = "Screenshot_${1_700_000_000 + index}.png",
            dateMillis = now - ((index * 37L) % 730) * 86_400_000L,
            sizeBytes = 100_000L + (group % 120) * 50_000L,
            category = Category.entries[index % Category.entries.size],
            text = "Screenshot $index. " + when (index % 6) {
                0 -> "Study lecture notes theorem proof for the next examination. ".repeat(12)
                1 -> "Receipt invoice amount total tax paid by card at the grocery store. ".repeat(12)
                2 -> "Shopping cart product shipping address and delivery date. ".repeat(12)
                3 -> "Ticket reservation boarding gate and seat details for travel. ".repeat(12)
                4 -> "Chat conversation meeting tomorrow and messages from a friend. ".repeat(12)
                else -> "A saved screenshot with useful information to review later. ".repeat(12)
            },
            keep = index % 17 == 0,
            hash = identity,
            indexed = true,
        )
    }

    private fun uncachedDerive(items: List<Screenshot>, selected: Set<String>): Long {
        val available = items.mapTo(hashSetOf()) { it.uri }
        val validSelection = selected.intersect(available)
        val selectedItems = items.filter { it.uri in validSelection }
        val duplicateCount = CleanupRules.selectDuplicatesToRemove(items).size
        val groupSizes = CleanupRules.duplicateGroups(items, includeKept = true)
            .associate { group -> (group.first().hash to group.first().sizeBytes) to group.size }
        val visible = CleanupRules.filter(items, ReviewCollection.DUPLICATES, nowMillis = now)
        return selectedItems.size + CleanupRules.selectedBytes(items, validSelection) +
            items.sumOf { it.sizeBytes } + duplicateCount + groupSizes.size + visible.size
    }

    private fun cachedDerive(items: List<Screenshot>, selected: Set<String>, summary: LibrarySummary): Long {
        val validSelection = selected.intersect(summary.existingUris)
        val selectedItems = items.filter { it.uri in validSelection }
        val visible = CleanupRules.filter(summary.duplicateMembers.filterNot { it.keep }, ReviewCollection.ALL, nowMillis = now)
        return selectedItems.size + CleanupRules.selectedBytes(items, validSelection) +
            summary.totalBytes + summary.duplicateExtras.size + summary.duplicateGroupSizes.size + visible.size
    }

    private fun measure(name: String, action: () -> Long): String {
        repeat(warmups) { sink = action() }
        val times = LongArray(repetitions)
        val allocations = LongArray(repetitions) { -1L }
        val allocationCounter = ThreadAllocations.create()
        repeat(repetitions) { index ->
            val bytesBefore = allocationCounter?.bytes() ?: -1L
            val start = System.nanoTime()
            sink = action()
            times[index] = System.nanoTime() - start
            val bytesAfter = allocationCounter?.bytes() ?: -1L
            if (bytesBefore >= 0 && bytesAfter >= bytesBefore) allocations[index] = bytesAfter - bytesBefore
        }
        times.sort()
        val median = times[times.size / 2] / 1_000_000.0
        val p95 = times[(ceil(times.size * .95).toInt() - 1).coerceIn(times.indices)] / 1_000_000.0
        val allocationMean = allocations.filter { it >= 0 }.takeIf { it.isNotEmpty() }
            ?.average()?.roundToLong()?.toString() ?: "null"
        return """        {"operation": ${json(name)}, "median_ms": $median, "p95_ms": $p95, "mean_thread_allocated_bytes": $allocationMean, "result_checksum": $sink}"""
    }

    private fun heapUsed() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private fun gcForEstimate() { System.gc(); System.runFinalization(); System.gc() }
    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // These optional host-JVM APIs are absent from the Android compile classpath. Keep all
    // access reflective; other JDKs still report wall times and a null allocation estimate.
    private class ThreadAllocations(private val bean: Any, private val getBytes: Method, private val threadId: Long) {
        fun bytes(): Long = runCatching { (getBytes.invoke(bean, threadId) as Number).toLong() }.getOrDefault(-1L)

        companion object {
            fun create(): ThreadAllocations? = runCatching {
                val factory = Class.forName("java.lang.management.ManagementFactory")
                val bean = factory.getMethod("getThreadMXBean").invoke(null) ?: return@runCatching null
                val api = Class.forName("com.sun.management.ThreadMXBean")
                if (!api.isInstance(bean) || api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) return@runCatching null
                if (api.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true) {
                    api.getMethod("setThreadAllocatedMemoryEnabled", java.lang.Boolean.TYPE).invoke(bean, true)
                }
                ThreadAllocations(bean, api.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE), Thread.currentThread().id)
            }.getOrNull()
        }
    }

    companion object { @Volatile private var sink: Long = 0 }
}
