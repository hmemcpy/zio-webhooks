package zio.webhooks.example

import zhttp.http._
import zhttp.service.Server
import zio._
import zio.console._
import zio.duration._
import zio.magic._
import zio.stream.UStream
import zio.webhooks._
import zio.webhooks.backends.sttp.WebhookSttpClient
import zio.webhooks.testkit._

/**
 * Differs from the [[BasicExample]] in that events are batched with the default batching setting
 * of 128 elements per batch. The server dispatches all events queued up for each webhook since the
 * last delivery and sends them in a batch.
 */
object BasicExampleWithBatching extends App {

  private val httpApp = HttpApp.collectM {
    case request @ Method.POST -> Root / "endpoint" =>
      for {
        randomDelay <- random.nextIntBetween(10, 20).map(_.millis)
        response    <- ZIO
                         .foreach(request.getBodyAsString) { str =>
                           putStrLn(s"""SERVER RECEIVED PAYLOAD: "$str"""")
                         }
                         .as(Response.status(Status.OK))
                         .delay(randomDelay)
      } yield response
  }

  // just an alias for a zio-http server to disambiguate it with the webhook server
  private lazy val httpEndpointServer = Server

  private lazy val n       = 10000L
  private lazy val nEvents = UStream
    .iterate(0L)(_ + 1)
    .map { i =>
      WebhookEvent(
        WebhookEventKey(WebhookEventId(i), webhook.id),
        WebhookEventStatus.New,
        s"""{"payload":$i}""",
        Chunk(("Accept", "*/*"), ("Content-Type", "application/json"))
      )
    }
    .take(n)

  private lazy val port = 8080

  private def program =
    for {
      _ <- httpEndpointServer.start(port, httpApp).fork
      _ <- WebhookServer.getErrors.use(UStream.fromQueue(_).map(_.toString).foreach(putStrLnErr(_))).fork
      _ <- TestWebhookRepo.createWebhook(webhook)
      _ <- nEvents.schedule(Schedule.spaced(100.micros).jittered).foreach(TestWebhookEventRepo.createEvent)
    } yield ()

  def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    program
      .injectCustom(
        TestWebhookRepo.test,
        TestWebhookStateRepo.test,
        TestWebhookEventRepo.test,
        WebhookSttpClient.live,
        WebhookServerConfig.defaultWithBatching,
        WebhookServer.live
      )
      .exitCode

  // Delivery mode is set to Batched
  private lazy val webhook = Webhook(
    id = WebhookId(0),
    url = s"http://0.0.0.0:$port/endpoint",
    label = "test webhook",
    WebhookStatus.Enabled,
    WebhookDeliveryMode.BatchedAtMostOnce
  )
}
