package models

import akka.actor._
import scala.concurrent.duration._

import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future

object Room {
  import scala.language.postfixOps

  implicit val timeout = Timeout(1 second)

  def create(roomNumber: Int) = Akka.system.actorOf(Props(new Room(roomNumber)))

  def join(username: String)
          (implicit default: ActorRef): Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
    (default ? Join(username)).map {
      case Connected(enumerator) =>
        // Create an Iteratee to consume the feed
        val iteratee = Iteratee.foreach[JsValue] { event =>
          implicit val from = username
          handleEvent(event)
        }.mapDone { _ =>
          default ! Quit(username)
        }
        (iteratee, enumerator)
      case CannotConnect(error) =>
        // Connection error
        // A finished Iteratee sending EOF
        val iteratee = Done[JsValue,Unit]((),Input.EOF)
        // Send an error and close the socket
        val enumerator =  Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))
        (iteratee, enumerator)
    }
  }

  def handleEvent(event: JsValue)(implicit default: ActorRef, from: String) {
    val body: JsValue = (event \ "body")
    default ! ((event \ "kind").as[String] match {
      case "members"  => Members(from)
      case "bet"      => Bet(from, body.as[String].toFloat)
      case "cards"    => Cards(from)
      case "showDown" => ShowDown()
      case "reset"    => Reset()
      case e          => throw new IllegalArgumentException(e)
    })
  }
}

class Room(val roomNumber: Int) extends Actor {

  private[this] var members = List.empty[Member]
  private[this] val (enumerator, channel) = Concurrent.broadcast[JsValue]

  private[this] var up = false

  def receive = {
    case Join(username) => {
      if(members.contains(Member(username))) {
        sender ! CannotConnect("This username is already used")
      } else {
        members = Member(username) :: members
        sender ! Connected(enumerator)
        notifyAll("join", username, JsString("has entered the room"))
      }
    }
    case Quit(username) => {
      members = members.filterNot(_.name == username)
      self ! Members(username)
      self ! Cards(username)
      if (members.isEmpty) Lobby.checkOut(roomNumber)
    }
    case Members(username) => {
      notifyAll("members", username, JsArray(members.map{ m => JsString(m.name) }))
    }
    case Cards(username) => notifyCards
    case Bet(username, point) => {
      play.Logger.debug(s"$username: $point")
      members.find(_.name == username).map { m =>
        m.point = Some(point)
      }
      notifyCards
    }
    case ShowDown() => {
      up = true
      notifyCards
    }
    case Reset() => {
      up = false
      members.map(_.point = None)
      notifyCards
    }
  }

  def notifyCards = {
    val v = members.map { m =>
      val p = m.point match {
        case Some(i) => if (up) JsNumber(i) else JsBoolean(true)
        case None    => JsNull
      }
      val s = Seq(
        "user"  -> JsString(m.name),
        "point" -> p)
      JsObject(s)
    }
    val validCards = members.map(_.point.getOrElse(-1f)).filter((i: Float) => (0f <= i && i < 999f))
    val total = validCards.sum
    val (ave, max, min) = if (0 < validCards.size) {
      (
        (total / validCards.length).toString,
        validCards.max.toString,
        validCards.min.toString
      )
    } else ("???", "???", "???")
    val result = Seq(
      "average" -> JsString(ave),
      "max"     -> JsString(max),
      "min"     -> JsString(min)
    )
    val r = Seq(
      "up"    -> JsBoolean(up),
      "cards" -> JsArray(v)) ++ (if (up) result else Seq())
    notifyAll("cards", "", JsObject(r))
  }

  def notifyAll(kind: String, user: String, body: JsValue) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user),
        "body" -> body
      )
    )
    channel.push(msg)
  }
}

case class Member(val name: String) {
  var point: Option[Float] = None
}

case class Join(username: String)
case class Quit(username: String)
case class Members(username: String)

case class Bet(username: String, point: Float)
case class Cards(username: String)

case class ShowDown()
case class Reset()

case class Connected(enumerator:Enumerator[JsValue])
case class CannotConnect(msg: String)
