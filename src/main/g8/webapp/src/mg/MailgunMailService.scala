package mg

import cats.data.EitherT
import cats.effect.Async
import cats.implicits.*
import fs2.io.net.Network
import io.github.joshuakfarrar.apollo.core.MailService
import org.http4s.Uri
import org.typelevel.log4cats.LoggerFactory

/** MailService backed by Mailgun. The app defaults to apollo's console
  * mailer; Server wires this in only when all three mailgun-* settings
  * are present in application.conf.
  */
object MailgunMailService:
  def apply[F[_]: Async: Network: LoggerFactory](
      domain: String,
      apiKey: String,
      sender: String,
      uiUrl: String
  ): MailService[F, Mailgun.Email, Unit] =
    new MailService[F, Mailgun.Email, Unit] {
      val mailgun = new Mailgun(
        domain = Uri.fromString(Mailgun.uri(domain)).fold(throw _, identity),
        apiKey = apiKey
      )

      override def confirmationEmail(to: String, code: String): Mailgun.Email = Mailgun.Email(
        Mailgun.EmailAddress(sender),
        Mailgun.EmailAddress(to),
        "Confirm your account",
        Some(s"Confirm your account (text): \$uiUrl/confirm/\$code"),
        Some(s"Confirm your account (html): \$uiUrl/confirm/\$code")
      )

      override def resetEmail(to: String, code: String): Mailgun.Email = Mailgun.Email(
        Mailgun.EmailAddress(sender),
        Mailgun.EmailAddress(to),
        "Reset your password",
        Some(s"Reset your password (text): \$uiUrl/reset/\$code"),
        Some(s"Reset your password (html): \$uiUrl/reset/\$code")
      )

      override def send(msg: Mailgun.Email): EitherT[F, Throwable, Unit] = mailgun.send(msg).map(_ => ())
    }
