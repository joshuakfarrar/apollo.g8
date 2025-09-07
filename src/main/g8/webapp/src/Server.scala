import cats.Monad
import cats.arrow.FunctionK
import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.{Console, Random}
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import doobie.{Get, Put, Transactor}
import fs2.io.net.Network
import me.joshuakfarrar.apollo.auth.*
import mg.Mailgun
import models.User
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.{CSRF, ErrorAction, ErrorHandling, Logger}
import org.http4s.server.staticcontent.webjarServiceBuilder
import org.http4s.{HttpRoutes, Response, Status, Uri}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.vault.Key

import java.util.UUID

object Server:

  private type UserId = UUID

  // type class instances for User domain object
  given HasId[User, UserId] = _.id
  given HasPassword[User] = _.password
  given HasEmail[User] = _.email

  // database mapping instances for UUID type (Doobie needs this for IDs)
  given Get[UserId] = Get[String].map(UUID.fromString)
  given Put[UserId] = Put[String].contramap(_.toString)

  private val banner = Seq(
    """
      |     ..                                             ..       ..
      |  :**888H: `: .xH""                           x .d88"  x .d88"
      | X   `8888k XX888    .d``                u.    5888R    5888R          u.
      |'8hx  48888 ?8888    @8Ne.   .u    ...ue888b   '888R    '888R    ...ue888b
      |'8888 '8888 `8888    %8888:u@88N   888R Y888r   888R     888R    888R Y888r
      | %888>'8888  8888     `888I  888.  888R I888>   888R     888R    888R I888>
      |   "8 '888"  8888      888I  888I  888R I888>   888R     888R    888R I888>
      |  .-` X*"    8888      888I  888I  888R I888>   888R     888R    888R I888>
      |    .xhx.    8888    uW888L  888' u8888cJ888    888R     888R   u8888cJ888
      |  .H88888h.~`8888.> '*88888Nu88P   "*888*P"    .888B .  .888B .  "*888*P"
      | .~  `%88!` '888*~  ~ '88888F`       'Y"       ^*888%   ^*888%     'Y"
      |       `"     ""       888 ^                     "%       "%
      |                       *8E
      |                       '8>
      |                        """".stripMargin
  )

  def run[F[_] : Async : Network](
      config: ApplicationConfiguration
  )(using C: Console[F], F: Monad[F], R: Random[F]): F[Nothing] = {

    for {
      _ <- Resource.eval(
        defaultLogger[F].info(banner.mkString(System.lineSeparator))
      )

      // csrf stuff
      cookieName = "csrf-token"
      csrfField = "_csrf"
      key  <- Resource.eval(CSRF.generateSigningKey[F]())
      csrfTokenKey <- Resource.eval(Key.newKey[F, String])

      csrf = CSRF.withDefaultOriginCheckFormAware[F, F](
          csrfField,           // fieldName - the form field name to look for
          FunctionK.id[F]      // nt: F ~> F (natural transformation, use identity)
        )(
          key,
          "localhost",         // host
          Uri.Scheme.http,     // scheme
          Some(8080)           // port
        )
        .withCookieName(cookieName)
        .withCookieDomain(Some("localhost"))
        .withCookiePath(Some("/"))
        .build

      // services
      xa = getTransactor[F](config)
      userService = UserService.impl[F, User, UserId](xa)

      mailService = new MailService[F, Mailgun.Email, Unit] {
        val mailgun = new Mailgun(
          domain = Uri
            .fromString(Mailgun.uri(config.mailgunDomain))
            .fold(throw _, identity),
          apiKey = config.mailgunKey
        )

        override def confirmationEmail(to: String, code: String): Mailgun.Email = Mailgun.Email(
          Mailgun.EmailAddress(config.mailgunSender),
          Mailgun.EmailAddress(to),
          "Confirm your account",
          Some(s"Confirm your account (text): http://localhost:8080/confirm/\$code"),
          Some(s"Confirm your account (html): http://localhost:8080/confirm/\$code")
        )

        override def resetEmail(to: String, code: String): Mailgun.Email = Mailgun.Email(
          Mailgun.EmailAddress(config.mailgunSender),
          Mailgun.EmailAddress(to),
          "Reset your password",
          Some(s"Reset your password (text): http://localhost:8080/reset/\$code"),
          Some(s"Reset your password (html): http://localhost:8080/reset/\$code")
        )

        override def send(msg: Mailgun.Email): EitherT[F, Throwable, Unit] = mailgun.send(msg).map(_ => ())
      }

      confirmationService = ConfirmationService.impl[F, User, UserId](xa)
      resetService = ResetService.impl[F, User, UserId](xa)
      sessionService = SessionService.impl[F, User, UserId](xa)

      httpApp = FlashMiddleware
        .httpRoutes[F](
          webjarServiceBuilder[F].toRoutes
            <+> AuthRoutes.routes[F, User, Mailgun.Email, UserId](csrfTokenKey, csrf, userService, confirmationService, mailService, sessionService, resetService)
        )
        .orNotFound

      finalHttpApp = Logger.httpApp(true, true)(httpApp)
      withErrorLogging = ErrorHandling.Recover.total(
        ErrorAction.log(
          finalHttpApp,
          messageFailureLogAction = errorHandler,
          serviceErrorLogAction = errorHandler
        )
      )

      _ <-
        EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(CSRFMiddleware.validate[F, F](csrf, cookieName, csrfTokenKey)(withErrorLogging))
          .build
    } yield ()
  }.useForever

  private def defaultLogger[F[_] : Async]: org.typelevel.log4cats.Logger[F] =
    Slf4jLogger.getLogger[F]

  private def errorHandler[F[_] : Async](t: Throwable, msg: => String)(using
    C: Console[F]
  ): F[Unit] =
    for {
      _ <- Console[F].println(msg)
      _ <- Console[F].printStackTrace(t)
    } yield ()

  private def getTransactor[F[_] : Async: Network](
      config: ApplicationConfiguration
  ) = Transactor.fromDriverManager[F] (
      driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver",
      url = config.sqlUrl,
      user = config.sqlUsername,
      password = config.sqlPassword,
      logHandler = None
    )