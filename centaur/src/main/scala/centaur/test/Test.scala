package centaur.test

import java.util.UUID

import cats.Monad
import cats.effect.IO
import cats.instances.list._
import cats.syntax.traverse._
import centaur._
import centaur.api.CentaurCromwellClient
import centaur.api.CentaurCromwellClient.LogFailures
import centaur.test.metadata.WorkflowMetadata
import centaur.test.submit.SubmitHttpResponse
import centaur.test.workflow.Workflow
import com.google.api.services.genomics.Genomics
import com.google.auth.Credentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.compute.{Compute, ComputeOptions}
import com.google.cloud.storage.{Storage, StorageOptions}
import com.typesafe.config.Config
import common.validation.Validation._
import configs.syntax._
import cromwell.api.CromwellClient.UnsuccessfulRequestException
import cromwell.api.model.{CallCacheDiff, Failed, SubmittedWorkflow, TerminalStatus, WorkflowId, WorkflowStatus}
import cromwell.cloudsupport.gcp.GoogleConfiguration
import cromwell.cloudsupport.gcp.auth.GoogleAuthMode
import spray.json.JsString

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}

/**
  * A simplified riff on the final tagless pattern where the interpreter (monad & related bits) are fixed. Operation
  * functions create an instance of a Test and override the run method to do their bidding. It is unlikely that you
  * should be modifying Test directly, instead most likely what you're looking to do is add a function to the Operations
  * object below
  */
sealed abstract class Test[A] {
  def run: IO[A]
}

object Test {
  def successful[A](value: A): Test[A] = testMonad.pure(value)
  def failed[A](exception: Exception) = new Test[A] {
    override def run = IO.raiseError(exception)
  }

  implicit val testMonad: Monad[Test] = new Monad[Test] {
    override def flatMap[A, B](fa: Test[A])(f: A => Test[B]): Test[B] = {
      new Test[B] {
        override def run: IO[B] = fa.run flatMap { f(_).run }
      }
    }

    override def pure[A](x: A): Test[A] = {
      new Test[A] {
        override def run: IO[A] = IO.pure(x)
      }
    }

    /** Call the default non-stack-safe but correct version of this method. */
    override def tailRecM[A, B](a: A)(f: (A) => Test[Either[A, B]]): Test[B] = {
      flatMap(f(a)) {
        case Right(b) => pure(b)
        case Left(nextA) => tailRecM(nextA)(f)
      }
    }
  }
}

/**
  * Defines functions which are building blocks for test formulas. Each building block is expected to perform
  * a single task and these tasks can be composed together to form arbitrarily complex test strategies. For instance
  * submitting a workflow, polling until a status is reached, retrieving metadata, verifying some value, delaying for
  * N seconds - these would all be operations.
  *
  * All operations are expected to return a Test type and implement the run method. These can then
  * be composed together via a for comprehension as a test formula and then run by some other entity.
  */
object Operations {
  lazy val configuration: GoogleConfiguration = GoogleConfiguration(CentaurConfig.conf)
  lazy val googleConf: Config = CentaurConfig.conf.getConfig("google")
  lazy val authName: String = googleConf.getString("auth")
  lazy val genomicsEndpointUrl: String = googleConf.getString("genomics.endpoint-url")
  lazy val credentials: Credentials = configuration.auth(authName).toTry.get.credential(Map.empty)
  lazy val credentialsProjectOption: Option[String] = {
    Option(credentials) collect {
      case serviceAccountCredentials: ServiceAccountCredentials => serviceAccountCredentials.getProjectId
    }
  }
  lazy val confProjectOption: Option[String] = googleConf.get[Option[String]]("project") valueOrElse None
  // The project from the config or from the credentials. By default the project is read from the system environment.
  lazy val projectOption: Option[String] = confProjectOption orElse credentialsProjectOption

  lazy val genomics: Genomics = {
    val builder = new Genomics.Builder(
      GoogleAuthMode.httpTransport,
      GoogleAuthMode.jsonFactory,
      new HttpCredentialsAdapter(credentials)
    )
    builder
      .setApplicationName(configuration.applicationName)
      .setRootUrl(genomicsEndpointUrl)
      .build()
  }

  lazy val compute: Compute = {
    val builder = ComputeOptions.newBuilder().setCredentials(credentials)
    projectOption foreach builder.setProjectId
    val computeOptions = builder.build()
    computeOptions.getService
  }

  lazy val storage: Storage = {
    val builder = StorageOptions.newBuilder().setCredentials(credentials)
    projectOption foreach builder.setProjectId
    val storageOptions = builder.build()
    storageOptions.getService
  }

