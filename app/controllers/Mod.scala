package controllers

import lila.api.Context
import lila.app._
import lila.chat.Chat
import lila.common.{ IpAddress, EmailAddress }
import lila.report.{ Suspect, Mod => AsMod }
import lila.user.{ UserRepo, User => UserModel }
import views._

import play.api.data._
import play.api.data.Forms._
import play.api.mvc._
import scala.concurrent.duration._

object Mod extends LilaController {

  private def modApi = Env.mod.api
  private def modLogApi = Env.mod.logApi
  private def assessApi = Env.mod.assessApi

  def engine(username: String, v: Boolean) = SecureBody(_.MarkEngine) { implicit ctx => me =>
    withSuspect(username) { sus =>
      for {
        inquiry <- Env.report.api.inquiries ofModId me.id
        _ <- modApi.setEngine(AsMod(me), sus, v)
        res <- Report.onInquiryClose(inquiry, me)
      } yield res
    }
  }

  def publicChat = Secure(_.ChatTimeout) { implicit ctx => _ =>
    val tourChats = Env.mod.publicChat.tournamentChats
    val simulChats = Env.mod.publicChat.simulChats

    tourChats zip simulChats map {
      case (tournamentsAndChats, simulsAndChats) =>
        Ok(html.mod.publicChat(tournamentsAndChats, simulsAndChats))
    }
  }

  def booster(username: String, v: Boolean) = Secure(_.MarkBooster) { implicit ctx => me =>
    withSuspect(username) { sus =>
      modApi.setBooster(AsMod(me), sus, v) inject redirect(username)
    }
  }

  def troll(username: String, v: Boolean) = SecureBody(_.MarkTroll) { implicit ctx => me =>
    withSuspect(username) { prev =>
      for {
        inquiry <- Env.report.api.inquiries ofModId me.id
        _ <- modApi.setTroll(AsMod(me), prev, v)
        res <- Report.onInquiryClose(inquiry, me)
      } yield res
    }
  }

  def warn(username: String, subject: String) = SecureBody(_.ModMessage) { implicit ctx => me =>
    lila.message.ModPreset.bySubject(subject).fold(notFound) { preset =>
      withSuspect(username) { sus =>
        for {
          inquiry <- Env.report.api.inquiries ofModId me.id
          _ <- modApi.setTroll(AsMod(me), sus, sus.user.troll)
          thread <- Env.message.api.sendPreset(me, sus.user, preset)
          _ <- Env.mod.logApi.modMessage(thread.creatorId, thread.invitedId, thread.name)
          res <- Report.onInquiryClose(inquiry, me)
        } yield res
      }
    }
  }

  def ban(username: String, v: Boolean) = Secure(_.IpBan) { implicit ctx => me =>
    withSuspect(username) { sus =>
      modApi.setBan(AsMod(me), sus, v)
    } inject redirect(username)
  }

  def ipban(ip: String) = Secure(_.IpBan) { implicit ctx => me =>
    modApi.ipban(me.id, ip)
  }

  def closeAccount(username: String) = Secure(_.CloseAccount) { implicit ctx => me =>
    modApi.closeAccount(me.id, username) flatMap {
      _ ?? Account.doClose
    } inject redirect(username)
  }

  def reopenAccount(username: String) = Secure(_.ReopenAccount) { implicit ctx => me =>
    modApi.reopenAccount(me.id, username) inject redirect(username)
  }

  def kickFromRankings(username: String) = Secure(_.RemoveRanking) { implicit ctx => me =>
    modApi.kickFromRankings(me.id, username) inject redirect(username)
  }

  def reportban(username: String, v: Boolean) = Secure(_.ReportBan) { implicit ctx => me =>
    withSuspect(username) { sus =>
      modApi.setReportban(AsMod(me), sus, v) inject redirect(username)
    }
  }

  def impersonate(username: String) = Auth { implicit ctx => me =>
    if (username == "-" && lila.mod.Impersonate.isImpersonated(me)) fuccess {
      lila.mod.Impersonate.stop(me)
      Redirect(routes.User.show(me.username))
    }
    else if (isGranted(_.Impersonate)) OptionFuRedirect(UserRepo named username) { user =>
      lila.mod.Impersonate.start(me, user)
      fuccess(routes.User.show(user.username))
    }
    else notFound
  }

