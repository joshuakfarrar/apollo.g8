import cats.Monad
import cats.arrow.FunctionK
import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.Resource
import cats.effect.kernel.{Async, Sync}
import cats.effect.std.{Console, Random}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.password4j.Password
import doobie.implicits.*
import doobie.{Get, Meta, Put, Transactor}
import fs2.io.net.Network
import me.joshuakfarrar.apollo.core.*
import me.joshuakfarrar.apollo.doobie.*
import me.joshuakfarrar.apollo.http4s.*
import mg.Mailgun
import models.User
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.{CSRF, ErrorAction, ErrorHandling, Logger}
import org.http4s.server.staticcontent.webjarServiceBuilder
import org.http4s.{HttpRoutes, Response, Status, Uri, EntityEncoder, UrlForm}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.vault.Key

import java.util.UUID

object Server:

  private type UserId = UUID

  // type class instances for User domain object
  given HasId[User, UserId] = _.id
  given HasPassword[User] = _.password
  given HasEmail[User] = _.email

  given Meta[UUID] = Meta.Advanced.other[UUID]("uuid")

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

  def run[F[_]: Async: Sync: Network](
    config: ApplicationConfiguration
  )(using S: Sync[F], R: Random[F]): F[Nothing] = {

    given LoggerFactory[F] = Slf4jFactory.create[F]

    given Hashable[F, String] with
      def hash(plain: String): F[String] = S.delay(
        Password.hash(plain).withArgon2().getResult
      )

      def verify(plain: String, hashed: String): F[Boolean] = S.delay(
        Password
          .check(plain, hashed)
          .withArgon2()
      )

    for {
      _ <- Resource.eval(
        LoggerFactory[F].getLogger.info(banner.mkString(System.lineSeparator))
      )

      // csrf stuff
      cookieName = "csrf-token"
      csrfField = "_csrf"
      key  <- Resource.eval(CSRF.generateSigningKey[F]())
      csrfTokenKey <- Resource.eval(Key.newKey[F, String])

      csrf = CSRF.withDefaultOriginCheckFormAware[F, F](
          csrfField,
          FunctionK.id[F]
        )(
          key,
          "localhost",
          Uri.Scheme.http,
          Some(8080)
        )
        .withCookieName(cookieName)
        .withCookieDomain(Some("localhost"))
        .withCookiePath(Some("/"))
        .build

      // database transactor
      xa = getTransactor[F](config)

      // mail service implementation
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

      // Apollo configuration
      apolloConfig = ApolloConfig[F](
        csrfTokenKey = csrfTokenKey,
        csrf = csrf
      )

      // Apollo services (using Doobie implementations)
      apolloServices = ApolloServices[F, User, UserId, Mailgun.Email](
        user = UserServiceDoobie.impl[F, User, UserId](xa),
        confirmation = ConfirmationServiceDoobie.impl[F, User, UserId](xa),
        mail = mailService,
        session = SessionServiceDoobie.impl[F, User, UserId](xa),
        reset = ResetServiceDoobie.impl[F, User, UserId](xa)
      )

      // Create Apollo instance with config and services
      apollo = Apollo[F, User, UserId, Mailgun.Email](apolloConfig, apolloServices)

      httpApp = FlashMiddleware
        .httpRoutes[F](
          webjarServiceBuilder[F].toRoutes
            <+> AuthRoutes.routes[F, User, Mailgun.Email, UserId](apollo)
        )
        .orNotFound

      finalHttpApp = Logger.httpApp(true, true)(httpApp)

      _ <-
        EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(CSRFMiddleware.validate[F, F](csrf, cookieName, csrfTokenKey)(finalHttpApp))
          .build
    } yield ()
  }.useForever

  private def getTransactor[F[_] : Async: Network](
      config: ApplicationConfiguration
  ) = Transactor.fromDriverManager[F] (
      driver = "org.postgresql.Driver",
      url = config.sqlUrl,
      user = config.sqlUsername,
      password = config.sqlPassword,
      logHandler = None
    )
