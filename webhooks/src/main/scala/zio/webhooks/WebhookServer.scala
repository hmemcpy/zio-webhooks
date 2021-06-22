package zio.webhooks

import zio._
import zio.clock.Clock
import zio.duration._
import zio.prelude.NonEmptySet
import zio.stream._
import zio.webhooks.WebhookDeliveryBatching._
import zio.webhooks.WebhookDeliverySemantics._
import zio.webhooks.WebhookError._
import zio.webhooks.WebhookServer._
import zio.webhooks.internal.CountDownLatch

import java.io.IOException
import java.time.Instant

/**
 * A [[WebhookServer]] subscribes to [[WebhookEvent]]s and reliably delivers them, i.e. failed
 * dispatches are retried once, followed by retries with exponential backoff. Retries are performed
 * until some duration after which webhooks will be marked [[WebhookStatus.Unavailable]] since some
 * [[java.time.Instant]]. Dispatches are batched iff a `batchConfig` is defined ''and'' a webhook's
 * delivery batching is [[WebhookDeliveryBatching.Batched]].
 *
 * A live server layer is provided in the companion object for convenience and proper resource
 * management.
 */
final class WebhookServer private (
  private val webhookRepo: WebhookRepo,
  private val eventRepo: WebhookEventRepo,
  private val httpClient: WebhookHttpClient,
  private val config: WebhookServerConfig,
  private val errorHub: Hub[WebhookError],
  private val internalState: SubscriptionRef[InternalState],
  private val batchingQueue: Option[Queue[(Webhook, WebhookEvent)]],
  private val changeQueue: Queue[WebhookState.Change],
  private val startupLatch: CountDownLatch,
  private val shutdownLatch: CountDownLatch
) {

  /**
   * Attempts delivery of a [[WebhookDispatch]] to the webhook receiver. On successful delivery,
   * events are marked [[WebhookEventStatus.Delivered]]. On failure, dispatches from webhooks with
   * at-least-once delivery semantics are enqueued for retrying.
   */
  private def deliver(dispatch: WebhookDispatch): URIO[Clock, Unit] = {
    def changeToRetryState(id: WebhookId, state: InternalState) =
      for {
        instant       <- clock.instant
        _             <- webhookRepo.setWebhookStatus(id, WebhookStatus.Retrying(instant))
        retryingState <- WebhookState.Retrying.make(config.retry.capacity)
        _             <- retryingState.dispatchQueue.offer(dispatch)
        _             <- changeQueue.offer(WebhookState.Change.ToRetrying(id, retryingState.dispatchQueue))
      } yield state.updateWebhookState(id, retryingState)

    def handleAtLeastOnce = {
      val id = dispatch.webhook.id
      internalState.ref.update { internalState =>
        internalState.webhookState.get(id) match {
          case Some(WebhookState.Enabled)               =>
            changeToRetryState(id, internalState)
          case None                                     =>
            changeToRetryState(id, internalState)
          case Some(WebhookState.Retrying(_, queue, _)) =>
            queue.offer(dispatch) *> UIO(internalState)
          case Some(WebhookState.Disabled)              =>
            ??? // TODO: handle, write webhook state change tests
          case Some(WebhookState.Unavailable)           =>
            ??? // TODO: handle, write webhook state change tests
        }
      }
    }

    for {
      response <- httpClient.post(WebhookHttpRequest.fromDispatch(dispatch)).option
      _        <- {
        (dispatch.semantics, response) match {
          case (_, Some(WebhookHttpResponse(200))) =>
            markDone(dispatch)
          case (AtLeastOnce, _)                    =>
            handleAtLeastOnce
          case (AtMostOnce, _)                     =>
            eventRepo.setEventStatusMany(dispatch.events.map(_.key), WebhookEventStatus.Failed)
        }
      }.catchAll(errorHub.publish)
    } yield ()
  }

  private def dispatchNewEvent(webhook: Webhook, event: WebhookEvent): ZIO[Clock, WebhookError, Unit] =
    for {
      _ <- eventRepo.setEventStatus(event.key, WebhookEventStatus.Delivering)
      _ <- (webhook.batching, batchingQueue) match {
             case (Batched, Some(queue)) =>
               queue.offer((webhook, event.copy(status = WebhookEventStatus.Delivering)))
             case _                      =>
               deliver(WebhookDispatch(webhook, NonEmptyChunk(event)))
           }
    } yield ()

  private def doRetry(webhookId: WebhookId, retry: Retry, retryQueue: Queue[Retry]) = {
    val dispatch = retry.dispatch
    for {
      response  <- httpClient.post(WebhookHttpRequest.fromDispatch(dispatch)).option
      nextState <- response match {
                     case Some(WebhookHttpResponse(200)) =>
                       markDone(dispatch) *>
                         internalState.ref.updateAndGet { state =>
                           UIO(state.removeRetry(webhookId, dispatch))
                         }
                     case _                              =>
                       val next = retry.next
                       internalState.ref.updateAndGet { state =>
                         (retry.backoff match {
                           case Some(backoff) =>
                             retryQueue.offer(next).delay(backoff).fork
                           case None          =>
                             retryQueue.offer(next)
                         }) *> UIO(state.setRetry(webhookId, next))
                       }
                   }
    } yield nextState
  }

  private def enqueueRetry(webhookId: WebhookId, retryQueue: Queue[Retry], dispatch: WebhookDispatch) = {
    val retry =
      Retry(
        dispatch,
        backoff = None,
        config.retry.exponentialBase,
        config.retry.exponentialFactor
      )
    (retry.backoff match {
      case None          => retryQueue.offer(retry)
      case Some(backoff) => retryQueue.offer(retry).delay(backoff)
    }) *> internalState.ref.updateAndGet(state => UIO(state.setRetry(webhookId, retry)))
  }

  /**
   * Exposes a way to listen for [[WebhookError]]s, namely missing webhooks or events. This provides
   * clients a way to handle server errors that would otherwise just fail silently.
   */
  def getErrors: UManaged[Dequeue[WebhookError]] =
    errorHub.subscribe

  private def handleNewEvent(dequeue: Dequeue[WebhookEvent]) =
    for {
      raceResult <- dequeue.take raceEither
                      internalState.changes.map(_.isShutdown).takeUntil(identity).runDrain
      _          <- raceResult match {
                      case Left(newEvent) =>
                        val webhookId = newEvent.key.webhookId
                        for {
                          _ <- webhookRepo
                                 .getWebhookById(webhookId)
                                 .flatMap(ZIO.fromOption(_).orElseFail(MissingWebhookError(webhookId)))
                                 .flatMap(webhook => dispatchNewEvent(webhook, newEvent).when(webhook.isAvailable))
                                 .catchAll(errorHub.publish(_).unit)
                        } yield ()
                      case Right(_)       => ZIO.unit
                    }
      isShutdown <- internalState.ref.get.map(_.isShutdown)
    } yield isShutdown

  private def markDone(dispatch: WebhookDispatch) =
    if (dispatch.size == 1)
      eventRepo.setEventStatus(dispatch.head.key, WebhookEventStatus.Delivered)
    else
      eventRepo.setEventStatusMany(dispatch.keys, WebhookEventStatus.Delivered)

  /**
   * Starts the webhook server. The following are run concurrently:
   *
   *   - new webhook event subscription
   *   - event recovery for webhooks with at-least-once delivery semantics
   *   - dispatch retry monitoring
   *   - dispatch batching, if configured and enabled per webhook
   *
   * The server is ready once it signals readiness to accept new events.
   */
  def start: URIO[Clock, Any] =
    for {
      _ <- startEventRecovery
      _ <- startRetryMonitoring
      _ <- startNewEventSubscription
      _ <- startBatching
      _ <- startupLatch.await
    } yield ()

  /**
   * Starts a fiber that listens to events queued for batched webhook dispatch.
   */
  private def startBatching =
    (config.batching, batchingQueue) match {
      case (Some(WebhookServerConfig.Batching(_, maxSize, maxWaitTime)), Some(batchingQueue)) =>
        {
          val getWebhookIdAndContentType = (webhook: Webhook, event: WebhookEvent) =>
            (webhook.id, event.headers.find(_._1.toLowerCase == "content-type"))

          for {
            isShutdown <- internalState.ref.get.map(_.isShutdown)
            shutdown    = internalState.changes.map(_.isShutdown)
            _          <- ZIO.unless(isShutdown) {
                            UStream
                              .fromQueue(batchingQueue)
                              .map(Left(_))
                              .mergeTerminateRight(shutdown.takeUntil(identity).map(Right(_)))
                              .collectLeft
                              .groupByKey(getWebhookIdAndContentType.tupled, maxSize) {
                                case (_, stream) =>
                                  stream
                                    .groupedWithin(maxSize, maxWaitTime)
                                    .map(NonEmptyChunk.fromChunk)
                                    .collectSome
                                    .mapM(events => deliver(WebhookDispatch(events.head._1, events.map(_._2))))
                              }
                              .runDrain *> shutdownLatch.countDown
                          }
          } yield ()
        }.forkAs("batching")
      case _                                                                                  =>
        ZIO.unit
    }

  /**
   * Starts recovery of events with status [[WebhookEventStatus.Delivering]] for webhooks with
   * [[WebhookDeliverySemantics.AtLeastOnce]]. Recovery is done by reconstructing
   * [[WebhookServer.WebhookState]], the server's internal representation of webhooks it handles.
   * This ensures retries are persistent with respect to server restarts.
   */
  private def startEventRecovery: UIO[Unit] = ZIO.unit // rebuild internal state, use WebhookStateRepo to load state

  /**
   * Starts new [[WebhookEvent]] subscription. Takes a latch which succeeds when the server is ready
   * to receive events.
   */
  private def startNewEventSubscription =
    eventRepo
      .getEventsByStatuses(NonEmptySet(WebhookEventStatus.New))
      .use { dequeue =>
        for {
          _          <- dequeue.poll
          _          <- startupLatch.countDown
          isShutdown <- internalState.ref.get.map(_.isShutdown)
          _          <- handleNewEvent(dequeue).uninterruptible.repeatUntil(identity).unless(isShutdown)
          _          <- shutdownLatch.countDown
        } yield ()
      }
      .forkAs("new-event-subscription")

  /**
   * Takes a dispatch from a retry queue and attempts delivery. When successful, dispatch events are
   * marked [[WebhookEventStatus.Delivered]]. If the dispatch fails, is put back into the queue to
   * be retried again.
   *
   * Returns the current queue size.
   */
  private def startRetrying(webhookId: WebhookId, dispatchQueue: Queue[WebhookDispatch]) =
    for {
      retryQueue    <- Queue.bounded[Retry](1)
      dispatchFiber <- UStream
                         .fromQueue(dispatchQueue) // TODO: merge with shutdown changes to stop
                         .mapM(dispatch => enqueueRetry(webhookId, retryQueue, dispatch).uninterruptible)
                         .runDrain
                         .fork
      retryFiber    <- UStream
                         .fromQueue(retryQueue)
                         .mapM(retry => doRetry(webhookId, retry, retryQueue).uninterruptible)
                         .takeUntil(
                           _.webhookState
                             .get(webhookId)
                             .collect { case WebhookState.Retrying(_, _, retries) => retries.size }
                             .exists(_ == 0)
                         )
                         .runDrain
                         .fork
      _             <- retryFiber.join *> dispatchFiber.interrupt
    } yield ()

  /**
   * Starts retries on a webhook's dispatch queue. Retries until the retry map is exhausted. If
   * retrying times out, the webhook is set to [[WebhookStatus.Unavailable]] and all its events are
   * marked [[WebhookEventStatus.Failed]].
   */
  private def startRetryMonitoring =
    UStream
      .fromQueue(changeQueue)
      .foreach {
        case WebhookState.Change.ToRetrying(id, dispatchQueue) =>
          (for {
            done      <- startRetrying(id, dispatchQueue).timeoutTo(false)(_ => true)(config.retry.timeout)
            newStatus <- if (done)
                           ZIO.succeed(WebhookStatus.Enabled)
                         else
                           clock.instant.map(WebhookStatus.Unavailable) <& eventRepo.setAllAsFailedByWebhookId(id)
            _         <- webhookRepo.setWebhookStatus(id, newStatus)
            _         <- internalState.ref.update { state =>
                           UIO(state.updateWebhookState(id, WebhookState.from(newStatus)))
                         }
          } yield ()).catchAll(errorHub.publish).fork
      }
      .forkAs("retry-monitoring")

  // let in-flight retry requests finish (mark in-flight requests uninterruptible)
  // persist retry state for each webhook
  /**
   * Waits until all work in progress is finished, then shuts down.
   */
  def shutdown: IO[IOException, Any] =
    for {
      _ <- internalState.ref.update(state => UIO(state.shutdown))
      _ <- shutdownLatch.await
    } yield ()
}

