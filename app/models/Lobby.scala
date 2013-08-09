package models

import akka.actor.{ActorRef, Props, Actor}
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import play.api.libs.concurrent.Akka

import akka.pattern.ask

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import scala.util.Random

object Lobby {
  import scala.language.postfixOps
  implicit val timeout = Timeout(1 second)
  lazy val default = Akka.system.actorOf(Props[Lobby])

  def reserve(): Future[Int] = {
    (default ? Reserve()).map {
      case Some(roomNumber: Int) => roomNumber
      case _                     => throw new RuntimeException("Cannot reserve a room for you.")
    }
  }

  def checkIn(roomNumber: Int): Future[ActorRef] = {
    (default ? CheckIn(roomNumber)).map {
      case Welcome(roomActor) => roomActor
      case Abort(msg)         => throw new RuntimeException(msg)
    }
  }

  def availableRooms: Future[Set[Int]] = {
    (default ? Rooms()).map {
      case RoomsReply(rooms) => rooms
      case _ => throw new RuntimeException("Cannot get rooms.")
    }
  }

  def checkOut(roomNumber: Int) = {
    default ! CheckOut(roomNumber)
  }
}

class Lobby extends Actor {
  private[this] var rooms = Map.empty[Int, ActorRef]

  def receive = {
    case Rooms() => {
      sender ! RoomsReply(rooms.keySet)
    }
    case Reserve() => {
      val numbers = rooms.keySet
      val r = new Random
      val roomNumber = r.shuffle((1 to 100).filterNot(numbers.contains(_))).headOption
      roomNumber.map { n =>
        rooms = rooms + (n -> Room.create(n))
      }
      sender ! roomNumber
    }
    case CheckIn(roomNumber) => {
      val roomActor: Option[ActorRef] = rooms.get(roomNumber)
      sender ! (roomActor match {
        case Some(actor) => Welcome(actor)
        case _ => Abort("This room is not reserved for you!")
      })
    }
    case CheckOut(roomNumber) => {
      rooms = rooms - roomNumber
    }
  }
}

case class Reserve()
case class Rooms()
case class RoomsReply(rooms: Set[Int])
case class Welcome(roomActor: ActorRef)
case class Abort(reason: String)
case class CheckIn(roomNumber: Int)
case class CheckOut(roomNumber: Int)
