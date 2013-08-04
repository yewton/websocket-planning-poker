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
          sendMessage(event)
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

  def sendMessage(event: JsValue)(implicit default: ActorRef, from: String) {
    val body: JsValue = (event \ "body")
    default ! ((event \ "kind").as[String] match {
      case "talk"    => Talk(from, body.as[String])
      case "members" => Members(from)
      case e         => throw new IllegalArgumentException(e)
    })
  }
}

class Room(val roomNumber: Int) extends Actor {

  private[this] var members = Set.empty[String]
  private[this] val (enumerator, channel) = Concurrent.broadcast[JsValue]

  def receive = {
    case Join(username) => {
      if(members.contains(username)) {
        sender ! CannotConnect("This username is already used")
      } else {
        members = members + username
        sender ! Connected(enumerator)
        notifyAll("join", username, JsString("has entered the room"))
      }
    }
    case Talk(username, text) => {
      notifyAll("talk", username, JsString(text))
    }
    case Quit(username) => {
      members = members - username
      notifyAll("quit", username, JsString("has left the room"))
      if (members.isEmpty) Lobby.checkOut(roomNumber)
    }
    case Members(username) => {
      notifyAll("members", username, JsArray(members.toList.map(JsString)));
    }
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

case class Join(username: String)
case class Quit(username: String)
case class Talk(username: String, text: String)
case class Members(username: String)

case class Connected(enumerator:Enumerator[JsValue])
case class CannotConnect(msg: String)
