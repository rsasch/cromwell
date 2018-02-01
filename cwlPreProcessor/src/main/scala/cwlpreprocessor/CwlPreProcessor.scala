package cwlpreprocessor

import better.files.{File => BFile}
import cats.data.NonEmptyList
import cats.syntax.either._
import common.Checked
import common.validation.Validation._
import common.validation.Checked._
import cwl.command.ParentName
import cwl.{CwlDecoder, FileAndId, FullyQualifiedName}
import cwlpreprocessor.CwlPreProcessor._
import io.circe.Json
import io.circe.optics.JsonPath._
import mouse.all._

object CwlPreProcessor {
  private val LocalScheme = "file://"

  object CwlReference {
    def fromString(in: String) = {
      in.startsWith(LocalScheme).option {
        FullyQualifiedName.maybeApply(in)(ParentName.empty) match {
          case Some(FileAndId(file, _, _)) => CwlReference(BFile(file.stripFilePrefix), in)
          case _ => CwlReference(BFile(in.stripFilePrefix), in)
        }
      }
    }

    def apply(file: BFile, pointer: Option[String]) = {
      // prepends file:// to the absolute file path
      val prefixedFile = s"$LocalScheme${file.pathAsString}"
      val fullReference = pointer.map(p => s"$prefixedFile#$p").getOrElse(prefixedFile)
      new CwlReference(file, fullReference)
    }
  }

  /**
    * Saladed CWLs reference other local CWL "node" using a URI as follow:
    * file:///path/to/file/containing/node.cwl[#pointer_to_node]
    * #pointer_to_node to node is optional, and will specify which workflow or tool is being targeted if the file
    * is a JSON array.
    * @param file: the file containing the referenced node. e.g: File(/path/to/file/containing/node.cwl)
    * @param fullReference: the full reference string as it is found in the saladed json. e.g: "file:///path/to/file/containing/node.cwl#pointer_to_node"
    */
  case class CwlReference(file: BFile, fullReference: String)

  /**
    * A Cwl node that has been processed (saladed and flattened)
    */
  case class ProcessedNode(json: Json)
  case class UnProcessedNode(json: Json)
  case class ProcessResult(processedNode: ProcessedNode, processedDependencies: Map[CwlReference, ProcessedNode])

  val saladCwlFile: BFile => Checked[String] = { file => CwlDecoder.saladCwlFile(file).value.unsafeRunSync() }

  implicit class PrintableJson(val json: Json) extends AnyVal {
    def print = io.circe.Printer.noSpaces.pretty(json)
  }

  implicit class EnhancedCwlId(val id: String) extends AnyVal {
    def asReference: Option[CwlReference] = CwlReference.fromString(id)
    def stripFilePrefix = id.stripPrefix(LocalScheme)
  }
}

class CwlPreProcessor(saladFunction: BFile => Checked[String] = saladCwlFile) {

  def preProcessCwlFile(file: BFile, cwlRoot: Option[String]): Checked[String] = {
    flattenCwlReference(CwlReference(file, cwlRoot), Map.empty) map {
      case (result, _) => result.print
    }
  }

  private def flattenCwlReference(cwlReference: CwlReference, knownReferences: Map[CwlReference, Json]): Checked[(Json, Map[CwlReference, Json])] = {
    for {
      // parse the file containing the reference
      parsed <- saladAndParse(cwlReference.file)
      // Get a Map[CwlReference, Json] from the parsed file. If the file is a JSON object and only contains one node, the map will only have 1 element 
      cwlNodes = mapIdToContent(parsed).toMap
      // The reference node in the file
      referenceNode <- cwlNodes.get(cwlReference).toChecked(s"Cannot find a tool or workflow with ID ${cwlReference.fullReference} in file ${cwlReference.file.pathAsString}")
      // Process the reference node
      processed <- flattenJson(referenceNode, cwlNodes - cwlReference, knownReferences)
    } yield processed
  }

