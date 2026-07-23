import pureconfig.*

case class ApplicationConfiguration(
    uiUrl: String,
    sqlUrl: String,
    sqlUsername: String,
    sqlPassword: String,
    // when absent, e-mails are printed to the console instead of sent
    mailgunDomain: Option[String],
    mailgunKey: Option[String],
    mailgunSender: Option[String]
) derives ConfigReader
