package controllers

import play.api._
import play.api.mvc._

import play.api.libs.json._
import play.api.libs.iteratee._

import models._

import akka.actor._
import scala.concurrent.duration._

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.data._
import play.api.data.Forms._

object Application extends Controller {
  val createForm = Form("userName" -> text)

  /**
   * Just display the home page.
   */
  def index = Action { implicit request =>
    Ok(views.html.index())
  }

  /**
   * Just reserve the room, not enter.
   */
  def reserve = Action { implicit request => Async {
    val userName = createForm.bindFromRequest.get
    Lobby.reserve.map { roomNumber =>
      Redirect(routes.Application.enter(roomNumber)).flashing(
        "userName" -> userName
      )
    } recover { case e =>
      Redirect(routes.Application.index).flashing(
        "error" -> e.getMessage()
      )
    }
  }}

  /**
   * Knock the door to check if it is avaiable.
   * The user will be took the room if it's available.
   *
   * @param roomNumber
   * @param userName
   */
  def knock(roomNumber: Option[Int], userName: Option[String]) = Action { Async {
    Lobby.availableRooms.map { rooms =>
      val pair: Option[(String, Int)] = for {
        userName   <- userName if ! userName.isEmpty
        roomNumber <- roomNumber if rooms.contains(roomNumber)
      } yield {
        (userName, roomNumber)
      }
      pair.map { case (userName, roomNumber) =>
        Redirect(routes.Application.enter(roomNumber)).flashing(
          "userName" -> userName
        )
      }.getOrElse {
        Redirect(routes.Application.index).flashing(
          "error" -> "Please choose a valid username and a valid room number."
        )
      }
    }
  }}

  /**
   * Enter the room.
   *
   * @param roomNumber
   */
  def enter(roomNumber: Int) = Action { implicit request =>
    flash.get("userName").map { name =>
      Ok(views.html.room(roomNumber, name))
    }.getOrElse {
      Redirect(routes.Application.index).flashing(
        "error" -> "Something's wrong. Retry."
      )
    }
  }

  /**
   * Get the socket for exchange messages.
   *
   * @param roomNumber
   * @param userName
   */
  def socket(roomNumber: Int, userName: String) = WebSocket.async[JsValue] { request =>
    Lobby.checkIn(roomNumber).flatMap { room =>
      Room.join(userName)(room)
    }
    // cause 500 error on failure
  }
}
