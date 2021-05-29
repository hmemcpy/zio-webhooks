package zio.webhooks

import zio._
import zio.clock._
import zio.prelude.NonEmptySet
import zio.stream.ZStream
import zio.webhooks.WebhookDeliveryBatching._
import zio.webhooks.WebhookError._
import zio.webhooks.WebhookServer._

import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * A [[WebhookServer]] subscribes to webhook events and reliably dispatches them, i.e. failed
 * dispatches are attempted twice followed by exponential backoff. Retries are performed until some
 * duration after which webhooks will be marked `Unavailable` since some [[java.time.Instant]].
 *
 * Batched deliveries are enabled if `batchConfig` is defined.
 *
 * A live server layer is provided in the companion object for convenience and proper resource
 * management.
 */
final case class WebhookServer(
  webhookRepo: WebhookRepo,
  stateRepo: WebhookStateRepo,
  eventRepo: WebhookEventRepo,
  httpClient: WebhookHttpClient,
  batchingQueue: Queue[(Webhook, WebhookEvent)],
  webhookState: Ref[Map[WebhookId, WebhookServer.WebhookState]],
  batchingConfig: Option[BatchingConfig]
) {

  private def consumeBatchElements(
    maxBatchSize: Int,
    maxWaitTime: Duration
  ): URIO[Clock, Unit] =
    ZStream
      .fromQueue(batchingQueue)
      .groupByKey(_._1, maxBatchSize) { (webhook, stream) =>
        stream
          .map(_._2)
          .groupedWithin(maxBatchSize, maxWaitTime)
          .mapM(events => dispatchBatch(webhook, events))
      }
      .runDrain

  private def dispatchBatch(webhook: Webhook, events: Chunk[WebhookEvent]): UIO[Unit] = {
    // TODO: Events may have different headers,
    // TODO: we're just taking the last one's for now.
    // TODO: WebhookEvents' contents are just being appended.
    // TODO: Content is stringly-typed, may need more structure
    val request = WebhookHttpRequest(
      webhook.url,
      events.map(_.content).mkString("\n"),
      events.last.headers
    )
    for {
      _ <- httpClient.post(request).ignore // TODO: handle errors/non-200
      // TODO[design]: should have setEventStatus variant accepting multiple keys
      _ <- ZIO.foreach(events)(event => eventRepo.setEventStatus(event.key, WebhookEventStatus.Delivered).ignore)
      // TODO: enqueue webhook/event errors
    } yield ()
  }

  private def dispatchEvent(webhook: Webhook, event: WebhookEvent) =
    for {
      _ <- eventRepo.setEventStatus(event.key, WebhookEventStatus.Delivering)
      _ <- webhook.batching match {
             case Single  =>
               val request = WebhookHttpRequest(webhook.url, event.content, event.headers)
               httpClient.post(request).ignore *>
                 eventRepo.setEventStatus(event.key, WebhookEventStatus.Delivered) // TODO: handle errors/non-200
             case Batched =>
               batchingQueue.offer((webhook, event))
           }
    } yield ()

  /**
   * Starts the webhook server. Kicks off the following to run concurrently:
   * - new webhook event subscription
   * - event recovery for webhooks which need to deliver at least once
   * - dispatch retry monitoring
   * - batching
   */
  // TODO: add error hook
  // TODO: retry monitoring should just be dispatch
  def start: URIO[Clock, Any] =
    startNewEventSubscription *> startEventRecovery *> startRetryMonitoring *> startBatching

  /**
   * Starts a fiber that listens to events queued for batched webhook dispatch
   */
  private def startBatching: URIO[Clock, Any] =
    batchingConfig match {
      case None                                       =>
        ZIO.unit
      case Some(BatchingConfig(maxSize, maxWaitTime)) =>
        consumeBatchElements(maxSize, maxWaitTime).fork
    }

  // get events that are Delivering whose webhooks have AtLeastOnce delivery semantics.
  // reconstruct webhookState
  /**
   * Starts recovery of events whose status is `Delivering` for webhooks with `AtLeastOnce`
   * delivery semantics.
   */
  private def startEventRecovery: UIO[Any] = ZIO.unit.fork

  // Call webhookEventRepo.getEventsByStatus looking for new events
  //
  // WebhookEventStatus.New
  //
  // For each new event:
  //  - mark event as Delivering
  //  - check to see if webhookId is retrying
  //    - if there are queues, we're retrying
  //      - enqueue the event
  //    - otherwise:
  //      - send it
  //        - if successful
  //          - mark it as Delivered
  //        - if unsuccessful
  //          - create a queue in the state
  //          - enqueue the event into the queue
  //
  /**
   * Kicks off new [[WebhookEvent]] subscription.
   */
  private def startNewEventSubscription: UIO[Any] =
    eventRepo
      .subscribeToEventsByStatuses(NonEmptySet(WebhookEventStatus.New))
      .foreach { newEvent =>
        val webhookId = newEvent.key.webhookId
        for {
          webhook <- webhookRepo
                       .getWebhookById(webhookId)
                       .flatMap(ZIO.fromOption(_).mapError(_ => MissingWebhookError(webhookId)))
          _       <- ZIO.when(webhook.isOnline)(dispatchEvent(webhook, newEvent))
        } yield ()
      }
      .forkAs("new-event-subscription")

  // retry dispatching every WebhookEvent in queues twice, then exponentially
  // if we've retried for >7 days,
  //   mark Webhook as Unavailable
  //   clear WebhookState for that Webhook
  //   make sure that startNewEventSubscription does _not_ try to deliver to an unavailable webhook
  /**
   * Kicks off backoff retries for every [[WebhookEvent]] pending delivery.
   */
  private def startRetryMonitoring: UIO[Any] = ZIO.unit.fork

  /**
   * Waits until all work in progress is finished, then shuts down.
   */
  def shutdown: IO[IOException, Any] = ZIO.unit
}

