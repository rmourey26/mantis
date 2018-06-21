package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.crypto.zksnark.BN128Fp
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.utils.ByteUtils


import scala.util.Try

// scalastyle:off magic.number
object PrecompiledContracts {

  val EcDsaRecAddr = Address(1)
  val Sha256Addr = Address(2)
  val Rip160Addr = Address(3)
  val IdAddr = Address(4)
  val ModExpAddr = Address(5)
  val Bn128AddAddr = Address(6)
  val Bn128MulAddr = Address(7)

  val contracts = Map(
    EcDsaRecAddr -> EllipticCurveRecovery,
    Sha256Addr -> Sha256,
    Rip160Addr -> Ripemp160,
    IdAddr -> Identity
  )

  val byzantiumContracts = contracts ++ Map(
    ModExpAddr -> ModExp,
    Bn128AddAddr -> Bn128Add,
    Bn128MulAddr -> Bn128Mul
  )
  /**
    * Checks whether `ProgramContext#recipientAddr` points to a precompiled contract
    */
  def isDefinedAt(context: ProgramContext[_, _]): Boolean =
    if (context.blockHeader.number >= context.evmConfig.blockchainConfig.byzantiumBlockNumber){
      context.recipientAddr.exists(byzantiumContracts.isDefinedAt)
    } else
      context.recipientAddr.exists(contracts.isDefinedAt)

  /**
    * Runs a contract for address provided in `ProgramContext#recipientAddr`
    * Will throw an exception if the address does not point to a precompiled contract - callers should first
    * check with `isDefinedAt`
    */
  def run[W <: WorldStateProxy[W, S], S <: Storage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] =
    if (context.blockHeader.number >= context.evmConfig.blockchainConfig.byzantiumBlockNumber)
      byzantiumContracts(context.recipientAddr.get).run(context)
    else
      contracts(context.recipientAddr.get).run(context)


  sealed trait PrecompiledContract {
    protected def exec(inputData: ByteString): Option[ByteString]
    protected def gas(inputData: ByteString): BigInt

    def run[W <: WorldStateProxy[W, S], S <: Storage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] = {
      val g = gas(context.inputData)

      val (result, error, gasRemaining): (ByteString, Option[ProgramError], BigInt) =
        if (g <= context.startGas)
          exec(context.inputData) match {
            case Some(returnData) => (returnData, None, context.startGas - g)
            case None => (ByteString.empty, Some(PreCompiledContractFail), 0)
          }
        else
          (ByteString.empty, Some(OutOfGas), 0)

      ProgramResult(
        result,
        gasRemaining,
        context.world,
        Set.empty,
        Nil,
        Nil,
        0,
        error
      )
    }
  }