  def setTitle(username: String) = SecureBody(_.SetTitle) { implicit ctx => me =>
    implicit def req = ctx.body
    lila.user.DataForm.title.bindFromRequest.fold(
      err => fuccess(redirect(username, mod = true)),
      title => modApi.setTitle(me.id, username, title) >>-
        Env.user.uncacheLightUser(UserModel normalize username) inject
        redirect(username, mod = false)
    )
  }

  def setEmail(username: String) = SecureBody(_.SetEmail) { implicit ctx => me =>
    implicit def req = ctx.body
    OptionFuResult(UserRepo named username) { user =>
      Env.security.forms.modEmail(user).bindFromRequest.fold(
        err => BadRequest(err.toString).fuccess,
        rawEmail => {
          val email = Env.security.emailAddressValidator.validate(EmailAddress(rawEmail)) err s"Invalid email ${rawEmail}"
          modApi.setEmail(me.id, user.id, email) inject redirect(user.username, mod = true)
        }
      )
    }
  }

  def notifySlack(username: String) = Auth { implicit ctx => me =>
    OptionFuResult(UserRepo named username) { user =>
      Env.slack.api.userMod(user = user, mod = me) inject redirect(user.username)
    }
  }

  def log = Secure(_.SeeReport) { implicit ctx => me =>
    modLogApi.recent map { html.mod.log(_) }
  }

  private def communications(username: String, priv: Boolean) = Secure {
    perms => if (priv) perms.ViewPrivateComms else perms.MarkTroll
  } { implicit ctx => me =>
    OptionFuOk(UserRepo named username) { user =>
      lila.game.GameRepo.recentPovsByUserFromSecondary(user, 80) flatMap { povs =>
        priv.?? {
          Env.chat.api.playerChat optionsByOrderedIds povs.map(_.gameId).map(Chat.Id.apply)
        } zip
          priv.?? {
            lila.message.ThreadRepo.visibleByUser(user.id, 60).map {
              _ filter (_ hasPostsWrittenBy user.id) take 30
            }
          } zip
          (Env.shutup.api getPublicLines user.id) zip
          (Env.security userSpy user.id) zip
          Env.user.noteApi.forMod(user.id) zip
          Env.mod.logApi.userHistory(user.id) zip
          Env.report.api.inquiries.ofModId(me.id) map {
            case chats ~ threads ~ publicLines ~ spy ~ notes ~ history ~ inquiry =>
              if (priv && !inquiry.??(_.isRecentCommOf(Suspect(user))))
                Env.slack.api.commlog(mod = me, user = user, inquiry.map(_.createdBy))
              val povWithChats = (povs zip chats) collect {
                case (p, Some(c)) if c.nonEmpty => p -> c
              } take 15
              val filteredNotes = notes.filter(_.from != "irwin")
              html.mod.communication(user, povWithChats, threads, publicLines, spy, filteredNotes, history, priv)
          }
      }
    }
  }

  def communicationPublic(username: String) = communications(username, false)
  def communicationPrivate(username: String) = communications(username, true)

  private[controllers] val ipIntelCache =
    Env.memo.asyncCache.multi[IpAddress, Int](
      name = "ipIntel",
      f = ip => {
        import play.api.libs.ws.WS
        import play.api.Play.current
        val email = Env.api.Net.Email
        val url = s"http://check.getipintel.net/check.php?ip=$ip&contact=$email"
        WS.url(url).get().map(_.body).mon(_.security.proxy.request.time).flatMap { str =>
          parseFloatOption(str).fold[Fu[Int]](fufail(s"Invalid ratio ${str.take(140)}")) { ratio =>
            if (ratio < 0) fufail(s"Error code $ratio")
            else fuccess((ratio * 100).toInt)
          }
        }.addEffects(
          fail = _ => lila.mon.security.proxy.request.failure(),
          succ = percent => {
            lila.mon.security.proxy.percent(percent max 0)
            lila.mon.security.proxy.request.success()
          }
        )
      },
      expireAfter = _.ExpireAfterAccess(3 days)
    )

