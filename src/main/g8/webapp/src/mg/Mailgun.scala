package mg

import cats.Monad
import cats.data.EitherT
import cats.effect.{Async, Concurrent}
import cats.implicits.*
import fs2.io.net.Network
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Authorization
import org.http4s.{
  BasicCredentials,
  EntityDecoder,
  Headers,
  Method,
  Request,
  Uri
}
import org.typelevel.log4cats.LoggerFactory

class Mailgun[F[_]: LoggerFactory: Concurrent: Async: Network](
    domain: Uri,
    apiKey: String
) {
  import Mailgun.*

  implicit val formDecoder: EntityDecoder[F, Response] = jsonOf[F, Response]

  def send(
      message: Email
  ): EitherT[F, Throwable, Response] = {
    val uri = domain
    EitherT {
      EmberClientBuilder
        .default[F]
        .build
        .use { client =>
          client.expect[Response](
            Request[F](
              Method.POST,
              Uri(
                uri.scheme,
                uri.authority,
                uri.path,
                uri.query
                  .+:("from", Some(message.from.toString))
                  .+:("to", Some(message.to.toString))
                  .+:("subject", Some(message.subject))
                  .+:("html", message.html)
              ),
              headers =
                Headers(Authorization(BasicCredentials("api", apiKey)))
            )
          )
        }
        .attempt
    }
  }
}

object Mailgun {
  def uri(domain: String) = s"https://api.mailgun.net/v3/\$domain/messages"

  trait SendError {
    def message: String
  }

  case class Email(
      from: EmailAddress,
      to: EmailAddress,
      subject: String,
      text: Option[String],
      html: Option[String]
  )

  case class EmailAddress(address: String, name: Option[String] = None) {
    override def toString: String = name match {
      case Some(n) => s"\$n <\$address>"
      case None    => address
    }
  }

  case class Response(id: String, message: String)

  case object InvalidDomain extends SendError {
    def message = "The e-mail service is configured incorrectly. (1)"
  }

  object EmailAddress {
    def apply(address: String, name: String): EmailAddress =
      EmailAddress(address, Option(name))
  }
}