  object EllipticCurveRecovery extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] = {
      val data = inputData.padTo(128, 0.toByte)
      val h = data.slice(0, 32)
      val v = data.slice(32, 64)
      val r = data.slice(64, 96)
      val s = data.slice(96, 128)

      if (hasOnlyLastByteSet(v)) {
        val recovered = Try(ECDSASignature(r, s, v.last).publicKey(h)).getOrElse(None)
        Some(recovered.map { bytes =>
          val hash = kec256(bytes).slice(12, 32)
          ByteUtils.padLeft(hash, 32)
        }.getOrElse(ByteString.empty))
      } else
        Some(ByteString.empty)

    }

    def gas(inputData: ByteString): BigInt =
      3000

    private def hasOnlyLastByteSet(v: ByteString): Boolean =
      v.dropWhile(_ == 0).size == 1
  }

  object Sha256 extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Some(sha256(inputData))

    def gas(inputData: ByteString): BigInt =
      60 + 12 * wordsForBytes(inputData.size)
  }

  object Ripemp160 extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Some(ByteUtils.padLeft(ripemd160(inputData), 32))

    def gas(inputData: ByteString): BigInt =
      600 + 120 * wordsForBytes(inputData.size)
  }

  object Identity extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Some(inputData)

    def gas(inputData: ByteString): BigInt =
      15 + 3 * wordsForBytes(inputData.size)
  }

  //Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-198.md
  object ModExp extends PrecompiledContract {

    private val lengthBytes = 32
    private val totalLengthBytes = 3 * lengthBytes
    private val GQUADDIVISOR = 20

    def exec(inputData: ByteString): Option[ByteString] = {
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      if (baseLength == 0 && modLength == 0)
        Some(ByteString.empty)
      else {
        val mod = getNumber(inputData, safeAdd(totalLengthBytes, safeAdd(baseLength, expLength)), modLength)

        if (mod == 0) {
          Some(ByteString.empty)
        } else {
          val base = getNumber(inputData, totalLengthBytes, baseLength)
          val exp = getNumber(inputData, safeAdd(totalLengthBytes, baseLength), expLength)

          val result = base.modPow(exp, mod)
          Some(ByteString(ByteUtils.bigIntegerToBytes(result.bigInteger, modLength)))
        }
      }
    }

    def gas(inputData: ByteString): BigInt = {
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val expBytes =
        inputData.slice(
          safeAdd(totalLengthBytes, baseLength),
          safeAdd(safeAdd(totalLengthBytes, baseLength), expLength))

      val multComplexity = getMultComplexity(math.max(baseLength, modLength))

      val adjExpLen = adjExpLength(expBytes, expLength)

      multComplexity * math.max(adjExpLen , 1) / GQUADDIVISOR
    }

    private def adjExpLength(expBytes: ByteString, expLength: Int): Long = {
      val expHead =
        if (expLength <= lengthBytes)
          expBytes.padTo(expLength, 0.toByte)
        else
          expBytes.take(lengthBytes).padTo(lengthBytes, 0.toByte)


      val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

      if (expLength <= lengthBytes) {
          highestBitIndex
      } else {
          8L * (expLength - lengthBytes) + highestBitIndex
      }
    }

    private def getLength(bytes: ByteString, position: Int): Int = {
      val start = position * lengthBytes
      safeInt(ByteUtils.toBigInt(bytes.slice(start, start + lengthBytes)))
    }

    private def getNumber(bytes: ByteString, offset: Int, length: Int): BigInt = {
      val number = bytes.slice(offset, safeAdd(offset, length)).padTo(length, 0.toByte)
      ByteUtils.toBigInt(number)
    }

    private def safeInt(value: BigInt): Int =
      if (value.isValidInt)
        value.toInt
      else
        Integer.MAX_VALUE

    private def safeAdd(a: Int, b: Int): Int = {
      safeInt(BigInt(a) + BigInt(b))
    }

    private def getMultComplexity(x: BigInt): BigInt = {
      val x2 = x * x

      if (x <= 64)
        x2
      else if (x <= 1024)
        x2 / 4 + 96 * x - 3072
      else
        x2 / 16 + 480 * x - 199680
    }
  }
  //Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
  object Bn128Add extends PrecompiledContract {
    val expectedBytes = 4 * 32

    def exec(inputData: ByteString): Option[ByteString] = {
      val paddedInput = inputData.padTo(expectedBytes, 0.toByte)
      val (x1, y1, x2, y2) = getCurvePointsBytes(paddedInput)

      val result =  for {
        p1 <- BN128Fp.createPointOnCurve(x1, y1)
        p2 <- BN128Fp.createPointOnCurve(x2, y2)
        p3 = BN128Fp.toEthNotation(BN128Fp.add(p1, p2))
      } yield p3

      result.map {point =>
        val xBytes = ByteUtils.bigIntegerToBytes(point.x.inner.bigInteger, 32)
        val yBytes = ByteUtils.bigIntegerToBytes(point.y.inner.bigInteger, 32)
        ByteString(xBytes ++ yBytes)
      }
    }


    def gas(inputData: ByteString): BigInt =
      BigInt(500)


    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString, ByteString) = {
      (input.slice(0, 32),
        input.slice(32, 64),
        input.slice(64, 96),
        input.slice(96, 128))
    }

  }

  //Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
  object Bn128Mul extends PrecompiledContract {
    val expectedBytes = 3 * 32
    val maxScalar = BigInt(2).pow(256) - 1

    def exec(inputData: ByteString): Option[ByteString] = {
      val paddedInput = inputData.padTo(expectedBytes, 0.toByte)
      val (x1, y1, scalarBytes) = getCurvePointsBytes(paddedInput)

      val scalar = ByteUtils.toBigInt(scalarBytes)

      val result = for {
        p <- BN128Fp.createPointOnCurve(x1, y1)
        s <- if (scalar <= maxScalar) Some(scalar) else None
        p3 = BN128Fp.toEthNotation(BN128Fp.mul(p, s))
      } yield p3

      result.map {point =>
        val xBytes = ByteUtils.bigIntegerToBytes(point.x.inner.bigInteger, 32)
        val yBytes = ByteUtils.bigIntegerToBytes(point.y.inner.bigInteger, 32)
        ByteString(xBytes ++ yBytes)
      }
    }

    def gas(inputData: ByteString): BigInt =
      40000

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString) = {
      (input.slice(0, 32),
        input.slice(32, 64),
        input.slice(64, 96))
    }
  }

}
