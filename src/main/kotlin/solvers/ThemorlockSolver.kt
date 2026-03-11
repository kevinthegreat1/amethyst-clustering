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
    private val MAX_SHAPES_PER_TARGET = 100
    private val TIMEOUT_MS = 2_000L
    private val ISLAND_COST = 1.0

    // --- Internal State ---
    private lateinit var gridBounds: GridBounds
    private lateinit var grid: Array<BlockType>
    private lateinit var possibleShapes: Map<Int, MutableList<Shape>>
    private lateinit var sortedTargetIndices: List<Int>
    private lateinit var targets: List<Int> // List of flat indices for CRYSTAL
    private lateinit var targetIndices: MutableMap<Int, Int> // flat index -> index in targets
    private var bestScore: Double = Double.NEGATIVE_INFINITY
    private var bestSolution = mutableListOf<Island?>()
    private var bestSolutionSlimeMask = BitSet()
    private var bestSolutionHoneyMask = BitSet()
    private var startTime: Long = 0
    private var backtrackCalls: Long = 0
    private var timedOut: Boolean = false

    override fun solve(proj: GeodeProjection): Solution {
        bestScore = Double.NEGATIVE_INFINITY
        bestSolution = mutableListOf()
        bestSolutionSlimeMask = BitSet()
        bestSolutionHoneyMask = BitSet()
        startTime = System.currentTimeMillis()
        backtrackCalls = 0
        timedOut = false

        // 1. Initialize Grid System (Mapping Vec2 <-> Flat Index)
        gridBounds = GridBounds(proj.xRange, proj.yRange)
        val totalCells = gridBounds.width * gridBounds.height
        grid = Array(totalCells) { i ->
            proj[gridBounds.toVec(i)]
        }

        // 2. Identify Targets (Crystals)
        targets = grid.indices.filter { grid[it] == BlockType.CRYSTAL }
        targetIndices = mutableMapOf()
        for ((idx, flatIdx) in targets.withIndex()) {
            targetIndices[flatIdx] = idx
        }

        if (targets.isEmpty()) {
            return Solution(proj, mutableListOf())
        }

        // 3. Precompute Valid Shapes
        possibleShapes = precomputeShapes()

        // 4. Sort Targets by Scarcity (Fewest shapes first)
        sortedTargetIndices = targets.indices.sortedBy { targetIdx ->
            val flatIndex = targets[targetIdx]
            possibleShapes[flatIndex]?.size ?: 0
        }

        // Sort shapes by efficiency (most crystals covered first)
        for (list in possibleShapes.values) {
            list.sortWith(Comparator.comparingInt<Shape> { it.onesCovered }.reversed())
        }

        // 5. Backtracking
        backtrack(
            sortedIdx = 0,
            currentIslands = mutableListOf(),
            slimeMask = BitSet(totalCells),
            honeyMask = BitSet(totalCells),
            flyingMachineStemMask = BitSet(totalCells),
            currentOnes = 0,
            remainingPossibleTargets = targets.size,
            currentIslandsCount = 0
        )

        // 6. Hill Climb
        hillClimbSolution()

        // 7. Build Result
        val resultGroups = bestSolution.filterNotNull().map { island ->
            SolutionGroup(
                blockLocations = island.cells.map { gridBounds.toVec(it) }.toMutableSet(),
                blockType = if (island.material == Material.SLIME) StickyBlockType.SLIME else StickyBlockType.HONEY
            )
        }.toMutableList()

        return Solution(proj, resultGroups)
    }

    // --- Flat Index Neighbor Iteration ---

    /**
     * Iterates over all 4-connected neighbors of the given flat index,
     * performing bounds checking via row/col arithmetic to avoid Vec2 allocation.
     */
    private inline fun forEachFlatNeighbor(flat: Int, action: (Int) -> Unit) {
        val w = gridBounds.width
        val col = flat % w
        val row = flat / w
        if (col < w - 1) action(flat + 1)              // right
        if (col > 0) action(flat - 1)                    // left
        if (row < gridBounds.height - 1) action(flat + w) // down
        if (row > 0) action(flat - w)                    // up
    }

    // --- Shape Generation ---

    private fun precomputeShapes(): Map<Int, MutableList<Shape>> {
        val shapesByTarget = mutableMapOf<Int, MutableList<Shape>>()
        val globalSeenShapes = mutableSetOf<Set<Int>>()

        for (targetFlatIdx in targets) {
            shapesByTarget.putIfAbsent(targetFlatIdx, mutableListOf())

            val queueHarvest = ArrayDeque<Set<Int>>()
            val queueAir = ArrayDeque<Set<Int>>()
            val seenLocal = mutableSetOf<Set<Int>>()

            val startSet = setOf(targetFlatIdx)
            queueHarvest.add(startSet)
            seenLocal.add(startSet)

            var shapesFound = 0

            while ((queueHarvest.isNotEmpty() || queueAir.isNotEmpty()) && shapesFound < MAX_SHAPES_PER_TARGET) {
                val currentCells = if (queueHarvest.isNotEmpty()) queueHarvest.poll() else queueAir.poll()

                if (currentCells.size >= MAX_ISLAND_SIZE) continue
                val neighbors = getNeighbors(currentCells)

                for (n in neighbors) {
                    val newShapeCells = currentCells + n

                    if (!seenLocal.add(newShapeCells)) continue

                    // Prioritize harvest-cell neighbors
                    if (grid[n] == BlockType.CRYSTAL) {
                        queueHarvest.add(newShapeCells)
                    } else {
                        queueAir.add(newShapeCells)
                    }

                    if (newShapeCells.size < MIN_ISLAND_SIZE) continue

                    val flyingMachine = findFlyingMachine(newShapeCells) ?: continue

                    if (!globalSeenShapes.add(newShapeCells)) continue

                    val shape = createShape(newShapeCells, flyingMachine)

                    // Register this shape for every target it covers
                    for (cell in newShapeCells) {
                        val ti = targetIndices[cell]
                        if (ti != null) {
                            shapesByTarget.computeIfAbsent(targets[ti]) { mutableListOf() }.add(shape)
                        }
                    }
                    shapesFound++
                }
            }
        }

        return shapesByTarget
    }

    private fun getNeighbors(cells: Set<Int>): Set<Int> {
        val neighbors = mutableSetOf<Int>()
        for (cell in cells) {
            forEachFlatNeighbor(cell) { n ->
                // Can expand into AIR or CRYSTAL, not BUD
                if (grid[n] != BlockType.BUD && n !in cells) {
                    neighbors.add(n)
                }
            }
        }
        return neighbors
    }

    /**
     * Finds the flying machine cells (4 cells: 3 in a row + 1 neighbor).
     * Returns the geometry details or null if no valid configuration exists in the blob.
     */
    private fun findFlyingMachine(cells: Set<Int>): FlyingMachine? {
        val w = gridBounds.width
        val h = gridBounds.height

        for (key in cells) {
            val col = key % w
            val row = key / w

            // Horizontal stem (delta = 1): prev=key-1, key, next=key+1
            if (col >= 1 && col < w - 1) {
                val prev = key - 1
                val next = key + 1
                if (prev in cells && next in cells) {
                    val stemCells = setOf(prev, key, next)
                    // 1. Check perpendicular down (+w)
                    if (row < h - 1) {
                        if (prev + w in cells) return createFlyingMachine(stemCells, prev + w)
                        if (key + w in cells) return createFlyingMachine(stemCells, key + w)
                        if (next + w in cells) return createFlyingMachine(stemCells, next + w)
                    }
                    // 2. Check perpendicular up (-w)
                    if (row > 0) {
                        if (prev - w in cells) return createFlyingMachine(stemCells, prev - w)
                        if (key - w in cells) return createFlyingMachine(stemCells, key - w)
                        if (next - w in cells) return createFlyingMachine(stemCells, next - w)
                    }
                    // 3. End sides (1x4 case)
                    if (col >= 2 && prev - 1 in cells) return createFlyingMachine(stemCells, prev - 1)
                    if (col < w - 2 && next + 1 in cells) return createFlyingMachine(stemCells, next + 1)
                }
            }

            // Vertical stem (delta = w): prev=key-w, key, next=key+w
            if (row >= 1 && row < h - 1) {
                val prev = key - w
                val next = key + w
                if (prev in cells && next in cells) {
                    val stemCells = setOf(prev, key, next)
                    // 1. Check perpendicular right (+1)
                    if (col < w - 1) {
                        if (prev + 1 in cells) return createFlyingMachine(stemCells, prev + 1)
                        if (key + 1 in cells) return createFlyingMachine(stemCells, key + 1)
                        if (next + 1 in cells) return createFlyingMachine(stemCells, next + 1)
                    }
                    // 2. Check perpendicular left (-1)
                    if (col > 0) {
                        if (prev - 1 in cells) return createFlyingMachine(stemCells, prev - 1)
                        if (key - 1 in cells) return createFlyingMachine(stemCells, key - 1)
                        if (next - 1 in cells) return createFlyingMachine(stemCells, next - 1)
                    }
                    // 3. End sides (1x4 case)
                    if (row >= 2 && prev - w in cells) return createFlyingMachine(stemCells, prev - w)
                    if (row < h - 2 && next + w in cells) return createFlyingMachine(stemCells, next + w)
                }
            }
        }
        return null
    }

    private fun createShape(cells: Set<Int>, flyingMachine: FlyingMachine): Shape {
        val mask = BitSet()
        val neighborsMask = BitSet()
        var crystalsCovered = 0

        for (cell in cells) {
            mask.set(cell)
            if (grid[cell] == BlockType.CRYSTAL) crystalsCovered++
            forEachFlatNeighbor(cell) { n ->
                if (n !in cells) neighborsMask.set(n)
            }
        }
        return Shape(cells, mask, neighborsMask, crystalsCovered, flyingMachine)
    }

    private fun createFlyingMachine(stemCells: Set<Int>, stopperCell: Int): FlyingMachine {
        val stemMask = BitSet()
        val stemNeighborsMask = BitSet()

        for (cell in stemCells) {
            stemMask.set(cell)
            forEachFlatNeighbor(cell) { n ->
                if (n !in stemCells) stemNeighborsMask.set(n)
            }
        }
        return FlyingMachine(stemCells, stemMask, stemNeighborsMask, stopperCell)
    }

    // --- Backtracking Logic ---

    private fun backtrack(
        sortedIdx: Int,
        currentIslands: MutableList<Island>,
        slimeMask: BitSet,
        honeyMask: BitSet,
        flyingMachineStemMask: BitSet,
        currentOnes: Int,
        remainingPossibleTargets: Int,
        currentIslandsCount: Int
    ) {
        if ((backtrackCalls++ and 0xFFFF) == 0L && System.currentTimeMillis() - startTime > TIMEOUT_MS) {
            timedOut = true
            return
        }

        val currentScore = currentOnes - (currentIslandsCount * ISLAND_COST)

        // Base Case: All targets considered
        if (sortedIdx >= sortedTargetIndices.size) {
            if (currentScore > bestScore) {
                bestScore = currentScore
                bestSolution = ArrayList(currentIslands)
                bestSolutionSlimeMask = slimeMask.clone() as BitSet
                bestSolutionHoneyMask = honeyMask.clone() as BitSet
            }
            return
        }

        // Pruning: Score estimation
        if (currentScore + remainingPossibleTargets <= bestScore) return

        val realTargetIdx = sortedTargetIndices[sortedIdx]
        val targetFlatIndex = targets[realTargetIdx]

        // Pruning: Target already covered?
        if (slimeMask[targetFlatIndex] || honeyMask[targetFlatIndex]) {
            backtrack(
                sortedIdx + 1, currentIslands, slimeMask, honeyMask, flyingMachineStemMask,
                currentOnes, remainingPossibleTargets, currentIslandsCount
            )
            return
        }

        val shapes = possibleShapes[targetFlatIndex] ?: emptyList()

        for (shape in shapes) {
            // Collision Check: Overlap
            if (slimeMask.intersects(shape.mask) || honeyMask.intersects(shape.mask)) continue

            // Constraint: Flying machine stems cannot be adjacent (mechanical interference)
            if (flyingMachineStemMask.intersects(shape.flyingMachine.stemNeighborsMask)) continue

            // Constraint: Adjacency Colors - cannot touch both colors
            val touchesSlime = slimeMask.intersects(shape.neighborsMask)
            if (touchesSlime && honeyMask.intersects(shape.neighborsMask)) continue

            val material = if (touchesSlime) Material.HONEY else Material.SLIME
            val activeMask = if (material == Material.SLIME) slimeMask else honeyMask

            // Apply Move
            currentIslands.add(Island(shape.cells, shape.mask, shape.flyingMachine, material))
            activeMask.or(shape.mask)
            flyingMachineStemMask.or(shape.flyingMachine.stemMask)

            backtrack(
                sortedIdx + 1,
                currentIslands,
                slimeMask,
                honeyMask,
                flyingMachineStemMask,
                currentOnes + shape.onesCovered,
                remainingPossibleTargets - shape.onesCovered,
                currentIslandsCount + 1
            )

            if (timedOut) return

            // Undo Move
            flyingMachineStemMask.andNot(shape.flyingMachine.stemMask)
            activeMask.andNot(shape.mask)
            currentIslands.removeAt(currentIslands.lastIndex)
        }

        // Option: Skip this target (leave uncovered)
        backtrack(
            sortedIdx + 1, currentIslands, slimeMask, honeyMask, flyingMachineStemMask,
            currentOnes, remainingPossibleTargets - 1, currentIslandsCount
        )
    }

    // --- Hill Climbing ---

    private fun hillClimbSolution() {
        var improved = true
        while (improved) {
            improved = false

            bestSolution = ArrayList<Island?>(bestSolution.filterNotNull().sortedBy { it.cells.size })

            var i = 0
            while (i < bestSolution.size) {
                val island = bestSolution[i]
                if (island == null || island.cells.size >= MAX_ISLAND_SIZE) {
                    i++
                    continue
                }

                val materialMask = if (island.material == Material.SLIME) bestSolutionSlimeMask else bestSolutionHoneyMask

                val neighbors = getNeighbors(island.cells)
                var stayAtSameIndex = false
                for (n in neighbors) {
                    if (isAdjacentToSameMaterial(materialMask, island.mask, n)) continue

                    // Expand island into uncovered harvest cells
                    if (grid[n] == BlockType.CRYSTAL && !bestSolutionSlimeMask[n] && !bestSolutionHoneyMask[n]) {
                        materialMask.set(n)
                        bestSolution[i] = island.withCell(n)
                        improved = true
                        break
                    }

                    // Find the island occupying this neighbor cell
                    val j = getIslandIndexAt(n)
                    if (j < 0 || i == j) continue
                    val neighboring = bestSolution[j] ?: continue

                    // Try merging the neighboring island into the current island
                    if (tryMerge(i, island, materialMask, j, neighboring)) {
                        improved = true
                        break
                    }

                    // Try stealing this cell from the neighboring island
                    if (tryTakeCell(i, island, n, j, neighboring)) {
                        stayAtSameIndex = true
                        break
                    }
                }

                if (stayAtSameIndex) continue
                i++
            }
        }
    }

    private fun getIslandIndexAt(flatIdx: Int): Int {
        for (j in bestSolution.indices) {
            val island = bestSolution[j]
            if (island != null && island.mask[flatIdx]) {
                return j
            }
        }
        return -1
    }

    /**
     * Try merging neighbor into the current island, preferring slime if possible.
     */
    private fun tryMerge(i: Int, island: Island, materialMask: BitSet, j: Int, neighboring: Island): Boolean {
        if (island.cells.size + neighboring.cells.size > MAX_ISLAND_SIZE) return false

        val neighboringMaterialMask = if (neighboring.material == Material.SLIME) bestSolutionSlimeMask else bestSolutionHoneyMask
        val canChangeIsland = island.cells.none { key -> isAdjacentToSameMaterial(neighboringMaterialMask, neighboring.mask, key) }
        val canChangeNeighbor = neighboring.cells.none { key -> isAdjacentToSameMaterial(materialMask, island.mask, key) }

        if (island.material == Material.SLIME && canChangeNeighbor || neighboring.material == Material.SLIME && canChangeIsland) {
            return merge(i, island, j, neighboring, Material.SLIME)
        } else if (island.material == Material.HONEY && canChangeNeighbor || neighboring.material == Material.HONEY && canChangeIsland) {
            return merge(i, island, j, neighboring, Material.HONEY)
        }
        return false
    }

    private fun merge(i: Int, island: Island, j: Int, neighboring: Island, newMaterial: Material): Boolean {
        if (newMaterial == Material.SLIME) {
            bestSolutionSlimeMask.or(island.mask)
            bestSolutionSlimeMask.or(neighboring.mask)
            bestSolutionHoneyMask.andNot(island.mask)
            bestSolutionHoneyMask.andNot(neighboring.mask)
        } else {
            bestSolutionSlimeMask.andNot(island.mask)
            bestSolutionSlimeMask.andNot(neighboring.mask)
            bestSolutionHoneyMask.or(island.mask)
            bestSolutionHoneyMask.or(neighboring.mask)
        }

        bestSolution[i] = island.union(neighboring, newMaterial)
        bestSolution[j] = null // Avoid breaking existing indices

        return true
    }

    /**
     * Try to steal the provided cell from a neighbor.
     * This allows other larger islands to expand and does not count as an improvement.
     */
    private fun tryTakeCell(i: Int, island: Island, n: Int, j: Int, neighboring: Island): Boolean {
        if (neighboring.cells.size <= 4) return false

        // Create the new neighboring island state after losing this cell.
        val neighborNewCells = neighboring.cells - n
        val neighborNewMask = neighboring.mask.clone() as BitSet
        neighborNewMask.clear(n)
        var neighborFlyingMachine = neighboring.flyingMachine

        // If we steal part of the neighbor's flying machine, find a new one.
        if (neighborFlyingMachine.stemMask[n] || neighborFlyingMachine.stopperCell == n) {
            neighborFlyingMachine = findFlyingMachine(neighborNewCells) ?: return false
        }

        // Check the neighbor is still connected after losing this cell.
        if (!isConnected(neighborNewCells)) return false

        // Update the material masks to reflect the cell transfer.
        if (island.material == Material.SLIME) {
            bestSolutionSlimeMask.set(n)
            bestSolutionHoneyMask.clear(n)
        } else {
            bestSolutionSlimeMask.clear(n)
            bestSolutionHoneyMask.set(n)
        }

        bestSolution[i] = island.withCell(n)
        bestSolution[j] = Island(neighborNewCells, neighborNewMask, neighborFlyingMachine, neighboring.material)

        return true
    }

    /**
     * Checks if cell [key] is adjacent to cells in [materialMask] that are NOT in [excluding].
     * Used during hill climbing to check same-material adjacency constraints.
     */
    private fun isAdjacentToSameMaterial(materialMask: BitSet, excluding: BitSet, key: Int): Boolean {
        forEachFlatNeighbor(key) { n ->
            if (materialMask[n] && !excluding[n]) return true
        }
        return false
    }

    /**
     * Checks if the given cells form a connected component using BFS.
     */
    private fun isConnected(cells: Set<Int>): Boolean {
        if (cells.isEmpty()) return true

        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        val start = cells.first()
        queue.add(start)
        visited.add(start)

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            forEachFlatNeighbor(current) { n ->
                if (n in cells && visited.add(n)) queue.add(n)
            }
        }

        return visited.size == cells.size
    }

    // --- Helpers & Data Classes ---

    private enum class Material { SLIME, HONEY }

    private data class Shape(
        val cells: Set<Int>,
        val mask: BitSet,
        val neighborsMask: BitSet, // Cells adjacent to the shape (used for color parity)
        val onesCovered: Int,
        val flyingMachine: FlyingMachine
    )

    private data class FlyingMachine(
        val stemCells: Set<Int>,
        val stemMask: BitSet,
        val stemNeighborsMask: BitSet, // Used to ensure flying machines don't jam each other
        val stopperCell: Int
    )

    private data class Island(
        val cells: Set<Int>,
        val mask: BitSet,
        val flyingMachine: FlyingMachine,
        val material: Material
    ) {
        fun withCell(cell: Int): Island {
            val newCells = cells + cell
            val newMask = mask.clone() as BitSet
            newMask.set(cell)
            return Island(newCells, newMask, flyingMachine, material)
        }

        fun union(other: Island, newMaterial: Material): Island {
            val newCells = cells + other.cells
            val newMask = mask.clone() as BitSet
            newMask.or(other.mask)
            return Island(newCells, newMask, flyingMachine, newMaterial)
        }
    }

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