object WebhookServer {

  /**
   * Creates a server, pulling dependencies from the environment while initializing internal state.
   */
  def create: URIO[Env, WebhookServer] =
    for {
      serverConfig  <- ZIO.service[WebhookServerConfig]
      webhookRepo   <- ZIO.service[WebhookRepo]
      eventRepo     <- ZIO.service[WebhookEventRepo]
      httpClient    <- ZIO.service[WebhookHttpClient]
      state         <- SubscriptionRef.make(InternalState(isShutdown = false, Map.empty))
      errorHub      <- Hub.sliding[WebhookError](serverConfig.errorSlidingCapacity)
      batchingQueue <- ZIO
                         .foreach(serverConfig.batching) { batching =>
                           Queue.bounded[(Webhook, WebhookEvent)](batching.capacity)
                         }
      changeQueue   <- Queue.bounded[WebhookState.Change](1)
      // start sync point: new event sub
      startupLatch  <- CountDownLatch.make(1)
      // shutdown sync points: new event sub + optional batching
      latchCount     = 1 + serverConfig.batching.fold(0)(_ => 1)
      shutdownLatch <- CountDownLatch.make(latchCount)
    } yield new WebhookServer(
      webhookRepo,
      eventRepo,
      httpClient,
      serverConfig,
      errorHub,
      state,
      batchingQueue,
      changeQueue,
      startupLatch,
      shutdownLatch
    )

