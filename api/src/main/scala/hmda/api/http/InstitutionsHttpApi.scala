package hmda.api.http

import java.time.Instant

import akka.actor.{ ActorRef, ActorSelection, ActorSystem }
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.pattern.ask
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.StandardRoute
import akka.stream.scaladsl.Framing
import akka.util.{ ByteString, Timeout }
import hmda.api.model._
import hmda.persistence.institutions.FilingPersistence.GetFilingByPeriod
import hmda.persistence.institutions.InstitutionPersistence.{ GetInstitutionById, GetInstitutionsById }
import hmda.persistence.institutions.SubmissionPersistence.{ CreateSubmission, GetLatestSubmission, GetSubmissionById }
import hmda.api.protocol.processing.{ ApiErrorProtocol, InstitutionProtocol }
import hmda.model.fi.{ Created, Filing, Submission, SubmissionId }
import hmda.model.institution.Institution
import hmda.persistence.CommonMessages._
import hmda.persistence.HmdaSupervisor.{ FindFilings, FindSubmissions }
import hmda.persistence.institutions.{ FilingPersistence, SubmissionPersistence }
import hmda.persistence.processing.HmdaRawFile._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

trait InstitutionsHttpApi extends InstitutionProtocol with ApiErrorProtocol with HmdaCustomDirectives {

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  val log: LoggingAdapter

  implicit val timeout: Timeout

  val splitLines = Framing.delimiter(ByteString("\n"), 2048, allowTruncation = true)

  val institutionsPath =
    path("institutions") {
      val path = "institutions"
      val institutionsActor = system.actorSelection("/user/supervisor/institutions")
      timedGet {
        extractRequestContext { ctx =>
          val ids = institutionIdsFromHeader(ctx)
          val fInstitutions = (institutionsActor ? GetInstitutionsById(ids)).mapTo[Set[Institution]]
          onComplete(fInstitutions) {
            case Success(institutions) =>
              val wrappedInstitutions = institutions.map(inst => InstitutionWrapper(inst.id.toString, inst.name, inst.status))
              complete(ToResponseMarshallable(Institutions(wrappedInstitutions)))
            case Failure(error) => completeWithInternalError(path, error)
          }
        }
      }
    }

