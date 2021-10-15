package io.shiftleft.dataflowengineoss.passes.reachingdef

import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.codepropertygraph.generated.nodes.{CfgNode, Method, MethodParameterIn, Return, StoredNode}
import io.shiftleft.semanticcpg.language._
import org.slf4j.{Logger, LoggerFactory}
import overflowdb.traversal.jIteratortoTraversal

/**
  * A general data flow problem, formulated as in the Dragon Book, Second Edition
  * on page 626, with mild modifications. In particular, instead of allowing only
  * for the specification of a boundary, we allow initialization of IN and OUT.
  */
class DataFlowProblem[V](val flowGraph: FlowGraph,
                         val transferFunction: TransferFunction[V],
                         val meet: (V, V) => V,
                         val inOutInit: InOutInit[V],
                         val forward: Boolean,
                         val empty: V)

/**
  * In essence, the flow graph is the control flow graph, however, we can
  * compensate for small deviations from our generic control flow graph to
  * one that is better suited for solving data flow problems. In particular,
  * method parameters are not part of our normal control flow graph. By
  * defining successors and predecessors, we provide a wrapper that takes
  * care of these minor discrepancies.
  * */
class FlowGraph(val method: Method) {

  private val entryNode: StoredNode = method
  val exitNode: StoredNode = method.methodReturn

  private val reversePostOrder = List(entryNode) ++ method.parameter.toList ++ method.reversePostOrder.toList
    .filter(_.id != entryNode.id) ++ List(exitNode)

  val allNodesReversePostOrder: List[Int] = List.range(0, reversePostOrder.size)

  val nodeToNumber: Map[StoredNode, Int] = reversePostOrder.zipWithIndex.map { case (x, i) => x -> i }.toMap
  val numberToNode: Map[Int, StoredNode] = reversePostOrder.zipWithIndex.map { case (x, i) => i -> x }.toMap

  val succ: Map[Int, List[Int]] = initSucc()
  val pred: Map[Int, List[Int]] = initPred()

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Create a map that allows CFG successors to be retrieved for each node
    * */
  private def initSucc(): Map[Int, List[Int]] = {
    val m = reversePostOrder.map {
      case n @ (_: Return) => n -> List(exitNode)
      case n @ (param: MethodParameterIn) =>
        n -> {
          val nextParam = param.method.parameter.order(param.order + 1).headOption
          if (nextParam.isDefined) { nextParam.toList } else { param.method.cfgFirst.l }
        }
      case n @ (cfgNode: CfgNode) =>
        n ->
          // `.cfgNext` would be wrong here because it filters `METHOD_RETURN`
          cfgNode.out(EdgeTypes.CFG).map(_.asInstanceOf[StoredNode]).l
      case n =>
        logger.warn(s"Node type ${n.getClass.getSimpleName} should not be part of the CFG");
        n -> List()
    }.toMap
    m.map { case (k, xs) => nodeToNumber(k) -> xs.map(nodeToNumber) }.withDefaultValue(List())
  }

  /**
    * Create a map that allows CFG predecessors to be retrieved for each node
    * */
  private def initPred(): Map[Int, List[Int]] = {
    val m =
      reversePostOrder.map {
        case n @ (param: MethodParameterIn) =>
          n -> {
            val prevParam = param.method.parameter.order(param.order - 1).headOption
            if (prevParam.isDefined) { prevParam.toList } else { List(method) }
          }
        case n @ (_: CfgNode) if method.cfgFirst.headOption.contains(n) =>
          n -> method.parameter.l.sortBy(_.order).lastOption.toList

        case n @ (cfgNode: CfgNode) => n -> cfgNode.cfgPrev.l

        case n =>
          logger.warn(s"Node type ${n.getClass.getSimpleName} should not be part of the CFG");
          n -> List()
      }.toMap
    m.map { case (k, xs) => nodeToNumber(k) -> xs.map(nodeToNumber) }.withDefaultValue(List())
  }

}

/**
  * This is actually a function family consisting of one transfer
  * function for each node of the flow graph. Each function maps
  * from the analysis domain to the analysis domain, e.g., for
  * reaching definitions, sets of definitions are mapped to sets
  * of definitions.
  * */
trait TransferFunction[V] {
  def apply(n: Int, x: V): V
}

/**
  * As a practical optimization, OUT[N] is often initialized to GEN[N]. Moreover,
  * we need a way of specifying boundary conditions such as OUT[ENTRY] = {}. We
  * achieve both by allowing the data flow problem to specify initializers for
  * IN and OUT.
  * */
trait InOutInit[V] {

  def initIn: Map[Int, V]

  def initOut: Map[Int, V]

}

/**
  * The solution consists of `in` and `out` for each
  * node of the flow graph. We also attach the problem.
  * */
case class Solution[T](in: Map[StoredNode, T], out: Map[StoredNode, T], problem: DataFlowProblem[T])
