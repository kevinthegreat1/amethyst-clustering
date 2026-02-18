import geode.GeodeProjection
import solution.Solution
import solution.Solver
import solvers.*
import kotlin.math.roundToInt

const val PUSH_LIMIT = 12

fun main() {
    testSolvers(
        UselessSolver(),
        MLFlexerSolver(),
        MLFlexerSolver(merge = false),
        PalaniJohnsonSolver(),
        IterniamSolver(tries = 20),
        ThemorlockSolver(),
    )
}

fun testSolvers(vararg solvers: Solver, file: String = "./src/main/resources/geodes.txt") {
    val geodes = GeodeProjection.fromFile(file).subList(0, 50)

    val percentTotals = MutableList(solvers.size) { 0.0 }
    val groupTotals = MutableList(solvers.size) { 0.0 }
    val blockTotals = MutableList(solvers.size) { 0.0 }
    val invalidSolutions = MutableList(solvers.size) { mutableListOf<Solution>() }
    val worstSolution: MutableList<Solution?> = MutableList(solvers.size) { null }
    val bestSolution: MutableList<Solution?> = MutableList(solvers.size) { null }
    val bunnySolution: MutableList<Solution?> = MutableList(solvers.size) { null }

    var iterations = 0
    geodes.forEach { proj ->
        println("$iterations/${geodes.size}")
        for ((i, solver) in solvers.withIndex()) {
            val solution = solver.solve(proj)
            val percent = solution.crystalPercentage()
            percentTotals[i] += percent
            groupTotals[i] += solution.crystalCount() / solution.groupCount().toDouble()
            blockTotals[i] += solution.stickyBlockCount().toDouble() / solution.crystalCount()
            if (solution.checkIfValid().isNotEmpty()) {
                invalidSolutions[i].add(solution)
            }
            if (worstSolution[i] == null || worstSolution[i]!!.betterThan(solution)) {
                worstSolution[i] = solution
            }
            if (bestSolution[i] == null || solution.betterThan(bestSolution[i]!!)) {
                bestSolution[i] = solution
            }
            if (iterations == 0) {
                bunnySolution[i] = solution
            }
        }
        iterations++
    }

    fun p(num: Double): String =
        "${(num * 10000).roundToInt().toDouble() / 100}"

    fun r(num: Double): String =
        "${(num * 100).roundToInt().toDouble() / 100}"

    fun show(solution: Solution?, name: String) {
        if (solution != null && solution.groupCount() != 0) {
            println(
                "  $name Solution: ${p(solution.crystalPercentage())}% crystals, ${solution.groupCount()} groups, ${solution.stickyBlockCount()} blocks for ${solution.crystalCount()} crystals (${
                    r(
                        solution.crystalCount().toDouble() / solution.groupCount()
                    )
                } Crystals / Group)"
            )
            solution.prettyPrint(true)
        }
    }

    solvers
        .withIndex()
        .sortedBy { blockTotals[it.index] }
        .sortedByDescending { groupTotals[it.index] }
        .sortedByDescending { percentTotals[it.index] }
        .reversed()
        .forEach { (i, solver) ->
            println("${solver.name()}:")
            println("  Avg. Crystal Percentage: ${p(percentTotals[i] / iterations)}%")
            println("  Avg. Crystals / Group : ${r(groupTotals[i] / iterations)}")
            println("  Avg. Blocks / Crystal: ${r(blockTotals[i] / iterations)}")
            println("  Num of Invalid Solutions: ${invalidSolutions[i].size} (${invalidSolutions[i].size / iterations * 100}%)")
            show(worstSolution[i], "Worst")
            show(bestSolution[i], "Best")
            show(bunnySolution[i], "\"Bunny\"")
            println()
        }

}