package hwacha

import Chisel._
import Node._
import Constants._
import Packing._
import DataGating._
import HardFloatHelper._

class LaneFConvResult extends Bundle
{
  val out = Bits(OUTPUT, SZ_D)
  val exc = Bits(OUTPUT, rocket.FPConstants.FLAGS_SZ)
}

class LaneFConvSlice extends HwachaModule
{
  val io = new Bundle {
    val req = Valid(new Bundle {
      val fn = new VFCUFn
      val in = Bits(INPUT, SZ_D)
    }).flip
    val resp = Valid(new LaneFConvResult)
  }

  val fn = io.req.bits.fn.dgate(io.req.valid)
  val in = dgate(io.req.valid, io.req.bits.in)

  val op_int2float = MuxCase(
    Bits(0), Array(
      fn.op_is(FC_CLTF)  -> hardfloat.consts.type_int64,
      fn.op_is(FC_CLUTF) -> hardfloat.consts.type_uint64,
      fn.op_is(FC_CWTF)  -> hardfloat.consts.type_int32,
      fn.op_is(FC_CWUTF) -> hardfloat.consts.type_uint32
    ))

  val op_float2int = MuxCase(
    Bits(0), Array(
      fn.op_is(FC_CFTL)  -> hardfloat.consts.type_int64,
      fn.op_is(FC_CFTLU) -> hardfloat.consts.type_uint64,
      fn.op_is(FC_CFTW)  -> hardfloat.consts.type_int32,
      fn.op_is(FC_CFTWU) -> hardfloat.consts.type_uint32
    ))

  val val_int2float = fn.op_is(FC_CLTF,FC_CLUTF,FC_CWTF,FC_CWUTF)
  val val_float2int32 = fn.op_is(FC_CFTL,FC_CFTLU)
  val val_float2int64 = fn.op_is(FC_CFTW,FC_CFTWU)
  val val_float2int = val_float2int32 || val_float2int64

  val wdp = (52, 12)
  val wsp = (23, 9)
  val whp = (10, 6)

  val results_int2float =
    List((FPD, ieee_dp _, expand_float_d _, wdp),
         (FPS, ieee_sp _, expand_float_s _, wsp),
         (FPH, ieee_hp _, expand_float_h _, whp)) map {
      case (fp, ieee, expand, (sig, exp)) => {
        val valid = fn.fp_is(fp) && val_int2float
        val input = dgate(valid, in)
        val rm = dgate(valid, fn.rm)
        val op = dgate(valid, op_int2float)
        val result = hardfloat.anyToRecodedFloatN(input, rm, op, sig, exp, SZ_D)
        (expand(ieee(result._1)), result._2)
      }
    }

  val results_float2int =
    List((FPD, recode_dp _, unpack_d _, wdp),
         (FPS, recode_sp _, unpack_w _, wsp),
         (FPH, recode_hp _, unpack_h _, whp)) map {
      case (fp, recode, unpack, (sig, exp)) => {
        val valid = fn.fp_is(fp) && val_float2int
        val input = recode(dgate(valid, unpack(in, 0)))
        val rm = dgate(valid, fn.rm)
        val op = dgate(valid, op_float2int)
        val result = hardfloat.recodedFloatNToAny(input, rm, op, sig, exp, SZ_D)
        (Mux(val_float2int32, expand_w(result._1(31, 0)), result._1), result._2)
      }
    }

  val results_float2float =
    List((FC_CSTD, recode_sp _, unpack_w _, ieee_dp _, expand_float_d _, wsp, wdp),
         (FC_CHTD, recode_hp _, unpack_h _, ieee_dp _, expand_float_d _, whp, wdp),
         (FC_CDTS, recode_dp _, unpack_d _, ieee_sp _, expand_float_s _, wdp, wsp),
         (FC_CHTS, recode_hp _, unpack_h _, ieee_sp _, expand_float_s _, whp, wsp),
         (FC_CDTH, recode_dp _, unpack_d _, ieee_hp _, expand_float_h _, wdp, whp),
         (FC_CSTH, recode_sp _, unpack_w _, ieee_hp _, expand_float_h _, wsp, whp)) map {
      case (op, recode, unpack, ieee, expand, (sigs, exps), (sigd, expd)) => {
        val valid = fn.op_is(op)
        val input = recode(dgate(valid, unpack(in, 0)))
        val rm = dgate(valid, fn.rm)
        val result = hardfloat.recodedFloatNToRecodedFloatM(input, rm, sigs, exps, sigd, expd)
        (expand(ieee(result._1)), result._2)
      }
    }

  val outs =
    List((FC_CSTD, FC_CHTD), (FC_CDTS, FC_CHTS), (FC_CDTH, FC_CSTH)).zipWithIndex.map {
      case ((op0, op1), i) =>
        MuxCase(Bits(0), Array(
          val_int2float -> results_int2float(i)._1,
          val_float2int -> results_float2int(i)._1,
          fn.op_is(op0) -> results_float2float(2*i)._1,
          fn.op_is(op1) -> results_float2float(2*i+1)._1
        ))
    }

  val excs =
    List((FC_CSTD, FC_CHTD), (FC_CDTS, FC_CHTS), (FC_CDTH, FC_CSTH)).zipWithIndex.map {
      case ((op0, op1), i) =>
        MuxCase(Bits(0), Array(
          val_int2float -> results_int2float(i)._2,
          val_float2int -> results_float2int(i)._2,
          fn.op_is(op0) -> results_float2float(2*i)._2,
          fn.op_is(op1) -> results_float2float(2*i+1)._2
        ))
    }

  val fpmatch = List(FPD, FPS, FPH).map { fn.fp_is(_) }
  val result = new LaneFConvResult
  result.out := Mux1H(fpmatch, outs)
  result.exc := Mux1H(fpmatch, excs)

  io.resp := Pipe(io.req.valid, result, fconv_stages)
}
