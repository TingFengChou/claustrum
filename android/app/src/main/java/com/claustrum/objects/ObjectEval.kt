package com.claustrum.objects

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Ground-truth box for the fixed-camera object-detector development corpus. */
internal data class ObjectEvalAnnotation(
    val category: String,
    val bounds: NormalizedObjectBounds,
) {
    init {
        require(category.isNotBlank())
    }
}

internal data class ObjectEvalManifestCase(
    val imageFileName: String,
    val label: String,
    val objects: List<ObjectEvalAnnotation>,
)

/**
 * Strict parser for `<externalFiles>/dev_object_eval/manifest.json`.
 *
 * Coordinates are normalized to the decoded image. Unknown fields are rejected so an identity
 * annotation (or a misspelled coordinate) cannot silently enter this anonymous object benchmark.
 */
internal object ObjectEvalManifest {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun parse(json: String, allowedCategories: Set<String>): List<ObjectEvalManifestCase> {
        require(allowedCategories.isNotEmpty()) { "allowedCategories must not be empty" }
        val root = JSONObject(json)
        root.requireOnlyKeys(setOf("version", "cases"), "manifest")
        require(root.getInt("version") == 1) { "manifest.version must be 1" }
        val casesJson = root.getJSONArray("cases")
        require(casesJson.length() > 0) { "manifest.cases must not be empty" }

        val seenImages = mutableSetOf<String>()
        return List(casesJson.length()) { index ->
            val item = casesJson.getJSONObject(index)
            item.requireOnlyKeys(setOf("image", "label", "objects"), "cases[$index]")
            val image = item.getString("image").trim()
            require(image.isNotEmpty()) { "cases[$index].image must not be blank" }
            require(!image.contains('/') && !image.contains('\\')) {
                "cases[$index].image must be a file name, not a path"
            }
            require(image.substringAfterLast('.', "").lowercase() in imageExtensions) {
                "cases[$index].image has an unsupported extension"
            }
            require(seenImages.add(image)) { "duplicate image in manifest: $image" }

            val label = item.getString("label").trim()
            require(label.isNotEmpty()) { "cases[$index].label must not be blank" }
            val objectsJson = item.getJSONArray("objects")
            val objects = List(objectsJson.length()) { objectIndex ->
                val obj = objectsJson.getJSONObject(objectIndex)
                val path = "cases[$index].objects[$objectIndex]"
                obj.requireOnlyKeys(
                    setOf("category", "left", "top", "right", "bottom"),
                    path,
                )
                val category = obj.getString("category").trim()
                require(category in allowedCategories) {
                    "$path.category is outside the detector allowlist: $category"
                }
                ObjectEvalAnnotation(
                    category = category,
                    bounds = NormalizedObjectBounds(
                        left = obj.finiteFloat("left", path),
                        top = obj.finiteFloat("top", path),
                        right = obj.finiteFloat("right", path),
                        bottom = obj.finiteFloat("bottom", path),
                    ),
                )
            }
            ObjectEvalManifestCase(image, label, objects)
        }
    }

    private fun JSONObject.requireOnlyKeys(allowed: Set<String>, path: String) {
        val unknown = keys().asSequence().filterNot(allowed::contains).toList()
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.joinToString()}" }
        val missing = allowed.filterNot(::has)
        require(missing.isEmpty()) { "$path is missing fields: ${missing.joinToString()}" }
    }

    private fun JSONObject.finiteFloat(name: String, path: String): Float {
        val value = getDouble(name)
        require(value.isFinite()) { "$path.$name must be finite" }
        return value.toFloat()
    }
}

/** Pure, host-testable detector metrics. It never classifies an object as litter. */
object ObjectEval {
    const val IOU_THRESHOLD = 0.5f

    internal data class CategoryCounts(
        val groundTruth: Int,
        val predicted: Int,
        val truePositive: Int,
        val minGroundTruthShortSidePx: Int?,
        val minGroundTruthAreaPx: Int?,
    ) {
        val falsePositive: Int get() = predicted - truePositive
        val falseNegative: Int get() = groundTruth - truePositive
    }

    internal data class CaseResult(
        val label: String,
        val groundTruthCount: Int,
        val predictionCount: Int,
        val truePositive: Int,
        val matchedIous: List<Float>,
        val categoryCounts: Map<String, CategoryCounts>,
        val minGroundTruthShortSidePx: Int?,
        val minGroundTruthAreaPx: Int?,
        val latencyMs: Long,
    ) {
        val falsePositive: Int get() = predictionCount - truePositive
        val falseNegative: Int get() = groundTruthCount - truePositive
        val isHardNegative: Boolean get() = groundTruthCount == 0
        val hardNegativeFailed: Boolean get() = isHardNegative && predictionCount > 0
    }

    data class CategorySummary(
        val category: String,
        val groundTruth: Int,
        val predicted: Int,
        val truePositive: Int,
        val minGroundTruthShortSidePx: Int?,
        val minGroundTruthAreaPx: Int?,
    ) {
        val falsePositive: Int get() = predicted - truePositive
        val falseNegative: Int get() = groundTruth - truePositive
        val precision: Double? get() = ratio(truePositive, predicted)
        val recall: Double? get() = ratio(truePositive, groundTruth)
    }

