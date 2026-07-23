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
import io.github.joshuakfarrar.apollo.core.*
import io.github.joshuakfarrar.apollo.doobie.*
import io.github.joshuakfarrar.apollo.http4s.*
import mg.{Mailgun, MailgunMailService}
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

  def run[F[_]: Async: Sync: Network: Console](
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
      key <- Resource.eval(config.csrfKey.filter(_.nonEmpty) match {
        case Some(encoded) =>
          S.delay(java.util.Base64.getDecoder.decode(encoded))
            .flatMap(CSRF.buildSigningKey[F](_))
        case None => CSRF.generateSigningKey[F]()
      })
      csrfTokenKey <- Resource.eval(Key.newKey[F, String])

      csrf = CSRF.withDefaultOriginCheckFormAware[F, F](
          csrfField,
          FunctionK.id[F]
        )(
          key,
          config.csrfHost,
          if (config.csrfSecure) Uri.Scheme.https else Uri.Scheme.http,
          config.csrfPort
        )
        .withCookieName(cookieName)
        .withCookieDomain(Some(config.csrfHost))
        .withCookiePath(Some("/"))
        .withCookieSecure(config.csrfSecure)
        .build

      // database transactor
      xa = getTransactor[F](config)

      // Apollo configuration
      apolloConfig = ApolloConfig[F](
        csrfTokenKey = csrfTokenKey,
        csrf = csrf
      )

      // mail backend: the console mailer is the default — confirmation and
      // reset e-mails are printed to the server console. Setting all three
      // mailgun-* keys in application.conf switches to mg.MailgunMailService.
      mailgunConfig = (
        config.mailgunDomain.filter(_.nonEmpty),
        config.mailgunKey.filter(_.nonEmpty),
        config.mailgunSender.filter(_.nonEmpty)
      ).tupled

      _ <- Resource.eval(mailgunConfig match {
        case None =>
          LoggerFactory[F].getLogger.info(
            "No Mailgun configuration found — e-mails will be printed to the console"
          )
        case Some(_) => S.unit
      })

      routes = mailgunConfig match {
        case None =>
          apolloRoutes[F, String](apolloConfig, xa, MailService.console[F])
        case Some((domain, key, sender)) =>
          apolloRoutes[F, Mailgun.Email](
            apolloConfig,
            xa,
            MailgunMailService[F](domain, key, sender, config.uiUrl)
          )
      }

      httpApp = FlashMiddleware
        .httpRoutes[F](webjarServiceBuilder[F].toRoutes <+> routes)
        .orNotFound

      // one line per request; set logBody = true to dump full bodies when
      // debugging, at the cost of drowning out the [apollo mail] lines
      finalHttpApp = Logger.httpApp(logHeaders = false, logBody = false)(httpApp)

      _ <-
        EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(CSRFMiddleware.validate[F, F](csrf, cookieName, csrfTokenKey)(finalHttpApp))
          .build
    } yield ()
  }.useForever

  // Builds the Apollo services and routes for any mail backend E.
  // Confirmation is opt-in: drop the Some(...) to sign users in
  // immediately after registration without e-mail confirmation.
  private def apolloRoutes[F[_]: Async: Random: LoggerFactory, E](
      apolloConfig: ApolloConfig[F],
      xa: Transactor[F],
      mailService: MailService[F, E, Unit]
  )(using Hashable[F, String]): HttpRoutes[F] = {
    val services = ApolloServices[F, User, UserId, E](
      user = DoobieUserService[F, User, UserId](xa),
      mail = mailService,
      session = DoobieSessionService[F, User, UserId](xa),
      reset = DoobieResetService[F, User, UserId](xa),
      confirmation = Some(DoobieConfirmationService[F, User, UserId](xa))
    )
    val apollo = Apollo[F, User, UserId, E](apolloConfig, services)
    WelcomeRoutes.routes[F, User, UserId, E](apollo) <+>
      AuthRoutes.routes[F, User, UserId, E](apollo)
  }

  private def getTransactor[F[_] : Async: Network](
      config: ApplicationConfiguration
  ) = Transactor.fromDriverManager[F] (
      driver = "org.postgresql.Driver",
      url = config.sqlUrl,
      user = config.sqlUsername,
      password = config.sqlPassword,
      logHandler = None
    )
