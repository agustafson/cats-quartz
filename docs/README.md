# cats-quartz
Quarts scheduler library using cats

### Import
```scala
libraryDependencies ++= Seq(
  "com.itv" %% "cats-quartz-core"     % "@CATS_QUARTZ_VERSION@",
  "com.itv" %% "cats-quartz-extruder" % "@CATS_QUARTZ_VERSION@"
)
```

The project uses a quartz scheduler, and as scheduled messages are generated from Quartz they are
decoded and put onto an `fs2.concurrent.Queue`.

#### Components for scheduling jobs:
* a `QuartzTaskScheduler[F[_], A]` which schedules jobs of type `A`
* a `JobDataEncoder[A]` which encodes job data in a map for the given job of type `A`

#### Components for responding to scheduled messages:
* a job factory which is triggered by quartz when a scheduled task occurs and creates messages to put on the queue
* a `JobDecoder[A]` which decodes the incoming message data map into an `A`
* the decoded message is put onto the provided `fs2.concurrent.Queue`


## Usage:

## Create some job types
We need to have a set of types to encode and decode.
The [extruder](https://janstenpickle.github.io/extruder/) project provides the ability to
encode/decode an object as a `Map[String, String]`, which works perfectly for 
putting data into the quartz `JobDataMap`.
```scala mdoc
import com.itv.scheduler.{JobDataEncoder, JobDecoder}
import com.itv.scheduler.extruder.implicits._
import extruder.map._

sealed trait ParentJob
case object ChildObjectJob     extends ParentJob
case class UserJob(id: String) extends ParentJob

object ParentJob {
  implicit val jobDataEncoder: JobDataEncoder[ParentJob] = deriveEncoder[ParentJob]
  implicit val jobDecoder: JobDecoder[ParentJob]         = deriveDecoder[ParentJob]
}
```

### Create a JobFactory
There are 2 options when creating a `CallbackJobFactory`: auto-acked and manually acked messages.

#### Auto-Acked messages
Scheduled jobs from quartz are immediately acked and the resulting message of type `A` is placed on a `Queue[F, A]`.
If the message taken from the queue isn't handled cleanly then the resulting quartz job won't be re-run,
as it has already been marked as successful. 
```scala mdoc
import cats.effect._
import com.itv.scheduler._
import fs2.concurrent.Queue
import scala.concurrent.ExecutionContext

implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

val jobMessageQueue = Queue.unbounded[IO, ParentJob].unsafeRunSync()
val autoAckJobFactory = CatsStreamJobFactory.autoAcking[IO, ParentJob](jobMessageQueue)
```

#### Manually Acked messages
Scheduled jobs are received but only acked with quartz once the handler has completed via an `acker: MessageAcker[F, A]`.

Scheduled jobs from quartz are bundled into a `message: A` and an `acker: MessageAcker[F, A]`.
The items in the queue are each a `Resource[F, A]` which uses the message and acks the message as the `Resource` is `use`d.

Alternatively the lower-level way of handling each message is via a queue of
`AckableMessage[F, A](message: A, acker: MessageAcker[F, A])` items where the message is explicitly acked by the user.

In both cases, the quartz job is only marked as complete once the `acker.complete(result: Either[Throwable, Unit])` is called.
```scala mdoc
// each message is wrapped as a `Resource` which acks on completion
val ackableJobResourceMessageQueue = Queue.unbounded[IO, Resource[IO, ParentJob]].unsafeRunSync()
val ackingResourceJobFactory: AckingQueueJobFactory[IO, Resource, ParentJob] =
  CatsStreamJobFactory.ackingResource(ackableJobResourceMessageQueue)

// each message is wrapped as a `AckableMessage` which acks on completion
val ackableJobMessageQueue = Queue.unbounded[IO, AckableMessage[IO, ParentJob]].unsafeRunSync()
val ackingJobFactory: AckingQueueJobFactory[IO, AckableMessage, ParentJob] =
  CatsStreamJobFactory.acking(ackableJobMessageQueue)
```

### Creating a scheduler
```scala mdoc
import java.util.concurrent.Executors
import com.itv.scheduler.extruder.implicits._

val quartzProperties = QuartzProperties(new java.util.Properties())
val blocker = Blocker.liftExecutorService(Executors.newFixedThreadPool(8))
val schedulerResource: Resource[IO, QuartzTaskScheduler[IO, ParentJob]] =
  QuartzTaskScheduler[IO, ParentJob](blocker, quartzProperties, autoAckJobFactory)
```

### Using the scheduler
```scala mdoc
import java.time.Instant
import org.quartz.{CronExpression, JobKey, TriggerKey}

def scheduleCronJob(scheduler: QuartzTaskScheduler[IO, ParentJob]): IO[Option[Instant]] =
  scheduler.scheduleJob(
    JobKey.jobKey("child-object-job"),
    ChildObjectJob,
    TriggerKey.triggerKey("cron-test-trigger"),
    CronScheduledJob(new CronExpression("* * * ? * *"))
  )

def scheduleSingleJob(scheduler: QuartzTaskScheduler[IO, ParentJob]): IO[Option[Instant]] =
  scheduler.scheduleJob(
    JobKey.jobKey("single-user-job"),
    UserJob("user-123"),
    TriggerKey.triggerKey("scheduled-single-test-trigger"),
    JobScheduledAt(Instant.now.plusSeconds(2))
  )
```
