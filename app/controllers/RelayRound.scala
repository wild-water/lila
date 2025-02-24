package controllers

import play.api.data.Form
import play.api.mvc.*
import scala.concurrent.duration.*

import lila.api.Context
import lila.app.{ given, * }

// import lila.common.config.MaxPerSecond
import lila.relay.{ RelayRound as RoundModel, RelayRoundForm, RelayTour as TourModel }
import lila.user.{ User as UserModel }
import views.*
import lila.common.HTTPRequest

final class RelayRound(
    env: Env,
    studyC: => Study,
    apiC: => Api
) extends LilaController(env):

  def form(tourId: String) =
    Auth { implicit ctx => me =>
      NoLameOrBot {
        WithTourAndRoundsCanUpdate(tourId) { trs =>
          Ok(html.relay.roundForm.create(env.relay.roundForm.create(trs), trs.tour)).toFuccess
        }
      }
    }

  def create(tourId: String) =
    AuthOrScopedBody(_.Study.Write)(
      auth = implicit ctx =>
        me =>
          NoLameOrBot {
            WithTourAndRoundsCanUpdate(tourId) { trs =>
              val tour = trs.tour
              env.relay.roundForm
                .create(trs)
                .bindFromRequest()(ctx.body, formBinding)
                .fold(
                  err => BadRequest(html.relay.roundForm.create(err, tour)).toFuccess,
                  setup =>
                    rateLimitCreation(
                      me,
                      ctx.req,
                      Redirect(routes.RelayTour.redirectOrApiTour(tour.slug, tour.id.value))
                    ) {
                      env.relay.api.create(setup, me, tour) map { round =>
                        Redirect(routes.RelayRound.show(tour.slug, round.slug, round.id.value))
                      }
                    }
                )
            }
          },
      scoped = req =>
        me =>
          NoLameOrBot(me) {
            env.relay.api tourById TourModel.Id(tourId) flatMap {
              _ ?? { tour =>
                env.relay.api.withRounds(tour) flatMap { trs =>
                  env.relay.roundForm
                    .create(trs)
                    .bindFromRequest()(req, formBinding)
                    .fold(
                      err => BadRequest(apiFormError(err)).toFuccess,
                      setup =>
                        rateLimitCreation(me, req, rateLimited) {
                          JsonOk {
                            env.relay.api.create(setup, me, tour) map { round =>
                              env.relay.jsonView.withUrl(round withTour tour)
                            }
                          }
                        }
                    )
                }
              }
            }
          }
    )

  def edit(id: RelayRoundId) =
    Auth { implicit ctx => me =>
      OptionFuResult(env.relay.api.byIdAndContributor(id, me)) { rt =>
        Ok(html.relay.roundForm.edit(rt, env.relay.roundForm.edit(rt.round))).toFuccess
      }
    }

  def update(id: RelayRoundId) =
    AuthOrScopedBody(_.Study.Write)(
      auth = implicit ctx =>
        me =>
          doUpdate(id, me)(using ctx.body) flatMap {
            case None => notFound
            case Some(res) =>
              res
                .fold(
                  { case (old, err) => BadRequest(html.relay.roundForm.edit(old, err)) },
                  rt => Redirect(rt.path)
                )
                .toFuccess
          },
      scoped = req =>
        me =>
          doUpdate(id, me)(using req) map {
            case None => NotFound(jsonError("No such broadcast"))
            case Some(res) =>
              res.fold(
                { case (_, err) => BadRequest(apiFormError(err)) },
                rt => JsonOk(env.relay.jsonView.withUrl(rt))
              )
          }
    )

  private def doUpdate(id: RelayRoundId, me: UserModel)(using
      req: Request[?]
  ): Fu[Option[Either[(RoundModel.WithTour, Form[RelayRoundForm.Data]), RoundModel.WithTour]]] =
    env.relay.api.byIdAndContributor(id, me) flatMap {
      _ ?? { rt =>
        env.relay.roundForm
          .edit(rt.round)
          .bindFromRequest()
          .fold(
            err => fuccess(Left(rt -> err)),
            data =>
              env.relay.api.update(rt.round) { data.update(_, me) }.dmap(_ withTour rt.tour) dmap Right.apply
          ) dmap some
      }
    }

  def reset(id: RelayRoundId) =
    Auth { implicit ctx => me =>
      OptionFuResult(env.relay.api.byIdAndContributor(id, me)) { rt =>
        env.relay.api.reset(rt.round, me) inject Redirect(rt.path)
      }
    }

  def show(ts: String, rs: String, id: RelayRoundId) =
    OpenOrScoped(_.Study.Read)(
      open = implicit ctx => {
        pageHit
        WithRoundAndTour(ts, rs, id) { rt =>
          val sc =
            if (rt.round.sync.ongoing)
              env.study.chapterRepo relaysAndTagsByStudyId rt.round.studyId flatMap { chapters =>
                chapters.find(_.looksAlive) orElse chapters.headOption match {
                  case Some(chapter) => env.study.api.byIdWithChapter(rt.round.studyId, chapter.id)
                  case None          => env.study.api byIdWithChapter rt.round.studyId
                }
              }
            else env.study.api byIdWithChapter rt.round.studyId
          sc flatMap { _ ?? { doShow(rt, _) } }
        }
      },
      scoped = _ =>
        _ =>
          env.relay.api.byIdWithTour(id) flatMap {
            _ ?? { rt =>
              env.study.chapterRepo orderedMetadataByStudy rt.round.studyId map { games =>
                JsonOk(env.relay.jsonView.withUrlAndGames(rt, games))
              }
            }
          }
    )

  def pgn(ts: String, rs: String, id: StudyId) = studyC.pgn(id)
  def apiPgn(id: StudyId)                      = studyC.apiPgn(id)

  def stream(id: RelayRoundId) = AnonOrScoped() { req => me =>
    env.relay.api.byIdWithStudy(id) flatMap {
      _ ?? { rt =>
        studyC.CanView(rt.study, me) {
          apiC
            .GlobalConcurrencyLimitPerIP(HTTPRequest ipAddress req)(
              env.relay.pgnStream.streamRoundGames(rt)
            ) { source =>
              noProxyBuffer(Ok chunked source.keepAlive(60.seconds, () => " ") as pgnContentType)
            }
            .toFuccess
        }(Unauthorized.toFuccess, Forbidden.toFuccess)
      }
    }
  }

  def chapter(ts: String, rs: String, id: RelayRoundId, chapterId: StudyChapterId) =
    Open { implicit ctx =>
      WithRoundAndTour(ts, rs, id) { rt =>
        env.study.api.byIdWithChapter(rt.round.studyId, chapterId) flatMap {
          _ ?? { doShow(rt, _) }
        }
      }
    }

  def push(id: RelayRoundId) =
    ScopedBody(parse.tolerantText)(Seq(_.Study.Write)) { req => me =>
      env.relay.api.byIdAndContributor(id, me) flatMap {
        case None     => notFoundJson()
        case Some(rt) => env.relay.push(rt, req.body) inject jsonOkResult
      }
    }

  private def WithRoundAndTour(ts: String, rs: String, id: RelayRoundId)(
      f: RoundModel.WithTour => Fu[Result]
  )(implicit ctx: Context): Fu[Result] =
    OptionFuResult(env.relay.api byIdWithTour id) { rt =>
      if (!ctx.req.path.startsWith(rt.path)) Redirect(rt.path).toFuccess
      else f(rt)
    }

  private def WithTour(id: String)(
      f: TourModel => Fu[Result]
  )(implicit ctx: Context): Fu[Result] =
    OptionFuResult(env.relay.api tourById TourModel.Id(id))(f)

  private def WithTourAndRoundsCanUpdate(id: String)(
      f: TourModel.WithRounds => Fu[Result]
  )(implicit ctx: Context): Fu[Result] =
    WithTour(id) { tour =>
      ctx.me.?? { env.relay.api.canUpdate(_, tour) } flatMap {
        _ ?? {
          env.relay.api withRounds tour flatMap f
        }
      }
    }

  private def doShow(rt: RoundModel.WithTour, oldSc: lila.study.Study.WithChapter)(using
      ctx: Context
  ): Fu[Result] =
    studyC.CanView(oldSc.study, ctx.me) {
      for {
        (sc, studyData) <- studyC.getJsonData(oldSc)
        rounds          <- env.relay.api.byTourOrdered(rt.tour)
        data <- env.relay.jsonView.makeData(
          rt.tour withRounds rounds.map(_.round),
          rt.round.id,
          studyData,
          ctx.userId exists sc.study.canContribute
        )
        chat      <- studyC.chatOf(sc.study)
        sVersion  <- env.study.version(sc.study.id)
        streamers <- studyC.streamersOf(sc.study)
      } yield Ok(
        html.relay.show(rt withStudy sc.study, data, chat, sVersion, streamers)
      ).enableSharedArrayBuffer
    }(studyC.privateUnauthorizedFu(oldSc.study), studyC.privateForbiddenFu(oldSc.study))

  private val CreateLimitPerUser = lila.memo.RateLimit[UserId](
    credits = 100 * 10,
    duration = 24.hour,
    key = "broadcast.round.user"
  )

  private val CreateLimitPerIP = lila.memo.RateLimit[lila.common.IpAddress](
    credits = 100 * 10,
    duration = 24.hour,
    key = "broadcast.round.ip"
  )

  private[controllers] def rateLimitCreation(
      me: UserModel,
      req: RequestHeader,
      fail: => Result
  )(
      create: => Fu[Result]
  ): Fu[Result] =
    val cost =
      if (isGranted(_.Relay, me)) 2
      else if (me.hasTitle || me.isVerified) 5
      else 10
    CreateLimitPerUser(me.id, cost = cost) {
      CreateLimitPerIP(HTTPRequest ipAddress req, cost = cost) {
        create
      }(fail.toFuccess)
    }(fail.toFuccess)
