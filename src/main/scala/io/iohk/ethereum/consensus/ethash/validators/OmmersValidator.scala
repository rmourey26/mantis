package io.iohk.ethereum.consensus.ethash.validators

import akka.util.ByteString
import io.iohk.ethereum.consensus.GetBlockHeaderByHash
import io.iohk.ethereum.consensus.GetNBlocksBack
import io.iohk.ethereum.consensus.ethash.validators.OmmersValidator.OmmersError
import io.iohk.ethereum.consensus.ethash.validators.OmmersValidator.OmmersValid
import io.iohk.ethereum.domain.Block
import io.iohk.ethereum.domain.BlockHeader
import io.iohk.ethereum.domain.Blockchain

trait OmmersValidator {

  def validate(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      getBlockByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  ): Either[OmmersError, OmmersValid]

  def validate(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      blockchain: Blockchain
  ): Either[OmmersError, OmmersValid] = {

    val getBlockHeaderByHash: ByteString => Option[BlockHeader] = blockchain.getBlockHeaderByHash
    val getNBlocksBack: (ByteString, Int) => List[Block] =
      (_, n) => ((blockNumber - n) until blockNumber).toList.flatMap(blockchain.getBlockByNumber)

    validate(parentHash, blockNumber, ommers, getBlockHeaderByHash, getNBlocksBack)
  }

}

object OmmersValidator {
  sealed trait OmmersError

  object OmmersError {
    case object OmmersLengthError extends OmmersError
    case object OmmersNotValidError extends OmmersError
    case object OmmersUsedBeforeError extends OmmersError
    case object OmmersAncestorsError extends OmmersError
    case object OmmersDuplicatedError extends OmmersError
  }

  sealed trait OmmersValid
  case object OmmersValid extends OmmersValid
}