  type Env = Has[WebhookRepo]
    with Has[WebhookStateRepo]
    with Has[WebhookEventRepo]
    with Has[WebhookHttpClient]
    with Has[WebhookServerConfig]
    with Clock

  def getErrors: URManaged[Has[WebhookServer], Dequeue[WebhookError]] =
    ZManaged.service[WebhookServer].flatMap(_.getErrors)

  private[webhooks] final case class InternalState(isShutdown: Boolean, webhookState: Map[WebhookId, WebhookState]) {
    def removeRetry(id: WebhookId, dispatch: WebhookDispatch): InternalState =
      copy(webhookState = webhookState.updatedWith(id)(_.map(_.removeRetry(dispatch)))) // TODO: scala 2.12.x equivalent

    def setRetry(id: WebhookId, retry: Retry): InternalState =
      copy(webhookState = webhookState.updatedWith(id)(_.map(_.setRetry(retry)))) // TODO: scala 2.12.x equivalent

    def shutdown: InternalState = copy(isShutdown = true)

    def updateWebhookState(id: WebhookId, newWebhookState: WebhookState): InternalState =
      copy(webhookState = webhookState.updated(id, newWebhookState))
  }

  /**
   * Creates a server, ensuring shutdown on release.
   */
  val live: URLayer[WebhookServer.Env, Has[WebhookServer]] = {
    for {
      server <- WebhookServer.create.toManaged_
      _      <- server.start.toManaged_
      _      <- ZManaged.finalizer(server.shutdown.orDie)
    } yield server
  }.toLayer