  def submitWorkflow(workflow: Workflow): Test[SubmittedWorkflow] = {
    new Test[SubmittedWorkflow] {
      override def run: IO[SubmittedWorkflow] = CentaurCromwellClient.submit(workflow)
    }
  }

  def submitInvalidWorkflow(workflow: Workflow): Test[SubmitHttpResponse] = {
    new Test[SubmitHttpResponse] {
      override def run: IO[SubmitHttpResponse] = {
        CentaurCromwellClient.submit(workflow).redeemWith({
          case unsuccessfulRequestException: UnsuccessfulRequestException =>
            val httpResponse = unsuccessfulRequestException.httpResponse
            val statusCode = httpResponse.status.intValue()
            val message = httpResponse.entity match {
              case akka.http.scaladsl.model.HttpEntity.Strict(_, data) => data.utf8String
              case _ =>
                throw new RuntimeException(s"Expected a strict http response entity but got ${httpResponse.entity}")
            }
            IO.pure(SubmitHttpResponse(statusCode, message))

          case unexpected => IO.raiseError(unexpected)
        },
          submittedWorkflow =>
            IO.raiseError(new RuntimeException(
              s"Expected a failure but got a successfully submitted workflow with id ${submittedWorkflow.id}"))
        )
      }
    }
  }

  def abortWorkflow(workflow: SubmittedWorkflow) = {
    new Test[WorkflowStatus] {
      override def run: IO[WorkflowStatus] = CentaurCromwellClient.abort(workflow)
    }
  }

  def waitFor(duration: FiniteDuration) = {
    new Test[Unit] {
      override def run = IO.sleep(duration)
    }
  }

  /**
    * Polls until a specific status is reached. If a terminal status which wasn't expected is returned, the polling
    * stops with a failure.
    */
  def pollUntilStatus(workflow: SubmittedWorkflow, testDefinition: Workflow, expectedStatus: WorkflowStatus): Test[SubmittedWorkflow] = {
    new Test[SubmittedWorkflow] {
      def status: IO[SubmittedWorkflow] = {
        for {
          workflowStatus <- CentaurCromwellClient.status(workflow)
          mappedStatus <- workflowStatus match {
            case s if s == expectedStatus => IO.pure(workflow)
            case s: TerminalStatus => IO.raiseError(new Exception(s"Unexpected terminal status $s but was waiting for $expectedStatus"))
            case _ => for {
              _ <- IO.sleep(10.seconds)
              s <- status
            } yield s
          }
        } yield mappedStatus
      }


      override def run: IO[SubmittedWorkflow] = status.timeout(CentaurConfig.maxWorkflowLength)
    }
  }

  /**
    * Validate that the given jobId matches the one in the metadata
    */
  def validateRecovered(workflow: SubmittedWorkflow, callFqn: String, formerJobId: String): Test[Unit] = {
    new Test[Unit] {
      override def run: IO[Unit] = CentaurCromwellClient.metadata(workflow) flatMap { s =>
        s.value.get(s"calls.$callFqn.jobId") match {
          case Some(newJobId) if newJobId.asInstanceOf[JsString].value == formerJobId => IO.unit
          case Some(_) => IO.raiseError(new Exception("Pre-restart job ID did not match post restart job ID"))
          case _ => IO.raiseError(new Exception("Cannot find a post restart job ID"))
        }
      }
    }
  }

  def validatePAPIAborted(jobId: String, workflow: SubmittedWorkflow): Test[Unit] = {
    new Test[Unit] {
      def checkPAPIAborted(): IO[Unit] = {
        for {
          operation <- IO { genomics.operations().get(jobId).execute() }
          done = operation.getDone
          operationError = Option(operation.getError)
          aborted = operationError.exists(_.getCode == 1) && operationError.exists(_.getMessage.startsWith("Operation canceled"))
          result <- if (!(done && aborted)) {
            IO.raiseError(new Exception(s"Underlying JES job was not aborted properly. Done = $done. Error = ${operationError.map(_.getMessage).getOrElse("N/A")}"))
          } else IO.unit
        } yield result
      }

      override def run: IO[Unit] = if (jobId.startsWith("operations/")) {
        checkPAPIAborted()
      } else IO.unit
    }
  }

