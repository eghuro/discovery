package services.discovery

import java.util.UUID

import play.Logger
import services.discovery.components.analyzer.LinksetBasedUnion
import services.discovery.components.transformer.FusionTransformer
import services.discovery.model._
import services.discovery.model.components.{ComponentInstanceWithInputs, DataSourceInstance, ExtractorInstance, SparqlUpdateTransformerInstance}
import services.discovery.model.internal.DiscoveryIteration

import scala.collection.mutable
import scala.collection.parallel.ParSeq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


class Discovery(val id: UUID, val input: DiscoveryInput, maxIterations: Int = 10)
   (portMatcher: DiscoveryPortMatcher, pipelineBuilder: PipelineBuilder)
   (implicit executor: ExecutionContext)
{

    val results = new mutable.HashMap[UUID, Pipeline]

    var isFinished = false

    val onStop : mutable.ArrayBuffer[Discovery => Unit] = new mutable.ArrayBuffer[Discovery => Unit]()

    private val discoveryLogger = Logger.of("discovery")

    private val timer = new Timer

    def start: Future[Seq[Pipeline]] = {
        timer.start
        discoveryLogger.info(s"[$id] Starting with ${input.dataSets.size} data sets, ${input.processors.size} processors and ${input.applications.size} applications.")

        val initialFragments = createInitialPipelineFragments(input.dataSets)
        val pipelines = initialFragments.flatMap { fragments =>
            val iteration = DiscoveryIteration(
                id = id,
                fragments = fragments,
                pipelines = Seq(),
                input = input,
                number = 1
            )
            iterate(iteration)
        }

        pipelines.onComplete(_ => isFinished = true)
        pipelines.onSuccess({case p: Seq[Pipeline] => discoveryLogger.info(s"[$id] Success, ${p.size} pipelines found.")})
        pipelines.onFailure({case f => discoveryLogger.info(s"[$id] Failure: ${f.getCause}.\n${f.getStackTrace.mkString("\n")}")})

        pipelines
    }

    private def iterate(iteration: DiscoveryIteration): Future[Seq[Pipeline]] = {
        iterationBody(iteration).flatMap { nextIteration =>

            val discoveredNewPipeline = nextIteration.discoveredNewPipeline(iteration)
            val stop = !discoveredNewPipeline || iteration.number == maxIterations

            discoveryLogger.debug(s"[$id][${iteration.number}] Iteration finished.")
            discoveryLogger.debug(s"[$id][${iteration.number}] Discovered any new pipelines: $discoveredNewPipeline.")
            discoveryLogger.debug(s"[$id][${iteration.number}] Next iteration: ${!stop}.")

            stop match {
                case true => finalize(nextIteration)
                case false => iterate(nextIteration)
            }
        }
    }

    private def iterationBody(iteration: DiscoveryIteration): Future[DiscoveryIteration] = {
        discoveryLogger.debug(s"[$id][${iteration.number}] Starting iteration.")

        val eventualPipelines = Future.sequence(
            getCombinatorInput(iteration).flatMap { ci =>
                ci.components.map { component =>
                    val relevantFragments = getRelevantFragments(component, ci.fragments)
                    portMatcher.tryMatchPorts(component, relevantFragments, iteration.number)
                }
            }
        )

        eventualPipelines.map { rawPipelines =>

            val newPipelines = rawPipelines.flatten
            val fresh = newPipelines//newPipelines.filter(containsBindingToIteration(iteration.number - 1))
            val (completePipelines, pipelineFragments) = fresh.partition(_.isComplete)

            discoveryLogger.debug(s"[$id][${iteration.number}] Found ${newPipelines.size} pipelines in the last iteration, ${fresh.size} new.")
            discoveryLogger.debug(s"[$id][${iteration.number}] ${completePipelines.size} complete pipelines, ${pipelineFragments.size} pipeline fragments.")

            val consolidatedFragments = pipelineFragments//consolidateFragments(pipelineFragments.par, iteration.number)

            completePipelines.foreach{ p =>
                results.put(UUID.randomUUID(), p)
            }

            DiscoveryIteration(
                id = iteration.id,
                fragments = (/*iteration.fragments ++ */consolidatedFragments).distinct.seq,
                pipelines = iteration.pipelines ++ completePipelines,
                input = iteration.input,
                number = iteration.number + 1
            )
        }
    }

    private def consolidateFragments(pipelineFragments: ParSeq[Pipeline], discoveryIteration: Int) : ParSeq[Pipeline] = {

        val (endingWithTransformer, others) = pipelineFragments.partition(p => p.endsWith[SparqlUpdateTransformerInstance])

        val datasourceGroups = endingWithTransformer.groupBy(p => p.typedDatasources)
        val dsgFragments = datasourceGroups.map { dsg =>
            val transformerGroups = dsg._2.groupBy(p => p.lastComponent.componentInstance.asInstanceOf[SparqlUpdateTransformerInstance].transformerGroupIri) // What if it ends with something else?!
            val (withTransformerGroup, withoutTransformerGroup) = transformerGroups.partition(tg => tg._1.isDefined)

            val newFragments = withTransformerGroup.map { transformerGroup =>
                val distinctTransformers = transformerGroup._2.map(_.lastComponent.componentInstance.asInstanceOf[SparqlUpdateTransformerInstance]).distinct
                val randomPipelineFragment = transformerGroup._2.head
                val transformer = randomPipelineFragment.lastComponent.componentInstance.asInstanceOf[SparqlUpdateTransformerInstance]
                val transformersToAttach = distinctTransformers.filter(t => t != transformer)

                var fragment = randomPipelineFragment
                transformersToAttach.foreach { t =>
                    fragment = Await.ready(pipelineBuilder.buildPipeline(t, Seq(PortMatch(t.getInputPorts.head, fragment, None)), discoveryIteration), atMost = Duration(30, SECONDS)).value.get.get
                }
                fragment
            }

            withoutTransformerGroup.values.flatten ++ newFragments
        }


        others ++ dsgFragments.flatten
    }

    private def finalize(iterationData: DiscoveryIteration): Future[Seq[Pipeline]] = {
        timer.stop
        onStop.foreach(f => f(this))
        Future.successful(iterationData.pipelines)
    }

    private def getCombinatorInput(iteration: DiscoveryIteration) : Seq[CombinatorInput] = {
        val (extractorCandidatePipelines, otherPipelines) = iteration.fragments.partition(_.endsWithLargeDataset)

        Seq(
            CombinatorInput(extractorCandidatePipelines, iteration.input.extractors),
            CombinatorInput(otherPipelines, iteration.input.processors ++ iteration.input.applications)
        )
    }

    private def getRelevantFragments(component: ComponentInstanceWithInputs, fragments: Seq[Pipeline]) : Seq[Pipeline] = {
        component match {
            case c if c.isInstanceOf[LinksetBasedUnion] => fragments.filterNot(p => p.containsInstance(c) || p.endsWithLargeDataset)
            case c if c.isInstanceOf[FusionTransformer] => fragments.filter(_.containsInstanceOfType[LinksetBasedUnion])
            case e if e.isInstanceOf[ExtractorInstance] => fragments.filter(_.endsWith[DataSourceInstance])
            case _ => fragments
        }
    }

    private def createInitialPipelineFragments(dataSets: Seq[DataSet]): Future[Seq[Pipeline]] = {
        discoveryLogger.trace(s"[$id] Initial pipelines built from $dataSets.")
        Future.sequence(dataSets.map(pipelineBuilder.buildInitialPipeline))
    }

    private def containsBindingToIteration(iterationNumber: Int)(pipeline: Pipeline): Boolean = {
        pipeline.bindings.exists(
            binding => binding.startComponent.discoveryIteration == iterationNumber
        )
    }
}

object Discovery {
    def create(input: DiscoveryInput) : Discovery = {
        val uuid = UUID.randomUUID()
        val builder = new PipelineBuilder(uuid)
        new Discovery(uuid, input)(new DiscoveryPortMatcher(uuid, builder), builder)
    }
}