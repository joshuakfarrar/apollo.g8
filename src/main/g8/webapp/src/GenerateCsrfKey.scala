import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.server.middleware.CSRF

/** Prints a base64 CSRF signing key for the csrf-key setting in
  * application.conf. Run with: mill webapp.runMain generateCsrfKey
  */
@main def generateCsrfKey(): Unit =
  val key = CSRF.generateSigningKey[IO]().unsafeRunSync()
  val encoded = java.util.Base64.getEncoder.encodeToString(key.toArray)
  println(s"csrf-key=\"\$encoded\"")