  def ipIntel(ip: String) = Secure(_.IpBan) { ctx => me =>
    ipIntelCache.get(IpAddress(ip)).map { Ok(_) }.recover {
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  protected[controllers] def redirect(username: String, mod: Boolean = true) =
    Redirect(routes.User.show(username).url + mod.??("?mod"))

  def refreshUserAssess(username: String) = Secure(_.MarkEngine) { implicit ctx => me =>
    assessApi.refreshAssessByUsername(username) >>
      Env.irwin.api.requests.fromMod(lila.user.User normalize username, me) inject
      redirect(username)
  }

  def spontaneousInquiry(username: String) = Secure(_.SeeReport) { implicit ctx => me =>
    OptionFuResult(UserRepo named username) { user =>
      Env.report.api.inquiries.spontaneous(AsMod(me), Suspect(user)) inject Redirect(routes.User.show(user.username) + "?mod")
    }
  }

  def gamify = Secure(_.SeeReport) { implicit ctx => me =>
    Env.mod.gamify.leaderboards zip
      Env.mod.gamify.history(orCompute = true) map {
        case (leaderboards, history) => Ok(html.mod.gamify.index(leaderboards, history))
      }
  }
  def gamifyPeriod(periodStr: String) = Secure(_.SeeReport) { implicit ctx => me =>
    lila.mod.Gamify.Period(periodStr).fold(notFound) { period =>
      Env.mod.gamify.leaderboards map { leaderboards =>
        Ok(html.mod.gamify.period(leaderboards, period))
      }
    }
  }

  def search = Secure(_.UserSearch) { implicit ctx => me =>
    val query = (~get("q")).trim
    Env.mod.search(query) map { users =>
      html.mod.search(query, users)
    }
  }

  def chatUser(username: String) = Secure(_.ChatTimeout) { implicit ctx => me =>
    implicit val lightUser = Env.user.lightUserSync _
    JsonOptionOk {
      Env.chat.api.userChat userModInfo username map2 lila.chat.JsonView.userModInfo
    }
  }

  def permissions(username: String) = Secure(_.ChangePermission) { implicit ctx => me =>
    OptionOk(UserRepo named username) { user =>
      html.mod.permissions(user)
    }
  }

  def savePermissions(username: String) = SecureBody(_.ChangePermission) { implicit ctx => me =>
    implicit def req = ctx.body
    import lila.security.Permission
    OptionFuResult(UserRepo named username) { user =>
      Form(single(
        "permissions" -> list(nonEmptyText.verifying { str =>
          Permission.allButSuperAdmin.exists(_.name == str)
        })
      )).bindFromRequest.fold(
        err => BadRequest(html.mod.permissions(user)).fuccess,
        permissions =>
          modApi.setPermissions(me.id, user.username, Permission(permissions)) inject
            redirect(user.username, true)
      )
    }
  }

  def emailConfirm = SecureBody(_.SetEmail) { implicit ctx => me =>
    get("q") match {
      case None => Ok(html.mod.emailConfirm("", none, none)).fuccess
      case Some(rawQuery) =>
        val query = rawQuery.trim.split(' ').toList
        val email = query.headOption.map(EmailAddress.apply) flatMap Env.security.emailAddressValidator.validate
        val username = query lift 1
        def tryWith(setEmail: EmailAddress, q: String): Fu[Option[Result]] = Env.mod.search(q) flatMap {
          case List(user) => (!user.everLoggedIn ?? modApi.setEmail(me.id, user.id, setEmail)) >>
            UserRepo.email(user.id) map { email =>
              Ok(html.mod.emailConfirm("", user.some, email)).some
            }
          case _ => fuccess(none)
        }
        email.?? { em =>
          tryWith(em, em.value) orElse {
            username ?? { tryWith(em, _) }
          }
        } getOrElse BadRequest(html.mod.emailConfirm(rawQuery, none, none)).fuccess
    }
  }

  private def withSuspect(username: String)(f: Suspect => Fu[Result])(implicit ctx: Context) =
    Env.report.api getSuspect username flatMap {
      _.fold(notFound)(f)
    }
}
