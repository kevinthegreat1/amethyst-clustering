package solvers

import Vec2
import geode.BlockType
import geode.GeodeProjection
import solution.Solution
import solution.SolutionGroup
import solution.Solver
import solution.StickyBlockType
import java.util.*

class ThemorlockSolver : Solver {
    override fun name(): String = "Themorlock Solver"

    // --- Configuration Constants ---
    private val MIN_ISLAND_SIZE = 4
    private val MAX_ISLAND_SIZE = 12
    private val MAX_SHAPES_PER_TARGET = 1000
    private val TIMEOUT_MS = 10_000L
    private val ISLAND_COST = 1.0

    // --- Internal State ---
    private lateinit var gridBounds: GridBounds
    private lateinit var grid: Array<BlockType>
    private lateinit var possibleShapes: Map<Int, List<Shape>>
    private lateinit var sortedTargetIndices: List<Int>
    private lateinit var targets: List<Int> // List of flat indices for CRYSTAL
    private var bestSolutionGroups: List<Island>? = null
    private var maxScore: Double = Double.NEGATIVE_INFINITY
    private var startTime: Long = 0

    // Directions: Right, Left, Down, Up (Mapped to flat index deltas later)
    private val DIRECTION_OFFSETS = listOf(Vec2(1, 0), Vec2(-1, 0), Vec2(0, 1), Vec2(0, -1))

    override fun solve(proj: GeodeProjection): Solution {
        startTime = System.currentTimeMillis()
        bestSolutionGroups = null
        maxScore = Double.NEGATIVE_INFINITY

        // 1. Initialize Grid System (Mapping Vec2 <-> Flat Index)
        gridBounds = GridBounds(proj.xRange, proj.yRange)
        val totalCells = gridBounds.width * gridBounds.height
        grid = Array(totalCells) { i ->
            proj[gridBounds.toVec(i)]
        }

        // 2. Identify Targets (Crystals)
        targets = grid.indices.filter { grid[it] == BlockType.CRYSTAL }

        if (targets.isEmpty()) {
            return Solution(proj, mutableListOf())
        }

        // 3. Precompute Valid Shapes
        possibleShapes = precomputeShapes(proj)

        // 4. Sort Targets by Scarcity (Fewest shapes first)
        sortedTargetIndices = targets.indices.sortedBy { targetIdx ->
            val flatIndex = targets[targetIdx]
            possibleShapes[flatIndex]?.size ?: 0
        }

        // 5. Backtracking
        backtrack(
            sortedIdx = 0,
            currentIslands = mutableListOf(),
            slimeMask = BitSet(totalCells),
            honeyMask = BitSet(totalCells),
            lShapeStemMask = BitSet(totalCells),
            currentOnes = 0,
            remainingPossibleTargets = targets.size,
            currentIslandsCount = 0
        )

        // 6. Build Result
        val resultGroups = bestSolutionGroups?.map { island ->
            val group = SolutionGroup(
                blockLocations = island.cells.map { gridBounds.toVec(it) }.toMutableSet(),
                blockType = if (island.material == Material.SLIME) StickyBlockType.SLIME else StickyBlockType.HONEY
            )
            // Implicitly set flying machine details if needed, currently just blocks
            group
        }?.toMutableList() ?: mutableListOf()

        return Solution(proj, resultGroups)
    }

    // --- Shape Generation ---

    private fun precomputeShapes(proj: GeodeProjection): Map<Int, List<Shape>> {
        val shapesByTarget = mutableMapOf<Int, MutableList<Shape>>()
        val globalSeenShapes = mutableSetOf<Set<Int>>() // Store as Sets of Flat Indices

        for (targetFlatIdx in targets) {
            shapesByTarget.putIfAbsent(targetFlatIdx, mutableListOf())

            val queue = ArrayDeque<Set<Int>>()
            val seenLocal = mutableSetOf<Set<Int>>()

            val startSet = setOf(targetFlatIdx)
            queue.add(startSet)
            seenLocal.add(startSet)

            var shapesFound = 0

            // Using a simple BFS to expand shapes
            while (!queue.isEmpty() && shapesFound < MAX_SHAPES_PER_TARGET) {
                val currentCells = queue.poll()

                // Try expanding to neighbors
                if (currentCells.size < MAX_ISLAND_SIZE) {
                    val neighbors = getNeighbors(currentCells)

                    // Prioritize adding crystals (Heuristic)
                    val sortedNeighbors = neighbors.sortedByDescending { if (grid[it] == BlockType.CRYSTAL) 1 else 0 }

                    for (n in sortedNeighbors) {
                        if (n in currentCells) continue

                        val newShapeCells = currentCells + n
                        if (seenLocal.add(newShapeCells)) {
                            queue.add(newShapeCells)
                        }
                    }
                }

                // Process current shape if valid size
                if (currentCells.size in MIN_ISLAND_SIZE..MAX_ISLAND_SIZE) {
                    // Check L-Shape geometry
                    val lShape = findLShapeCells(currentCells) ?: continue

                    // Deduplicate globally
                    if (!globalSeenShapes.add(currentCells)) continue

                    val shape = createShape(currentCells, lShape)

                    // Register this shape for every target it covers
                    for (cell in currentCells) {
                        if (grid[cell] == BlockType.CRYSTAL) {
                            shapesByTarget.computeIfAbsent(cell) { mutableListOf() }.add(shape)
                        }
                    }
                    shapesFound++
                }
            }
        }

        // Sort shapes by efficiency (most crystals covered first)
        shapesByTarget.values.forEach { list ->
            list.sortWith(Comparator.comparingInt<Shape> { it.onesCovered }.reversed())
        }

        return shapesByTarget
    }

