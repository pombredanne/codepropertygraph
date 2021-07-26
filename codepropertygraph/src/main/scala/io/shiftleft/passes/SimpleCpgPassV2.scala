package io.shiftleft.passes

import io.shiftleft.codepropertygraph.generated.nodes.{NewNode, StoredNode}

import java.lang.{Long => JLong}

/**
  * Base class for CPG pass - a program, which receives an input graph
  * and outputs a sequence of additive diff graphs. These diff graphs can
  * be merged into the original graph ("applied"), they can be serialized
  * into a binary format, and finally, they can be added to an existing
  * cpg.bin.zip file.
  *
  * A pass is provided by inheriting from this class and implementing `run`,
  * a method, which creates the sequence of diff graphs from an input graph.
  *
  * Overview of steps and their meaning:
  *
  * 1. Create: A sequence of diff graphs is created from the source graph
  * 2. Apply: Each diff graph can be applied to the source graph
  * 3. Serialize: After applying a diff graph, the diff graph can be serialized into a CPG overlay
  * 4. Store: The CPG overlay can be stored in a serialized CPG.
  *
  * @param cpg the source CPG this pass traverses
  */
abstract class SimpleCpgPassV2(keyPool: Option[KeyPool] = None)
  extends CpgPassV2[Unit](keyPool.map(Iterator.single)) {

  /**
    * Main method of enhancement - to be implemented by child class
    * */
  def run(diffGraphHandler: DiffGraphHandler): Unit

  override def partIterator: Iterator[Unit] = {
    Iterator.single(())
  }

  override def runOnPart(diffGraphHandler: DiffGraphHandler, part: Unit): Unit = {
    run(diffGraphHandler)
  }

}

abstract class CpgPassV2[T](val keyPools: Option[Iterator[KeyPool]] = None) {
  /**
    * Name of the enhancement pass.
    * By default it is inferred from the name of the class, override if needed.
    */
  def name: String = getClass.getName

  def init(): Unit = {}

  def partIterator: Iterator[T]

  def runOnPart(diffGraphHandler: DiffGraphHandler, part: T): Unit

  def workItemIterator: Iterator[WorkItem[T]] = {
    partIterator.map(new WorkItem(_, runOnPart))
  }

}

class WorkItem[T](item: T, runOnPart: (DiffGraphHandler, T) => Unit) {
  def run(diffGraphHandler: DiffGraphHandler): Unit = {
    runOnPart(diffGraphHandler, item)
  }
}

trait DiffGraphHandler {
  def addDiffGraph(diffGraph: DiffGraph): Unit
}

/**
  * Diff Graph that has been applied to a source graph. This is a wrapper around
  * diff graph, which additionally provides a map from nodes to graph ids.
  * */
case class AppliedDiffGraph(diffGraph: DiffGraph,
                            inverseDiffGraph: Option[DiffGraph],
                            private val nodeToOdbNode: java.util.IdentityHashMap[NewNode, StoredNode]) {

  /**
    * Obtain the id this node has in the applied graph
    * */
  def nodeToGraphId(node: NewNode): JLong = {
    nodeToOdbNode.get(node).id
  }
}