  /**
    * Polls until a specific call is in Running state. Returns the job id.
    */
  def pollUntilCallIsRunning(workflow: SubmittedWorkflow, callFqn: String): Test[String] = {
    // Special case for sub workflow testing
    def findJobIdInSubWorkflow(subWorkflowId: String): IO[Option[String]] = {
      for {
        metadata <- CentaurCromwellClient
          .metadata(WorkflowId.fromString(subWorkflowId))
          .redeem(_ => None, Option.apply)
        jobId <- IO.pure(metadata.flatMap(_.value.get("calls.inner_abort.aborted.jobId")))
      } yield jobId.map(_.asInstanceOf[JsString].value)
    }

    def valueAsString(key: String, metadata: WorkflowMetadata) = {
      metadata.value.get(key).map(_.asInstanceOf[JsString].value)
    }

    def findCallStatus(metadata: WorkflowMetadata): IO[Option[(String, String)]] = {
      for {
        status <- IO.pure(metadata.value.get(s"calls.$callFqn.executionStatus"))
        statusString = status.map(_.asInstanceOf[JsString].value)
        jobId <- valueAsString(s"calls.$callFqn.jobId", metadata).map(jobId => IO.pure(Option(jobId)))
          .orElse(
            valueAsString(s"calls.$callFqn.subWorkflowId", metadata).map(findJobIdInSubWorkflow)
          ).getOrElse(IO.pure(None))
        pair = (statusString, jobId) match {
          case (Some(s), Some(j)) => Option(s -> j)
          case _ => None
        }
      } yield pair
    }

    new Test[String] {
      def doPerform: IO[String] = {
        val metadata = for {
          // We don't want to keep going forever if the workflow failed
          status <- CentaurCromwellClient.status(workflow)
          _ <- status match {
            case Failed => IO.raiseError(new Exception("Workflow Failed"))
            case _ => IO.unit
          }
          metadata <- CentaurCromwellClient.metadata(workflow)
        } yield metadata

        for {
          md <- metadata
          callStatus <- findCallStatus(md)
          result <- callStatus match {
            case Some(("Running", jobId)) => IO.pure(jobId)
            case Some(("Failed", _)) => IO.raiseError(new Exception(s"$callFqn failed"))
            case _ => for {
              _ <- IO.sleep(5.seconds)
              recurse <- doPerform
            } yield recurse
          }
        } yield result
      }

      override def run: IO[String] = doPerform.timeout(CentaurConfig.maxWorkflowLength)
    }
  }
  
  def printHashDifferential(workflowA: SubmittedWorkflow, workflowB: SubmittedWorkflow) = new Test[Unit] {
    def hashDiffOfAllCalls = {
      // Extract the workflow name followed by call name to use in the call cache diff endpoint
      val callNameRegexp = """calls\.([^.]*\.[^.]*)\..*""".r

      for {
        md <- CentaurCromwellClient.metadata(workflowB)
        calls = md.value.keySet.flatMap({
          case callNameRegexp(name) => Option(name)
          case _ => None
        })
        diffs <- calls.toList.traverse[IO, CallCacheDiff]({ callName =>
          CentaurCromwellClient.callCacheDiff(workflowA, callName, workflowB, callName)
        })
      } yield diffs.flatMap(_.hashDifferential)
    }
    
    override def run = {
      hashDiffOfAllCalls map {
        case diffs if diffs.nonEmpty && CentaurCromwellClient.LogFailures =>
          Console.err.println(s"Hash differential for ${workflowA.id} and ${workflowB.id}")
          diffs.map({ diff =>
            s"For key ${diff.hashKey}:\nCall A: ${diff.callA.getOrElse("N/A")}\nCall B: ${diff.callB.getOrElse("N/A")}"
          }).foreach(Console.err.println)
        case _ =>
      }
    }
  }