  def institutionByIdPath(institutionId: String) =
    pathEnd {
      val path = s"institutions/$institutionId"
      extractExecutionContext { executor =>
        val institutionsActor = system.actorSelection("/user/supervisor/institutions")
        timedGet {
          implicit val ec: ExecutionContext = executor
          val supervisor = system.actorSelection("/user/supervisor")
          val fFilingsActor = (supervisor ? FindFilings(FilingPersistence.name, institutionId)).mapTo[ActorRef]
          val fInstitutionDetails = for {
            f <- fFilingsActor
            d <- institutionDetails(institutionId, institutionsActor, f)
          } yield d

          onComplete(fInstitutionDetails) {
            case Success(institutionDetails) =>
              if (institutionDetails.institution.name != "")
                complete(ToResponseMarshallable(institutionDetails))
              else {
                val errorResponse = ErrorResponse(404, s"Institution $institutionId not found", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              }
            case Failure(error) =>
              completeWithInternalError(path, error)
          }
        }
      }
    }

  def filingByPeriodPath(institutionId: String) =
    path("filings" / Segment) { period =>
      val path = s"institutions/$institutionId/filings/$period"
      extractExecutionContext { executor =>
        timedGet {
          implicit val ec: ExecutionContext = executor
          val supervisor = system.actorSelection("/user/supervisor")
          val fFilings = (supervisor ? FindFilings(FilingPersistence.name, institutionId)).mapTo[ActorRef]
          val fSubmissions = (supervisor ? FindSubmissions(SubmissionPersistence.name, institutionId, period)).mapTo[ActorRef]

          val fDetails = for {
            f <- fFilings
            s <- fSubmissions
            d <- filingDetailsByPeriod(period, f, s)
          } yield d

          onComplete(fDetails) {
            case Success(filingDetails) =>
              val filing = filingDetails.filing
              if (filing.institutionId == institutionId && filing.period == period)
                complete(ToResponseMarshallable(filingDetails))
              else if (filing.institutionId == institutionId && filing.period != period) {
                val errorResponse = ErrorResponse(404, s"$period filing not found for institution $institutionId", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              } else {
                val errorResponse = ErrorResponse(404, s"Institution $institutionId not found", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              }
            case Failure(error) =>
              completeWithInternalError(path, error)
          }
        }
      }
    }

  def submissionPath(institutionId: String) =
    path("filings" / Segment / "submissions") { period =>
      val path = s"institutions/$institutionId/filings/$period/submissions"
      extractExecutionContext { executor =>
        timedPost {
          implicit val ec: ExecutionContext = executor
          val supervisor = system.actorSelection("/user/supervisor")
          val fFilingsActor = (supervisor ? FindFilings(FilingPersistence.name, institutionId)).mapTo[ActorRef]
          val fSubmissionsActor = (supervisor ? FindSubmissions(SubmissionPersistence.name, institutionId, period)).mapTo[ActorRef]

          val fFiling = for {
            f <- fFilingsActor
            s <- fSubmissionsActor
            d <- (f ? GetFilingByPeriod(period)).mapTo[Filing]
          } yield (s, d)

          onComplete(fFiling) {
            case Success((submissionsActor, filing)) =>
              if (filing.period == period) {
                submissionsActor ! CreateSubmission
                val fLatest = (submissionsActor ? GetLatestSubmission).mapTo[Submission]
                onComplete(fLatest) {
                  case Success(submission) =>
                    complete(ToResponseMarshallable(StatusCodes.Created -> submission))
                  case Failure(error) =>
                    completeWithInternalError(path, error)
                }
              } else if (!filing.institutionId.isEmpty) {
                val errorResponse = ErrorResponse(404, s"$period filing not found for institution $institutionId", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              } else {
                val errorResponse = ErrorResponse(404, s"Institution $institutionId not found", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              }
            case Failure(error) =>
              completeWithInternalError(path, error)
          }
        }
      }
    }

  def submissionLatestPath(institutionId: String) =
    path("filings" / Segment / "submissions" / "latest") { period =>
      val path = s"institutions/$institutionId/filings/$period/submissions/latest"
      extractExecutionContext { executor =>
        timedGet {
          implicit val ec: ExecutionContext = executor
          val supervisor = system.actorSelection("/user/supervisor")
          val fSubmissionsActor = (supervisor ? FindSubmissions(SubmissionPersistence.name, institutionId, period)).mapTo[ActorRef]

          val fSubmissions = for {
            s <- fSubmissionsActor
            xs <- (s ? GetLatestSubmission).mapTo[Submission]
          } yield xs

          onComplete(fSubmissions) {
            case Success(submission) =>
              if (submission.id.sequenceNumber == 0) {
                val errorResponse = ErrorResponse(404, s"No submission found for $institutionId for $period", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              } else {
                val statusWrapper = SubmissionStatusWrapper(submission.submissionStatus.code, submission.submissionStatus.message)
                val submissionWrapper = SubmissionWrapper(submission.id.sequenceNumber, statusWrapper)
                complete(ToResponseMarshallable(submissionWrapper))
              }
            case Failure(error) =>
              completeWithInternalError(path, error)
          }
        }
      }
    }

  def uploadPath(institutionId: String) =
    path("filings" / Segment / "submissions" / IntNumber) { (period, seqNr) =>
      time {
        val path = s"institutions/$institutionId/filings/$period/submissions/$seqNr"
        val submissionId = SubmissionId(institutionId, period, seqNr)
        extractExecutionContext { executor =>
          val uploadTimestamp = Instant.now.toEpochMilli
          val processingActor = createHmdaRawFile(system, submissionId)
          val submissionsActor = system.actorOf(SubmissionPersistence.props(institutionId, period))
          implicit val ec: ExecutionContext = executor
          val fIsSubmissionOverwrite = checkSubmissionOverwrite(submissionsActor, submissionId)
          onComplete(fIsSubmissionOverwrite) {
            case Success(false) =>
              submissionsActor ! Shutdown
              processingActor ! StartUpload
              fileUpload("file") {
                case (metadata, byteSource) if (metadata.fileName.endsWith(".txt")) =>
                  val uploadedF = byteSource
                    .via(splitLines)
                    .map(_.utf8String)
                    .runForeach(line => processingActor ! AddLine(uploadTimestamp, line))

                  onComplete(uploadedF) {
                    case Success(response) =>
                      processingActor ! CompleteUpload
                      processingActor ! Shutdown
                      complete(ToResponseMarshallable(StatusCodes.Accepted -> "uploaded"))
                    case Failure(error) =>
                      processingActor ! Shutdown
                      log.error(error.getLocalizedMessage)
                      val errorResponse = ErrorResponse(400, "Invalid File Format", path)
                      complete(ToResponseMarshallable(StatusCodes.BadRequest -> errorResponse))
                  }
                case _ =>
                  processingActor ! Shutdown
                  val errorResponse = ErrorResponse(400, "Invalid File Format", path)
                  complete(ToResponseMarshallable(StatusCodes.BadRequest -> errorResponse))
              }
            case Success(true) =>
              val errorResponse = ErrorResponse(400, "Submission already exists", path)
              complete(ToResponseMarshallable(StatusCodes.BadRequest -> errorResponse))
            case Failure(error) =>
              submissionsActor ! Shutdown
              completeWithInternalError(path, error)
          }
        }
      }
    }

  def institutionSummaryPath(institutionId: String) =
    path("summary") {
      val path = s"institutions/$institutionId/summary"
      extractExecutionContext { executor =>
        val institutionsActor = system.actorSelection("/user/supervisor/institutions")
        val filingsActor = system.actorOf(FilingPersistence.props(institutionId))
        implicit val ec = executor
        get {
          val fInstitution = (institutionsActor ? GetInstitutionById(institutionId)).mapTo[Institution]
          val fFilings = (filingsActor ? GetState).mapTo[Seq[Filing]]
          val fSummary = for {
            institution <- fInstitution
            filings <- fFilings
          } yield InstitutionSummary(institution.id.toString, institution.name, filings)

          onComplete(fSummary) {
            case Success(summary) =>
              filingsActor ! Shutdown
              complete(ToResponseMarshallable(summary))
            case Failure(error) =>
              filingsActor ! Shutdown
              completeWithInternalError(path, error)
          }
        }
      }
    }

  private def institutionDetails(institutionId: String, institutionsActor: ActorSelection, filingsActor: ActorRef)(implicit ec: ExecutionContext): Future[InstitutionDetail] = {
    val fInstitution = (institutionsActor ? GetInstitutionById(institutionId)).mapTo[Institution]
    for {
      i <- fInstitution
      filings <- (filingsActor ? GetState).mapTo[Seq[Filing]]
    } yield InstitutionDetail(InstitutionWrapper(i.id.toString, i.name, i.status), filings)
  }

  private def filingDetailsByPeriod(period: String, filingsActor: ActorRef, submissionActor: ActorRef)(implicit ec: ExecutionContext): Future[FilingDetail] = {
    val fFiling = (filingsActor ? GetFilingByPeriod(period)).mapTo[Filing]
    for {
      filing <- fFiling
      submissions <- (submissionActor ? GetState).mapTo[Seq[Submission]]
    } yield FilingDetail(filing, submissions)
  }

  private def checkSubmissionOverwrite(submissionsActor: ActorRef, submissionId: SubmissionId)(implicit ec: ExecutionContext): Future[Boolean] = {
    val submission = (submissionsActor ? GetSubmissionById(submissionId)).mapTo[Submission]
    submission.map(_.submissionStatus != Created)
  }

  private def completeWithInternalError(path: String, error: Throwable): StandardRoute = {
    log.error(error.getLocalizedMessage)
    val errorResponse = ErrorResponse(500, "Internal server error", path)
    complete(ToResponseMarshallable(StatusCodes.InternalServerError -> errorResponse))

  }

  val institutionsRoutes =
    headerAuthorize {
      institutionsPath ~
        pathPrefix("institutions" / Segment) { instId =>
          institutionAuthorize(instId) {
            institutionByIdPath(instId) ~
              institutionSummaryPath(instId) ~
              filingByPeriodPath(instId) ~
              submissionPath(instId) ~
              submissionLatestPath(instId) ~
              uploadPath(instId)
          }
        }
    }
}
