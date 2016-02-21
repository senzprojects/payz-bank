package actors

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{Actor, ActorRef, Props}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import components.TransDbComp
import config.Configuration
import org.slf4j.LoggerFactory
import protocols.Trans

import scala.concurrent.duration._

case class TransMsg(msg: String)

case class TransTimeout()

trait TransHandlerComp {

  this: TransDbComp =>

  object TransHandler {
    def props(transMsg: TransMsg): Props = Props(new TransHandler(transMsg))
  }

  class TransHandler(transMsg: TransMsg) extends Actor with Configuration {

    import context._

    def logger = LoggerFactory.getLogger(this.getClass)

    // we need senz sender to send reply back
    val senzSender = context.actorSelection("/user/SenzSender")

    // handle timeout in 4 seconds
    val cancellable = system.scheduler.schedule(0 milliseconds, 4 seconds, self, TransTimeout)

    // connect to epic tcp end
    val remoteAddress = new InetSocketAddress(InetAddress.getByName(epicHost), epicPort)
    IO(Tcp) ! Connect(remoteAddress)

    override def preStart = {
      logger.debug("Start actor: " + context.self.path)
    }

    override def receive: Receive = {
      case c@Connected(remote, local) =>
        logger.debug("TCP connected")

        // send TransMsg
        val connection = sender()
        connection ! Register(self)
        connection ! Write(ByteString(transMsg.msg))

        logger.debug("Send TransMsg " + transMsg.msg)

        // handler response
        context become {
          case CommandFailed(w: Write) =>
            logger.error("CommandFailed[Failed to write]")
          case Received(data) =>
            val response = data.decodeString("UTF-8")
            logger.debug("Received :" + response)

            handleResponse(response, connection)
          case "close" =>
            logger.debug("Close")
            connection ! Close
          case _: ConnectionClosed =>
            logger.debug("ConnectionClosed")
            context stop self
        }
      case CommandFailed(_: Connect) =>
        // TODO may be reconnect
        logger.error("CommandFailed[Failed to connect]")
      case TransTimeout =>
        // TODO may be resend trans
        logger.error("TransTimeout")
    }

    def handleResponse(response: String, connection: ActorRef) = {
      // update db
      transDb.createTrans(Trans("sdf", "sdf", "sdf", "sdf", "sdf"))

      // send status back
      senzSender ! SendSenz("DATA #msg #SHAREDONE")

      // disconnect from tcp
      connection ! Close
    }
  }

}