  def validateMetadata(submittedWorkflow: SubmittedWorkflow, workflowSpec: Workflow, cacheHitUUID: Option[UUID] = None): Test[WorkflowMetadata] = {
    new Test[WorkflowMetadata] {
      def eventuallyMetadata(workflow: SubmittedWorkflow, expectedMetadata: WorkflowMetadata): IO[WorkflowMetadata] = {
        validateMetadata(workflow, expectedMetadata).handleErrorWith({_ =>
          for {
            _ <- IO.sleep(2.seconds)
            _ = if (LogFailures) Console.err.println(s"Metadata mismatch for ${submittedWorkflow.id} - retrying")
            recurse <- eventuallyMetadata(workflow, expectedMetadata)
          } yield recurse
        })
      }
      
      def validateMetadata(workflow: SubmittedWorkflow, expectedMetadata: WorkflowMetadata): IO[WorkflowMetadata] = {
        def checkDiff(diffs: Iterable[String]): IO[Unit] = {
          diffs match {
            case d if d.nonEmpty => IO.raiseError(new Exception(s"Invalid metadata response:\n -${d.mkString("\n -")}\n"))
            case _ => IO.unit
          }
        }
        cleanUpImports(workflow)

        def validateUnwantedMetadata(actualMetadata: WorkflowMetadata) = if (workflowSpec.notInMetadata.nonEmpty) {
          // Check that none of the "notInMetadata" keys are in the actual metadata
          val absentMdIntersect = workflowSpec.notInMetadata.toSet.intersect(actualMetadata.value.keySet)
          if (absentMdIntersect.nonEmpty) IO.raiseError(new Exception(s"Found unwanted keys in metadata: ${absentMdIntersect.mkString(", ")}"))
          else IO.unit
        } else IO.unit

        for {
          actualMetadata <- CentaurCromwellClient.metadata(workflow)
          _ <- validateUnwantedMetadata(actualMetadata)
          diffs = expectedMetadata.diff(actualMetadata, workflow.id.id, cacheHitUUID)
          _ <- checkDiff(diffs)
        } yield actualMetadata
      }
      
      override def run: IO[WorkflowMetadata] = workflowSpec.metadata match {
        case Some(expectedMetadata) =>
          eventuallyMetadata(submittedWorkflow, expectedMetadata)
            .timeoutTo(CentaurConfig.metadataConsistencyTimeout, validateMetadata(submittedWorkflow, expectedMetadata))
        // Nothing to wait for, so just return the first metadata we get back:
        case None => CentaurCromwellClient.metadata(submittedWorkflow)
      }
    }
  }
  
  /**
    * Verify that none of the calls within the workflow are cached.
    */
  def validateCacheResultField(metadata: WorkflowMetadata, workflowName: String, blacklistedValue: String): Test[Unit] = {
    new Test[Unit] {
      override def run: IO[Unit] = {
        val badCacheResults = metadata.value collect {
          case (k, JsString(v)) if k.contains("callCaching.result") && v.contains(blacklistedValue) => s"$k: $v"
        }

        if (badCacheResults.isEmpty) IO.unit
        else IO.raiseError(new Exception(s"Found unexpected cache hits for $workflowName:${badCacheResults.mkString("\n", "\n", "\n")}"))
      }
    }
  }

  def validateDirectoryContentsCounts(workflowDefinition: Workflow, submittedWorkflow: SubmittedWorkflow): Test[Unit] = new Test[Unit] {
    private val workflowId = submittedWorkflow.id.id.toString
    override def run: IO[Unit] = workflowDefinition.directoryContentCounts match {
      case None => IO.unit
      case Some(directoryContentCountCheck) =>
        val counts = directoryContentCountCheck.expectedDrectoryContentsCounts map {
          case (directory, count) =>
            val substitutedDir = directory.replaceAll("<<UUID>>", workflowId)
            (substitutedDir, count, directoryContentCountCheck.checkFiles.countObjectsAtPath(substitutedDir))
        }

        val badCounts = counts collect {
          case (directory, expectedCount, actualCount) if expectedCount != actualCount => s"Expected to find $expectedCount item(s) at $directory but got $actualCount"
        }
        if (badCounts.isEmpty) IO.unit else IO.raiseError(new Exception(badCounts.mkString("\n", "\n", "\n")))
    }
  }

  def validateNoCacheHits(metadata: WorkflowMetadata, workflowName: String): Test[Unit] = validateCacheResultField(metadata, workflowName, "Cache Hit")
  def validateNoCacheMisses(metadata: WorkflowMetadata, workflowName: String): Test[Unit] = validateCacheResultField(metadata, workflowName, "Cache Miss")

  def validateSubmitFailure(workflow: Workflow,
                            expectedSubmitResponse: SubmitHttpResponse,
                            actualSubmitResponse: SubmitHttpResponse): Test[Unit] = {
    new Test[Unit] {
      override def run: IO[Unit] = {
        if (expectedSubmitResponse == actualSubmitResponse) {
          IO.unit
        } else {
          IO.raiseError(
            new RuntimeException(
              s"""|
                  |Expected
                  |$expectedSubmitResponse
                  |
                  |but got:
                  |$actualSubmitResponse
                  |""".stripMargin
            )
          )
        }
      }
    }
  }

  /**
    * Clean up temporary zip files created for Imports testing.
    */
  def cleanUpImports(submittedWF: SubmittedWorkflow) = {
    submittedWF.workflow.zippedImports match {
      case Some(zipFile) => zipFile.delete(swallowIOExceptions = true)
      case None => //
    }
  }

  // FIXME: Should be abstracted w/ validateMetadata - ATM still used by the unused caching tests
  def retrieveMetadata(workflow: SubmittedWorkflow): Test[WorkflowMetadata] = {
    new Test[WorkflowMetadata] {
      override def run: IO[WorkflowMetadata] = CentaurCromwellClient.metadata(workflow)
    }
  }
}
