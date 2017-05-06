package lila.study

import akka.actor._
import akka.pattern.ask
import com.typesafe.config.Config
import scala.concurrent.duration._

import lila.common.PimpedConfig._
import lila.hub.actorApi.map.Ask
import lila.hub.{ ActorMap, Sequencer }
import lila.socket.actorApi.GetVersion
import lila.socket.AnaDests
import makeTimeout.short

final class Env(
    config: Config,
    lightUserApi: lila.user.LightUserApi,
    gamePgnDump: lila.game.PgnDump,
    importer: lila.importer.Importer,
    evalCacheHandler: lila.evalCache.EvalCacheSocketHandler,
    system: ActorSystem,
    hub: lila.hub.Env,
    db: lila.db.Env,
    asyncCache: lila.memo.AsyncCache.Builder
) {

  private val settings = new {
    val CollectionStudy = config getString "collection.study"
    val CollectionChapter = config getString "collection.chapter"
    val HistoryMessageTtl = config duration "history.message.ttl"
    val UidTimeout = config duration "uid.timeout"
    val SocketTimeout = config duration "socket.timeout"
    val SocketName = config getString "socket.name"
    val SequencerTimeout = config duration "sequencer.timeout"
    val NetDomain = config getString "net.domain"
    val NetBaseUrl = config getString "net.base_url"
    val MaxPerPage = config getInt "paginator.max_per_page"
  }
  import settings._

  private val socketHub = system.actorOf(
    Props(new lila.socket.SocketHubActor.Default[Socket] {
      def mkActor(studyId: String) = new Socket(
        studyId = Study.Id(studyId),
        jsonView = jsonView,
        studyRepo = studyRepo,
        lightUser = lightUserApi.async,
        history = new lila.socket.History(ttl = HistoryMessageTtl),
        uidTimeout = UidTimeout,
        socketTimeout = SocketTimeout,
        lightStudyCache = lightStudyCache
      )
    }), name = SocketName
  )

  def version(studyId: Study.Id): Fu[Int] =
    socketHub ? Ask(studyId.value, GetVersion) mapTo manifest[Int]

  lazy val socketHandler = new SocketHandler(
    hub = hub,
    socketHub = socketHub,
    chat = hub.actor.chat,
    api = api,
    evalCacheHandler = evalCacheHandler
  )

  lazy val studyRepo = new StudyRepo(coll = db(CollectionStudy))
  lazy val chapterRepo = new ChapterRepo(coll = db(CollectionChapter))

  lazy val jsonView = new JsonView(
    studyRepo,
    lightUserApi.sync
  )

  private lazy val chapterMaker = new ChapterMaker(
    importer = importer,
    pgnFetch = new PgnFetch,
    lightUser = lightUserApi.sync,
    chat = hub.actor.chat,
    domain = NetDomain
  )

  private lazy val studyMaker = new StudyMaker(
    lightUser = lightUserApi.sync,
    chapterMaker = chapterMaker
  )

  lazy val api = new StudyApi(
    studyRepo = studyRepo,
    chapterRepo = chapterRepo,
    sequencers = sequencerMap,
    chapterMaker = chapterMaker,
    studyMaker = studyMaker,
    inviter = new StudyInvite(
    notifyApi = lila.notify.Env.current.api,
    relationApi = lila.relation.Env.current.api
  ),
    tagsFixer = new ChapterTagsFixer(chapterRepo, gamePgnDump),
    lightUser = lightUserApi.sync,
    scheduler = system.scheduler,
    chat = hub.actor.chat,
    bus = system.lilaBus,
    timeline = hub.actor.timeline,
    socketHub = socketHub,
    lightStudyCache = lightStudyCache
  )

  lazy val pager = new StudyPager(
    studyRepo = studyRepo,
    chapterRepo = chapterRepo,
    maxPerPage = lila.common.MaxPerPage(MaxPerPage)
  )

  lazy val pgnDump = new PgnDump(
    chapterRepo = chapterRepo,
    gamePgnDump = gamePgnDump,
    lightUser = lightUserApi.sync,
    netBaseUrl = NetBaseUrl
  )

  private val sequencerMap = system.actorOf(Props(ActorMap { id =>
    new Sequencer(
      receiveTimeout = SequencerTimeout.some,
      executionTimeout = 5.seconds.some,
      logger = logger
    )
  }))

  lazy val lightStudyCache: LightStudyCache = asyncCache.multi(
    name = "study.lightStudyCache",
    f = studyRepo.lightById,
    expireAfter = _.ExpireAfterWrite(20 minutes)
  )

  def cli = new lila.common.Cli {
    def process = {
      case "study" :: "rank" :: "reset" :: Nil => api.resetAllRanks.map { count => s"$count done" }
    }
  }
}

object Env {

  lazy val current: Env = "study" boot new Env(
    config = lila.common.PlayApp loadConfig "study",
    lightUserApi = lila.user.Env.current.lightUserApi,
    gamePgnDump = lila.game.Env.current.pgnDump,
    importer = lila.importer.Env.current.importer,
    evalCacheHandler = lila.evalCache.Env.current.socketHandler,
    system = lila.common.PlayApp.system,
    hub = lila.hub.Env.current,
    db = lila.db.Env.current,
    asyncCache = lila.memo.Env.current.asyncCache
  )
}
