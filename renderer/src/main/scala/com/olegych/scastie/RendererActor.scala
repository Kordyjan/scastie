package com.olegych.scastie

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.event.LoggingReceive
import com.olegych.scastie.FailuresActor.{AddFailure, FatalFailure}
import com.olegych.scastie.PastesActor.Paste

import scala.concurrent._
import scala.concurrent.duration._
import scalaz.Scalaz._

import upickle.default.{read => uread}

/**
  */
case class RendererActor(failures: ActorRef) extends Actor with ActorLogging {
  private val killer = createKiller(2.minutes)
  private val runKiller = createKiller(30.seconds)

  private def applyRunKiller(paste: Paste)(block: => Unit) {
    runKiller { case _ => block} apply paste
  }

  private def createKiller(timeout: FiniteDuration): (Actor.Receive) => Actor.Receive = {
    TimeoutActor(timeout, message => {
      message match {
        case paste: Paste => sender ! paste
          .copy(output = Seq(s"Killed because of timeout $timeout"), content = None)
        case _ => log.info("unknown message {}", message)
      }
      preRestart(FatalFailure, Some(message))
    })
  }

  private def generateId: String = scala.util.Random.alphanumeric.take(10).mkString

  private val sbtDir = PastesContainer(new java.io.File(System.getProperty("java.io.tmpdir"))).renderer(generateId)

  private var sbt: Option[Sbt] = None
  private var settings = ""
  private var reloadResult: Seq[String] = Seq()

  override def preStart() {
    sbt = blocking {Option(RendererTemplate.create(sbtDir.root, generateId))}
    settings = ""
    reloadResult = Seq()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    super.preRestart(reason, message)
    message.collect {
      case message@Paste(_, content, _, _, _) => failures ! AddFailure(reason, message, sender, content)
    }
  }

  override def postStop() {
    log.info("stopping sbt")
    sbt.foreach(_.close())
  }

  def receive = LoggingReceive {
    killer { case paste@Paste(_, Some(content), _, _, _) => sbt foreach { sbt =>
      // def sendPasteFile(result: Seq[String]) {
      //   sender ! paste.copy(content = sbtDir.pasteFile.read, output = result)
      // }
      sbtDir.pasteFile.write(Option(content))
      val settings = paste.settings
      if (this.settings =/= settings) {
        this.settings = settings
        sbt.process("reload", (line, _) =>
          sender ! paste.copy(content = sbtDir.pasteFile.read, output = line +: paste.output)
        )
      } else {
        sender ! paste.copy(content = sbtDir.pasteFile.read, output = Seq())
      }

      // sendPasteFile(reloadResult)
      sbtDir.sxrSource.delete()
      sbt.process("compile", (line, done) => {
        println(line)
        if(done) {
          sender ! paste.copy(
            content = sbtDir.pasteFile.read
            , renderedContent = sbtDir.sxrSource.read.map(cleanSource)
            , output = Seq("Now running...")
          )
          applyRunKiller(paste) {
            sbt.process("run-all", (line, done) =>
              sender ! paste.copy(output = line +: paste.output)
            )
          }
        } else {
          println("still going")

          val problems =
            try{ Right(uread[sbtapi.Problem](line)) }
            catch { case scala.util.control.NonFatal(e) => Left(e) }

          println(problems)

          sender ! paste.copy(output = line +: paste.output)
        }
        // line match {
        //   case sbt.Success(compileResult) => {
        //     println("sucess")
        //
        //   }
        //   case _ => {
        //
        //   }
        // }
          // sendPasteFile(errorResult)
      })
    }
    }
  }

  def cleanSource(sxrSource: String): String = {
    sxrSource.replaceFirst("^(?mis).*<body>", "").replaceFirst("(?mis)</body>\\s*</html>$", "")
  }
}
