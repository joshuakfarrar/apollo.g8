import pureconfig.*

case class ApplicationConfiguration(
    uiUrl: String,
    sqlUrl: String,
    sqlUsername: String,
    sqlPassword: String,
    // when absent, e-mails are printed to the console instead of sent
    mailgunDomain: Option[String],
    mailgunKey: Option[String],
    mailgunSender: Option[String],
    csrfHost: String,
    csrfSecure: Boolean,
    csrfPort: Option[Int],
    // when absent, a fresh signing key is generated at startup, so open
    // forms stop validating across restarts; set it to keep tokens stable
    csrfKey: Option[String]
) derives ConfigReader
