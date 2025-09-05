import cats.effect.std.{Console, Random}
import cats.effect.{IO, IOApp}
import pureconfig.ConfigSource

object Main extends IOApp.Simple:
  given Console[IO] = Console.make[IO]

  val config: ApplicationConfiguration = ConfigSource.default.loadOrThrow[ApplicationConfiguration]

  val run: IO[Unit] = Random.scalaUtilRandom[IO].flatMap { random =>
    given Random[IO] = random
    Server.run[IO](config)
  }

  override protected def blockedThreadDetectionEnabled = false