object WebhookServer {
  // TODO: Smart constructor
  final case class BatchingConfig(maxSize: Int, maxWaitTime: Duration)
  object BatchingConfig {
    val disabled: ULayer[Has[Option[BatchingConfig]]] =
      ZLayer.succeed(None)

    def live(maxSize: Int, maxWaitTime: Duration): ULayer[Has[Option[BatchingConfig]]] =
      ZLayer.succeed(Some(BatchingConfig(maxSize, maxWaitTime)))
  }

  type Env = Has[WebhookRepo]
    with Has[WebhookStateRepo]
    with Has[WebhookEventRepo]
    with Has[WebhookHttpClient]
    with Has[Option[BatchingConfig]]
    with Clock

  val live: URLayer[WebhookServer.Env, Has[WebhookServer]] = {
    for {
      state          <- Ref.makeManaged(Map.empty[WebhookId, WebhookServer.WebhookState])
      batchQueue     <- Queue.bounded[(Webhook, WebhookEvent)](1024).toManaged_
      batchingConfig <- ZManaged.service[Option[BatchingConfig]]
      repo           <- ZManaged.service[WebhookRepo]
      stateRepo      <- ZManaged.service[WebhookStateRepo]
      eventRepo      <- ZManaged.service[WebhookEventRepo]
      httpClient     <- ZManaged.service[WebhookHttpClient]
      server          = WebhookServer(repo, stateRepo, eventRepo, httpClient, batchQueue, state, batchingConfig)
      _              <- server.start.toManaged_
      _              <- ZManaged.finalizer(server.shutdown.orDie)
    } yield server
  }.toLayer

  sealed trait WebhookState
  object WebhookState {
    case object Enabled                                                       extends WebhookState
    case object Disabled                                                      extends WebhookState
    // TODO[high-prio]: replace WebhookEvent with a Dispatch type, as retries should be done on dispatches
    final case class Retrying(sinceTime: Instant, queue: Queue[WebhookEvent]) extends WebhookState
    case object Unavailable                                                   extends WebhookState
  }
}
