package com.bitwise.bitmarket.client.handshake

import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.actor.Props
import akka.testkit.TestProbe
import com.google.bitcoin.core.{ECKey, Sha256Hash, Transaction}
import com.google.bitcoin.crypto.TransactionSignature
import org.mockito.BDDMockito.given
import org.scalatest.mock.MockitoSugar

import com.bitwise.bitmarket.client.{Exchange, ProtocolConstants}
import com.bitwise.bitmarket.common.{PeerConnection, AkkaSpec}
import com.bitwise.bitmarket.common.currency.BtcAmount
import com.bitwise.bitmarket.common.protocol._
import com.bitwise.bitmarket.common.protocol.gateway.MessageGateway.{ReceiveMessage, ForwardMessage}

/** Test fixture for testing the handshake actor interaction, one derived class per scenario. */
abstract class DefaultHandshakeActorTest(systemName: String)
  extends AkkaSpec(systemName) with MockitoSugar {

  class MockExchangeHandshake extends ExchangeHandshake {
    override val exchange = Exchange(
      "id",
      PeerConnection("counterpart"),
      PeerConnection("broker"),
      network = null,
      userKey = new ECKey(),
      counterpartKey = null,
      exchangeAmount = BtcAmount(10),
      steps = 10,
      lockTime = 10)
    override val commitmentTransaction = mock[Transaction]
    val commitmentTransactionHash = mock[Sha256Hash]
    override val refundTransaction = mock[Transaction]
    val counterpartRefund = mock[Transaction]
    val refundSignature = mock[TransactionSignature]
    val counterpartRefundSignature = mock[TransactionSignature]
    val counterpartCommitmentTransaction = mock[Transaction]
    val counterpartCommitmentTransactionHash = mock[Sha256Hash]

    override def signCounterpartRefundTransaction(txToSign: Transaction) =
      if (txToSign == counterpartRefund) Success(counterpartRefundSignature)
      else Failure(new Error("Invalid refundSig"))

    override def validateRefundSignature(sig: TransactionSignature) =
      if (sig == refundSignature) Success(()) else Failure(new Error("Invalid signature!"))

    given(commitmentTransaction.getHash).willReturn(commitmentTransactionHash)
    given(counterpartCommitmentTransaction.getHash).willReturn(counterpartCommitmentTransactionHash)
  }

  def protocolConstants: ProtocolConstants

  val handshake = new MockExchangeHandshake
  val listener = TestProbe()
  val gateway = TestProbe()
  val blockchain = TestProbe()
  val actor = system.actorOf(Props(new DefaultHandshakeActor(handshake,
    gateway.ref, blockchain.ref, protocolConstants, Seq(listener.ref))
  ), "handshake-actor")
  listener.watch(actor)

  def shouldForwardRefundSignatureRequest() {
    shouldForwardToCounterpart(RefundTxSignatureRequest("id", handshake.refundTransaction))
  }

  def shouldSignCounterpartRefund() {
    val request = RefundTxSignatureRequest("id", handshake.counterpartRefund)
    gateway.send(actor, ReceiveMessage(request, handshake.exchange.counterpart))
    shouldForwardToCounterpart(RefundTxSignatureResponse("id", handshake.counterpartRefundSignature))
  }

  def shouldForwardToCounterpart[T : MessageSend](message: T) {
    gateway.expectMsg(ForwardMessage(message, handshake.exchange.counterpart))
  }

  def shouldForwardToBroker[T : MessageSend](message: T) {
    gateway.expectMsg(ForwardMessage(message, handshake.exchange.broker))
  }

  def fromCounterpart(message: Any) = ReceiveMessage(message, handshake.exchange.counterpart)

  def fromBroker(message: Any) = ReceiveMessage(message, handshake.exchange.broker)
}