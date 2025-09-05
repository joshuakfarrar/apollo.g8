package mg

case class Message(from: Mailgun.EmailAddress, to: Mailgun.EmailAddress, subject: String, text: Option[String], html: Option[String])