package lila.analyse

import chess.{ Ply, Color }
import play.api.libs.json.*

import lila.common.Maths
import lila.game.Game
import lila.tree.JsonHandlers.*
import lila.common.Json.given

object JsonView:

  def moves(analysis: Analysis, withGlyph: Boolean = true) =
    JsArray(analysis.infoAdvices map { case (info, adviceOption) =>
      Json
        .obj()
        .add("eval" -> info.cp)
        .add("mate" -> info.mate)
        .add("best" -> info.best.map(_.uci))
        .add("variation" -> info.variation.nonEmpty.option(info.variation mkString " "))
        .add("judgment" -> adviceOption.map { a =>
          Json
            .obj(
              "name"    -> a.judgment.name,
              "comment" -> a.makeComment(withEval = false, withBestMove = true)
            )
            .add(
              "glyph" -> withGlyph.option(
                Json.obj(
                  "name"   -> a.judgment.glyph.name,
                  "symbol" -> a.judgment.glyph.symbol
                )
              )
            )
        })
    })

  def player(pov: Game.SideAndStart)(analysis: Analysis, accuracy: Option[Color.Map[AccuracyPercent]]) =
    analysis.summary
      .find(_._1 == pov.color)
      .map(_._2)
      .map { s =>
        JsObject(s map { case (nag, nb) =>
          nag.toString.toLowerCase -> JsNumber(nb)
        })
          .add("acpl", lila.analyse.AccuracyCP.mean(pov, analysis))
          .add("accuracy", accuracy.map(_(pov.color).toInt))
      }

  def bothPlayers(startedAtPly: Ply, analysis: Analysis, withAccuracy: Boolean = true) =
    val accuracy = withAccuracy ?? AccuracyPercent.gameAccuracy(startedAtPly.color, analysis)
    Json.obj(
      "id"    -> analysis.id,
      "white" -> player(Game.SideAndStart(Color.white, startedAtPly))(analysis, accuracy),
      "black" -> player(Game.SideAndStart(Color.black, startedAtPly))(analysis, accuracy)
    )

//   def bothPlayers(pov: Game.SideAndStart, analysis: Analysis, accuracy: Option[Color.Map[AccuracyPercent]]) =
//     Json.obj(
//       "id"    -> analysis.id,
//       "white" -> player(pov.copy(color = chess.White))(analysis, accuracy),
//       "black" -> player(pov.copy(color = chess.Black))(analysis, accuracy)
//     )

  def mobile(game: Game, analysis: Analysis) =
    Json.obj(
      "summary" -> bothPlayers(game.startedAtPly, analysis, withAccuracy = false),
      "moves"   -> moves(analysis)
    )