  private def processCwlReference(unProcessedSiblings: Map[CwlReference, Json])
                                 (checkedProcessedReferences: Checked[Map[CwlReference, Json]], cwlReference: CwlReference): Checked[Map[CwlReference, Json]] = {
    checkedProcessedReferences flatMap { processedReferences =>
      // If the reference has already been processed, no need to do anything
      if (processedReferences.contains(cwlReference)) processedReferences.validNelCheck
      else {
        // Otherwise let's see if it's a reference to another node in the same file (what is called sibling here)
        val result: Checked[(Json, Map[CwlReference, Json])] = unProcessedSiblings.get(cwlReference) match {
          // If yes, then flatten this sibling reference, note that we remove ourselves from the list of siblings
          // This is in case there is a cyclic dependency between this reference and its sibling, the sibling will fail to be flattened instead of infinitely looping
          case Some(referencedSibling) => flattenJson(referencedSibling, unProcessedSiblings - cwlReference, processedReferences)
          // If not, simply flatten this new reference  
          case None => flattenCwlReference(cwlReference, processedReferences)
        }

        result map {
          // Return everything we got ("processedReferences", "processed" and whatever was done to process our reference ("newReferences"))
          case (processed, newReferences) => processedReferences ++ newReferences + (cwlReference -> processed)
        }
      }
    }
  }

  private def flattenJson(saladedJson: Json, unProcessedSiblings: Map[CwlReference, Json], processedReferences: Map[CwlReference, Json]): Checked[(Json, Map[CwlReference, Json])] = {
    findRunReferences(saladedJson)
      .foldLeft(processedReferences.validNelCheck)(processCwlReference(unProcessedSiblings)) map { newKnownReferences =>

      val lookupFunction = json: Json => {
        val fromMap = for {
          asString <- json.asString
          reference <- asString.asReference
          embbeddedJson <- newKnownReferences.get(reference)
        } yield embbeddedJson

        fromMap.getOrElse(json)
      }

      root.steps.each.run.json.modify(lookupFunction)(saladedJson) -> newKnownReferences
    }
  }

  /**
    * Salad and parse a string to Json
    */
  private def saladAndParse(file: BFile): Checked[Json] = for {
    saladed <- saladFunction(file)
    saladedJson <- io.circe.parser.parse(saladed).leftMap(error => NonEmptyList.one(error.message))
  } yield saladedJson

  /**
    * Given a json, collects all "steps.run" values that are JSON Strings, and convert them to CwlReferences.
    * Handles a JSON object representing a single CWL "node" (workflow or tool),
    * as well as a (possibly nested) array of CWL nodes.
    * A saladed json is assumed.
    * For instance:
    *
    * [
    *   {
    *     "id": "file:///path/to/workflow/workflow.cwl#my_first_workflow",
    *     ...
    *     "steps": [
    *       {
    *         "run": "file:///path/to/workflow/other_workflow.cwl",
    *         ...
    *       },
    *       {
    *         "run": "file:///path/to/workflow/not_the_same_workflow.cwl#sometool",
    *         ...
    *       }
    *     ]
    *   },
    *   {
    *     "id": "file:///path/to/workflow/workflow.cwl#my_second_workflow",
    *     ...
    *     "steps": [
    *       {
    *         "run": "file:///path/to/workflow/workflow.cwl#my_first_workflow",
    *         ...
    *       }
    *     ]
    *   }
    * ]
    *
    * will return 
    * List(
    *   CwlReference(file:///path/to/workflow/other_workflow.cwl,        "file:///path/to/workflow/other_workflow.cwl")
    *   CwlReference(file:///path/to/workflow/not_the_same_workflow.cwl, "file:///path/to/workflow/not_the_same_workflow.cwl#sometool")
    *   CwlReference(file:///path/to/workflow/workflow.cwl,              "file:///path/to/workflow/workflow.cwl#my_first_workflow")
    * )
    *
    * Note that the second workflow has a step that references the first workflow, in the same file
    */
  private def findRunReferences(json: Json): List[CwlReference] = {
    json.asArray match {
      case Some(cwls) => cwls.toList.flatMap(findRunReferences)
      case _ => root.steps.each.run.string.getAll(json).flatMap(_.asReference).distinct
    }
  }

  /**
    * Given a json, collect all "steps.run" values that are JSON Strings as CwlReferences.
    */
  private def mapIdToContent(json: Json): List[(CwlReference, Json)] = {
    json.asArray match {
      case Some(cwls) => cwls.toList.flatMap(mapIdToContent)
      case None => root.id.string.getOption(json).flatMap(_.asReference).map(_ -> json).toList
    }
  }
}