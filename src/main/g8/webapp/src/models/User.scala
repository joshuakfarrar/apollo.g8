package models

import java.util.UUID

case class User(id: UUID, name: String, email: String, password: String)