    private fun getNeighbors(cells: Set<Int>): Set<Int> {
        val neighbors = mutableSetOf<Int>()
        for (cell in cells) {
            val vec = gridBounds.toVec(cell)
            for (offset in DIRECTION_OFFSETS) {
                val nVec = Vec2(vec.x + offset.x, vec.y + offset.y)
                if (gridBounds.isInBounds(nVec)) {
                    val nIdx = gridBounds.toFlat(nVec)
                    // Can expand into AIR or CRYSTAL, not BUD
                    if (grid[nIdx] != BlockType.BUD) {
                        neighbors.add(nIdx)
                    }
                }
            }
        }
        return neighbors
    }

    /**
     * Finds valid L-Shape configuration (3 Stem + 1 Stopper).
     * Returns the geometry details or null if no valid L-shape exists in the blob.
     */
    private fun findLShapeCells(cells: Set<Int>): LShape? {
        val cellsSet = cells // Optimization: assuming input is HashSet or small enough

        for (key in cells) {
            val keyVec = gridBounds.toVec(key)

            // Iterate potential stems centered at 'key'
            for (dir in DIRECTION_OFFSETS) {
                val prevVec = Vec2(keyVec.x - dir.x, keyVec.y - dir.y)
                val nextVec = Vec2(keyVec.x + dir.x, keyVec.y + dir.y)

                if (!gridBounds.isInBounds(prevVec) || !gridBounds.isInBounds(nextVec)) continue

                val prevKey = gridBounds.toFlat(prevVec)
                val nextKey = gridBounds.toFlat(nextVec)

                if (cellsSet.contains(prevKey) && cellsSet.contains(nextKey)) {
                    // We found a 3-line: prev - key - next. Now look for the stopper perpendicular to this line.

                    val stemCells = setOf(prevKey, key, nextKey)

                    for (perpDir in DIRECTION_OFFSETS) {
                        // Skip parallel directions
                        if (perpDir == dir || (perpDir.x == -dir.x && perpDir.y == -dir.y)) continue

                        // Check corner at 'prev' end
                        val corner1Vec = Vec2(prevVec.x + perpDir.x, prevVec.y + perpDir.y)
                        if (gridBounds.isInBounds(corner1Vec)) {
                            val corner1Key = gridBounds.toFlat(corner1Vec)
                            if (cellsSet.contains(corner1Key)) {
                                return createLShape(stemCells, corner1Key)
                            }
                        }

                        // Check corner at 'next' end
                        val corner2Vec = Vec2(nextVec.x + perpDir.x, nextVec.y + perpDir.y)
                        if (gridBounds.isInBounds(corner2Vec)) {
                            val corner2Key = gridBounds.toFlat(corner2Vec)
                            if (cellsSet.contains(corner2Key)) {
                                return createLShape(stemCells, corner2Key)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun createShape(cells: Set<Int>, lShape: LShape): Shape {
        val mask = BitSet()
        val neighborsMask = BitSet()
        var crystalsCovered = 0

        for (cell in cells) {
            mask.set(cell)
            if (grid[cell] == BlockType.CRYSTAL) crystalsCovered++

            val vec = gridBounds.toVec(cell)
            for (dir in DIRECTION_OFFSETS) {
                val nVec = Vec2(vec.x + dir.x, vec.y + dir.y)
                if (gridBounds.isInBounds(nVec)) {
                    neighborsMask.set(gridBounds.toFlat(nVec))
                }
            }
        }
        return Shape(cells, mask, neighborsMask, crystalsCovered, lShape)
    }

    private fun createLShape(stemCells: Set<Int>, stopperCell: Int): LShape {
        val stemMask = BitSet()
        val stemNeighborsMask = BitSet()

        for (cell in stemCells) {
            stemMask.set(cell)
            val vec = gridBounds.toVec(cell)
            for (dir in DIRECTION_OFFSETS) {
                val nVec = Vec2(vec.x + dir.x, vec.y + dir.y)
                if (gridBounds.isInBounds(nVec)) {
                    stemNeighborsMask.set(gridBounds.toFlat(nVec))
                }
            }
        }
        return LShape(stemCells, stemMask, stemNeighborsMask, stopperCell)
    }

    // --- Backtracking Logic ---

    private fun backtrack(
        sortedIdx: Int,
        currentIslands: MutableList<Island>,
        slimeMask: BitSet,
        honeyMask: BitSet,
        lShapeStemMask: BitSet, // Mask of the "Stem" part of L-shapes (cannot touch other stems)
        currentOnes: Int,
        remainingPossibleTargets: Int,
        currentIslandsCount: Int
    ) {
        if (System.currentTimeMillis() - startTime > TIMEOUT_MS) return

        val currentScore = currentOnes - (currentIslandsCount * ISLAND_COST)

        // Base Case: All targets considered
        if (sortedIdx >= sortedTargetIndices.size) {
            if (currentScore > maxScore) {
                maxScore = currentScore
                bestSolutionGroups = ArrayList(currentIslands)
            }
            return
        }

        // Pruning: Score estimation
        if (currentScore + remainingPossibleTargets <= maxScore) return

        val realTargetIdx = sortedTargetIndices[sortedIdx]
        val targetFlatIndex = targets[realTargetIdx]

        // Pruning: Target already covered?
        if (slimeMask[targetFlatIndex] || honeyMask[targetFlatIndex]) {
            backtrack(
                sortedIdx + 1, currentIslands, slimeMask, honeyMask, lShapeStemMask,
                currentOnes, remainingPossibleTargets, currentIslandsCount
            )
            return
        }

        val shapes = possibleShapes[targetFlatIndex] ?: emptyList()

        for (shape in shapes) {
            // Collision Check: Overlap
            if (slimeMask.intersects(shape.mask) || honeyMask.intersects(shape.mask)) continue

            // Constraint: L-Shape stems cannot be adjacent (mechanical interference)
            if (lShapeStemMask.intersects(shape.lShape.stemNeighborsMask)) continue

            // Constraint: Adjacency Colors
            // Can only touch opposite color. Cannot touch BOTH colors.
            val touchesSlime = slimeMask.intersects(shape.neighborsMask)
            val touchesHoney = honeyMask.intersects(shape.neighborsMask)

            if (touchesSlime && touchesHoney) continue // Invalid: touches both

            val material = if (touchesSlime) Material.HONEY else Material.SLIME
            val activeMask = if (material == Material.SLIME) slimeMask else honeyMask
            val otherMask = if (material == Material.SLIME) honeyMask else slimeMask

            // Apply Move
            currentIslands.add(Island(shape.cells, shape.lShape, material))
            activeMask.or(shape.mask)
            lShapeStemMask.or(shape.lShape.stemMask)

            backtrack(
                sortedIdx + 1,
                currentIslands,
                slimeMask,
                honeyMask,
                lShapeStemMask,
                currentOnes + shape.onesCovered,
                remainingPossibleTargets - shape.onesCovered,
                currentIslandsCount + 1
            )

            // Undo Move
            lShapeStemMask.andNot(shape.lShape.stemMask)
            activeMask.andNot(shape.mask)
            currentIslands.removeAt(currentIslands.lastIndex)
        }

        // Option: Skip this target (leave uncovered)
        backtrack(
            sortedIdx + 1, currentIslands, slimeMask, honeyMask, lShapeStemMask,
            currentOnes, remainingPossibleTargets - 1, currentIslandsCount
        )
    }

    // --- Helpers & Data Classes ---

    private enum class Material { SLIME, HONEY }

    private data class Shape(
        val cells: Set<Int>,
        val mask: BitSet,
        val neighborsMask: BitSet, // Cells adjacent to the shape (used for color parity)
        val onesCovered: Int,
        val lShape: LShape
    )

    private data class LShape(
        val stemCells: Set<Int>,
        val stemMask: BitSet,
        val stemNeighborsMask: BitSet, // Used to ensure L-shapes don't jam each other
        val stopperCell: Int
    )

    private data class Island(
        val cells: Set<Int>,
        val lShape: LShape,
        val material: Material
    )

    /**
     * Helper to map between the arbitrary IntRange of GeodeProjection and a 0-based flat index array.
     */
    private class GridBounds(val xRange: IntRange, val yRange: IntRange) {
        val minX = xRange.first
        val minY = yRange.first
        val width = xRange.last - minX + 1
        val height = yRange.last - minY + 1

        fun toFlat(vec: Vec2): Int {
            return (vec.y - minY) * width + (vec.x - minX)
        }

        fun toVec(flat: Int): Vec2 {
            val y = flat / width
            val x = flat % width
            return Vec2(x + minX, y + minY)
        }

        fun isInBounds(vec: Vec2): Boolean {
            return vec.x in xRange && vec.y in yRange
        }
    }
}