  private[webhooks] final case class Retry(
    dispatch: WebhookDispatch,
    backoff: Option[Duration],
    base: Duration,
    power: Double,
    attempt: Int = 0
  ) {
    def next: Retry =
      copy(
        backoff = backoff.map(_ => Some(base * math.pow(2, attempt.toDouble))).getOrElse(Some(base)),
        attempt = attempt + 1
      )
  }

  def shutdown: ZIO[Has[WebhookServer], IOException, Any] =
    ZIO.serviceWith(_.shutdown)

  /**
   * [[WebhookState]] is the server's internal representation of a webhook's state.
   */
  private[webhooks] sealed trait WebhookState extends Product with Serializable { self =>
    final def setRetry(retry: Retry): WebhookState =
      self match {
        case retrying @ WebhookState.Retrying(_, _, retries) =>
          retrying.copy(retries = retries + (retry.dispatch -> retry))
        case _                                               =>
          self
      }

    final def removeRetry(dispatch: WebhookDispatch): WebhookState =
      self match {
        case retrying @ WebhookState.Retrying(_, _, retries) =>
          retrying.copy(retries = retries - dispatch)
        case _                                               =>
          self
      }
  }

  private[webhooks] object WebhookState {
    sealed trait Change
    object Change {
      final case class ToRetrying(id: WebhookId, queue: Queue[WebhookDispatch]) extends Change
    }

    case object Disabled extends WebhookState

    case object Enabled extends WebhookState

    val from: PartialFunction[WebhookStatus, WebhookState] = {
      case WebhookStatus.Enabled        => WebhookState.Enabled
      case WebhookStatus.Disabled       => WebhookState.Disabled
      case WebhookStatus.Unavailable(_) => WebhookState.Unavailable
    }

    final case class Retrying(
      sinceTime: Instant,
      dispatchQueue: Queue[WebhookDispatch],
      retries: Map[WebhookDispatch, Retry] = Map.empty
    ) extends WebhookState

    object Retrying {
      def make(capacity: Int): URIO[Clock, Retrying] =
        ZIO.mapN(clock.instant, Queue.bounded[WebhookDispatch](capacity))(Retrying(_, _))
    }

    case object Unavailable extends WebhookState
  }
}
