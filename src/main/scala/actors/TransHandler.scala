package actors

import actors.SenzSender.SenzMsg
import akka.actor.{Actor, Props}
import components.TransDbComp
import config.Configuration
import org.slf4j.LoggerFactory
import protocols.Trans
import utils.TransUtils

import scala.concurrent.duration._

case class TransMsg(msgStream: Array[Byte])

case class TransResp(esh: String, status: String, rst: String)

case class TransTimeout()

trait TransHandlerComp {

  this: TransDbComp =>

  object TransHandler {
    def props(trans: Trans): Props = Props(new TransHandler(trans))
  }

  class TransHandler(trans: Trans) extends Actor with Configuration {

    import context._

    def logger = LoggerFactory.getLogger(this.getClass)

    // we need senz sender to send reply back
    val senzSender = context.actorSelection("/user/SenzSender")

    // handle timeout in 5 seconds
    val timeoutCancellable = system.scheduler.scheduleOnce(5 seconds, self, TransTimeout)

    override def preStart() = {
      logger.debug("Start actor: " + context.self.path)
    }

    override def receive: Receive = {
      case TransTimeout =>
        // timeout
        logger.error("TransTimeout")
        handleResponse("response")
    }

    def handleResponse(response: String) = {
      // parse response and get 'TransResp'
      TransUtils.getTransResp(response) match {
        case TransResp(_, "00", _) =>
          logger.debug("Transaction done")
        case TransResp(_, status, _) =>
          logger.error("Transaction fail with stats: " + status)
        case transResp =>
          logger.error("Invalid response " + transResp)
      }

      // update db
      // TODO update according to the status
      transDb.updateTrans(Trans(trans.agent, trans.customer, trans.amount, trans.timestamp, "DONE"))

      // send status back
      // TODO status according to the response
      val senz = s"DATA #msg PUTDONE @${trans.agent} ^sdbltrans"
      senzSender ! SenzMsg(senz)
    }
  }

}
