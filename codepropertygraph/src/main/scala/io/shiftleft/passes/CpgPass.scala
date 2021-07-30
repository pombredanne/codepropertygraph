package io.shiftleft.passes

import com.google.protobuf.GeneratedMessageV3
import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{NewNode, StoredNode}
import io.shiftleft.passes.CpgPassBase.logger
import org.slf4j.{Logger, LoggerFactory, MDC}

import java.lang.{Long => JLong}
import scala.concurrent.duration.DurationLong

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
abstract class CpgPass(cpg: Cpg, outName: String = "", keyPool: Option[KeyPool] = None) extends CpgPassBase {

  /**
    * Main method of enhancement - to be implemented by child class
    * */
  def run(): Iterator[DiffGraph]

  /**
    * Execute the enhancement and apply result to the underlying graph
    */
  override def createAndApply(): Unit = {
    createApplySerializeAndStore(null)
  }

  /**
    * Run a CPG pass to create diff graphs, apply diff graphs, create corresponding
    * overlays and add them to the serialized CPG. The name of the overlay is derived
    * from the class name of the pass.
    *
    * @param serializedCpg the destination serialized CPG to add overlays to
    * @param inverse invert the diffgraph before serializing
    * @param prefix a prefix to add to the output name
    * */
  override def createApplySerializeAndStore(serializedCpg: SerializedCpg,
                                            inverse: Boolean = false,
                                            prefix: String = ""): Unit = {
    logger.info(s"Start of enhancement: $name")
    val tic = System.nanoTime()
    //diagnostics
    var _nChunks = 0
    var _nDiffElements = 0
    var _nDiffElementsExpanded = 0
    var _nanosApplier = 0L
    var _nanosSerialize = 0L
    var _nanosStore = 0L

    try {
      var index = 0
      for (diffGraph <- run()) {
        val applyTic = System.nanoTime
        _nDiffElements += diffGraph.size
        _nChunks += 1
        val applied = DiffGraph.Applier.applyDiff(diffGraph,
                                                  cpg,
                                                  undoable = inverse && serializedCpg != null && !serializedCpg.isEmpty,
                                                  keyPool)
        _nDiffElementsExpanded += applied.size
        val applyToc = System.nanoTime()
        _nanosApplier += (applyToc - applyTic)
        if (serializedCpg != null && !serializedCpg.isEmpty) {
          val serialized = serialize(applied, inverse)
          val serializedToc = System.nanoTime
          _nanosSerialize += serializedToc - applyToc
          store(serialized, generateOutFileName(prefix, outName, index), serializedCpg)
          val storeToc = System.nanoTime
          _nanosStore += storeToc - serializedToc
        }
        index += 1
      }
    } catch {
      case exception: Exception =>
        logger.error(s"Enhancement ${name} failed!", exception)
        throw exception
    } finally {
      val toc = System.nanoTime()
      if (serializedCpg == null || serializedCpg.isEmpty) {
        val fracApply = (_nanosApplier * 1e-2 / (1 + toc - tic))
        val fracRun = (100.0 - fracApply)
        MDC.put("time", s"${(toc - tic) * 1e-6}%.0f")
        logger.info(
          f"Enhancement $name completed in ${(toc - tic) * 1e-6}%.0f ms (split: ${fracRun}%2.0f%%/${fracApply}%2.0f%%). ${_nDiffElements}%d + ${_nDiffElementsExpanded - _nDiffElements}%d changes commited over ${_nChunks}%d chunks.")
        MDC.remove("time")
      } else {
        val fracApply = (_nanosApplier * 1e-2 / (1 + toc - tic))
        val fracSerialize = (_nanosSerialize * 1e-2 / (1 + toc - tic))
        val fracStore = (_nanosStore * 1e-2 / (1 + toc - tic))
        val fracRun = (100.0 - fracApply - fracSerialize - fracStore)
        MDC.put("time", s"${(toc - tic) * 1e-6}%.0f")
        logger.info(
          f"Enhancement $name completed in ${(toc - tic) * 1e-6}%.0f ms (split: ${fracRun}%2.0f%%/${fracApply}%2.0f%%/${fracSerialize}%2.0f%%/${fracStore}%2.0f%%${if (inverse) " including inverse"
          else ""}). ${_nDiffElements}%d + ${_nDiffElementsExpanded - _nDiffElements}%d changes committed over ${_nChunks}%d chunks.")
        MDC.remove("time")
      }
    }
  }

}

object CpgPassBase {
  val logger: Logger = LoggerFactory.getLogger(classOf[CpgPass])
}

trait CpgPassBase {

  def createAndApply(): Unit

  def createApplySerializeAndStore(serializedCpg: SerializedCpg, inverse: Boolean = false, prefix: String = ""): Unit

  /**
    * Name of the enhancement pass.
    * By default it is inferred from the name of the class, override if needed.
    */
  def name: String = getClass.getName

  protected def serialize(appliedDiffGraph: AppliedDiffGraph, inverse: Boolean): GeneratedMessageV3 = {
    if (inverse) {
      new DiffGraphProtoSerializer().serialize(appliedDiffGraph.inverseDiffGraph.get)
    } else {
      new DiffGraphProtoSerializer().serialize(appliedDiffGraph)
    }
  }

  protected def generateOutFileName(prefix: String, outName: String, index: Int): String = {
    val outputName = {
      if (outName.isEmpty) {
        this.getClass.getSimpleName
      } else {
        outName
      }
    }
    prefix + "_" + outputName + "_" + index
  }

  protected def store(overlay: GeneratedMessageV3, name: String, serializedCpg: SerializedCpg): Unit = {
    if (overlay.getSerializedSize > 0) {
      serializedCpg.addOverlay(overlay, name)
    }
  }

  protected def withStartEndTimesLogged[A](fun: => A): A = {
    logger.info(s"Start of enhancement: $name")
    val startTime = System.currentTimeMillis
    try {
      fun
    } finally {
      val duration = (System.currentTimeMillis - startTime).millis.toCoarsest
      MDC.put("time", duration.toString())
      logger.info(s"Enhancement $name completed in $duration")
      MDC.remove("time")
    }
  }

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
  // for diagnostics
  var size = 0
}
