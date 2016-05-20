package actors

import actors.SenzSender.SenzMsg
import akka.actor.{Actor, Props}
import config.Configuration
import db.PayzDbComp
import org.slf4j.LoggerFactory
import protocols.{Senz, Matm, Trans}
import utils.TransUtils

import scala.concurrent.duration._

case class TransTimeout()

trait TransHandlerComp {

  this: PayzDbComp =>

  object TransHandler {
    def props(trans: Trans): Props = Props(new TransHandler(trans))
  }

  class TransHandler(trans: Trans) extends Actor with Configuration {

    import context._

    def logger = LoggerFactory.getLogger(this.getClass)

    // we need senz sender to send reply back
    val senzSender = context.actorSelection("/user/SenzSender")

    // send message to self in order to init trans
    val transCancellable = system.scheduler.scheduleOnce(0 seconds, self, trans)

    // handle timeout in 5 seconds
    val timeoutCancellable = system.scheduler.scheduleOnce(60 seconds, self, TransTimeout)

    override def preStart() = {
      logger.info("[_________START ACTOR__________] " + context.self.path)
    }

    override def receive: Receive = {
      case trans: Trans =>
        logger.info("InitTrans: [" + trans.fromAcc + "] [" + trans.toAcc + "] [" + trans.amount + "]")

        // check trans exists
        payzDb.getTrans(trans.tId) match {
          case Some(existingTrans) =>
            // already existing trans
            logger.debug("Trans exists, no need to recreate: " + "[" + existingTrans.fromAcc + ", " + existingTrans.toAcc + ", " + existingTrans.amount + "]")
          case None =>
            // new trans, so create and process it
            logger.debug("New Trans, process it: " + "[" + trans.fromAcc + ", " + trans.toAcc + ", " + trans.amount + "]")

            // create trans in db
            payzDb.createTrans(trans)

            // handle according to MATM protocol
            processTransResponse(trans)
        }
      case matm: Matm =>
        matm.acc match {
          case trans.fromAcc =>
            // send by user
            if (matm.key == trans.tKey) {
              // valid key exchange
              val status = processMatm(matm)
              processMatmResponse(status, trans)
            }
          case trans.toAcc =>
            // send by shop
            if (matm.key == trans.fKey) {
              // valid key exchange
              val status = processMatm(matm)
              processMatmResponse(status, trans)
            }
        }
      case TransTimeout =>
        // timeout
        logger.error("TransTimeout")

      // TODO send error back
      // TODO remove actorRef from map

    }

    def processTransResponse(trans: Trans) = {
      senzSender ! SenzMsg(s"DATA #tid ${trans.tId} #key ${trans.fKey} @${trans.fromAcc} ^payzbank}")
      senzSender ! SenzMsg(s"DATA #tid ${trans.tId} #key ${trans.tKey} @${trans.toAcc} ^payzbank}")
    }

    def processMatm(matm: Matm): Option[String] = {
      val trans = payzDb.getTrans(matm.tId)
      trans match {
        case Some(Trans(tId, fromAcc, toAcc, timestamp, amount, fKey, tKey, "INIT")) =>
          // INIT stage
          // update to PENDING
          payzDb.updateTransStatus(Trans(tId, fromAcc, toAcc, timestamp, amount, fKey, tKey, "PENDING"))
          Some("PENDING")
        case Some(Trans(tId, fromAcc, toAcc, timestamp, amount, fKey, tKey, "PENDING")) =>
          // PENDING state
          // update to DONE
          payzDb.updateTransStatus(Trans(tId, fromAcc, toAcc, timestamp, amount, fKey, tKey, "DONE"))
          Some("DONE")
        case _ =>
          None
      }
    }

    def processMatmResponse(status: Option[String], trans: Trans) = {
      status match {
        case Some("DONE") =>
          // transfer money now
          // transfer money is error prone
          try {
            payzDb.transferMoney(trans)
            senzSender ! SenzMsg(s"DATA #msg DONE @${trans.fromAcc} ^payzbank")
            senzSender ! SenzMsg(s"DATA #msg DONE @${trans.toAcc} ^payzbank")
          } catch {
            case ex: Exception =>
              logger.error("Fail to money transfer " + ex)
              senzSender ! SenzMsg(s"DATA #msg FAIL @${trans.fromAcc} ^payzbank")
              senzSender ! SenzMsg(s"DATA #msg FAIL @${trans.toAcc} ^payzbank")
          }

        // send
        case _ =>
        // nothing to do
      }
    }
  }

}
