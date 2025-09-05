import pureconfig.*

case class ApplicationConfiguration(
    uiUrl: String,
    sqlUrl: String,
    sqlUsername: String,
    sqlPassword: String,
    mailgunDomain: String,
    mailgunKey: String,
    mailgunSender: String
) derives ConfigReader