    data class Summary(
        val images: Int,
        val groundTruth: Int,
        val predicted: Int,
        val truePositive: Int,
        val hardNegativeImages: Int,
        val hardNegativeFailures: Int,
        val meanMatchedIou: Double?,
        val minGroundTruthShortSidePx: Int?,
        val minGroundTruthAreaPx: Int?,
        val avgLatencyMs: Long,
        val p50LatencyMs: Long,
        val p95LatencyMs: Long,
        val maxLatencyMs: Long,
        val categories: List<CategorySummary>,
    ) {
        val falsePositive: Int get() = predicted - truePositive
        val falseNegative: Int get() = groundTruth - truePositive
        val precision: Double? get() = ratio(truePositive, predicted)
        val recall: Double? get() = ratio(truePositive, groundTruth)
    }

    /**
     * Match predictions in descending confidence order to the highest-IoU unmatched box of the
     * same category. This deterministic rule makes duplicate detections false positives.
     */
    internal fun evaluate(
        label: String,
        imageWidth: Int,
        imageHeight: Int,
        groundTruth: List<ObjectEvalAnnotation>,
        predictions: List<ObjectDetectionSample>,
        latencyMs: Long,
        iouThreshold: Float = IOU_THRESHOLD,
    ): CaseResult {
        require(label.isNotBlank())
        require(imageWidth > 0 && imageHeight > 0)
        require(latencyMs >= 0L)
        require(iouThreshold in 0f..1f)

        val unmatchedGroundTruth = groundTruth.indices.toMutableSet()
        val matches = mutableListOf<Pair<Int, Float>>()
        predictions.withIndex()
            .sortedWith(compareByDescending<IndexedValue<ObjectDetectionSample>> { it.value.score }
                .thenBy { it.index })
            .forEach { (_, prediction) ->
                val match = unmatchedGroundTruth.asSequence()
                    .filter { groundTruth[it].category == prediction.category }
                    .map { it to groundTruth[it].bounds.intersectionOverUnion(prediction.bounds) }
                    .filter { (_, iou) -> iou >= iouThreshold }
                    .maxWithOrNull(compareBy<Pair<Int, Float>> { it.second }.thenBy { -it.first })
                if (match != null) {
                    unmatchedGroundTruth.remove(match.first)
                    matches += match
                }
            }

        val categories = (groundTruth.map { it.category } + predictions.map { it.category }).toSortedSet()
        val matchedByCategory = matches.groupingBy { groundTruth[it.first].category }.eachCount()
        val counts = categories.associateWith { category ->
            val categoryGroundTruth = groundTruth.filter { it.category == category }
            CategoryCounts(
                groundTruth = categoryGroundTruth.size,
                predicted = predictions.count { it.category == category },
                truePositive = matchedByCategory[category] ?: 0,
                minGroundTruthShortSidePx = categoryGroundTruth.minOfOrNull {
                    minOf(it.bounds.width * imageWidth, it.bounds.height * imageHeight).roundToInt()
                },
                minGroundTruthAreaPx = categoryGroundTruth.minOfOrNull {
                    (it.bounds.width * imageWidth * it.bounds.height * imageHeight).roundToInt()
                },
            )
        }
        val shortSides = groundTruth.map {
            minOf(it.bounds.width * imageWidth, it.bounds.height * imageHeight).roundToInt()
        }
        val areas = groundTruth.map {
            (it.bounds.width * imageWidth * it.bounds.height * imageHeight).roundToInt()
        }
        return CaseResult(
            label = label,
            groundTruthCount = groundTruth.size,
            predictionCount = predictions.size,
            truePositive = matches.size,
            matchedIous = matches.map(Pair<Int, Float>::second),
            categoryCounts = counts,
            minGroundTruthShortSidePx = shortSides.minOrNull(),
            minGroundTruthAreaPx = areas.minOrNull(),
            latencyMs = latencyMs,
        )
    }

    internal fun summarize(results: List<CaseResult>): Summary {
        val latencies = results.map(CaseResult::latencyMs).sorted()
        val categoryNames = results.flatMap { it.categoryCounts.keys }.toSortedSet()
        val categories = categoryNames.map { category ->
            val counts = results.mapNotNull { it.categoryCounts[category] }
            CategorySummary(
                category = category,
                groundTruth = counts.sumOf(CategoryCounts::groundTruth),
                predicted = counts.sumOf(CategoryCounts::predicted),
                truePositive = counts.sumOf(CategoryCounts::truePositive),
                minGroundTruthShortSidePx = counts.mapNotNull(CategoryCounts::minGroundTruthShortSidePx).minOrNull(),
                minGroundTruthAreaPx = counts.mapNotNull(CategoryCounts::minGroundTruthAreaPx).minOrNull(),
            )
        }
        val matchedIous = results.flatMap(CaseResult::matchedIous)
        return Summary(
            images = results.size,
            groundTruth = results.sumOf(CaseResult::groundTruthCount),
            predicted = results.sumOf(CaseResult::predictionCount),
            truePositive = results.sumOf(CaseResult::truePositive),
            hardNegativeImages = results.count(CaseResult::isHardNegative),
            hardNegativeFailures = results.count(CaseResult::hardNegativeFailed),
            meanMatchedIou = if (matchedIous.isEmpty()) null else matchedIous.average(),
            minGroundTruthShortSidePx = results.mapNotNull(CaseResult::minGroundTruthShortSidePx).minOrNull(),
            minGroundTruthAreaPx = results.mapNotNull(CaseResult::minGroundTruthAreaPx).minOrNull(),
            avgLatencyMs = if (latencies.isEmpty()) 0L else latencies.average().roundToInt().toLong(),
            p50LatencyMs = percentile(latencies, 0.50),
            p95LatencyMs = percentile(latencies, 0.95),
            maxLatencyMs = latencies.maxOrNull() ?: 0L,
            categories = categories,
        )
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator
}
