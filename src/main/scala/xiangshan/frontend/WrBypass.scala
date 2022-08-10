/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/
package xiangshan.frontend

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import chisel3.experimental.chiselName
import xiangshan.cache.mmu.CAMTemplate

class WrBypass[T <: Data](gen: T, val numEntries: Int, val idxWidth: Int,
  val numWays: Int = 1, val tagWidth: Int = 0, val hasWr2sram: Boolean = false)(implicit p: Parameters) extends XSModule {
  require(numEntries >= 0)
  require(idxWidth > 0)
  require(numWays >= 1)
  require(tagWidth >= 0)
  def hasTag = tagWidth > 0
  def multipleWays = numWays > 1
  val io = IO(new Bundle {
    // write wrbypass
    val wen = Input(Bool())
    val write_idx = Input(UInt(idxWidth.W))
    val write_tag = if (hasTag) Some(Input(UInt(tagWidth.W))) else None
    val write_data = Input(Vec(numWays, gen))
    val write_way_mask = if (multipleWays) Some(Input(Vec(numWays, Bool()))) else None

    val hit = Output(Bool())
    val hit_data = Vec(numWays, Valid(gen))

    // read wrbypass
    val ren = if (hasWr2sram) Some(Input(Bool())) else None
    val read_idx = if (hasWr2sram) Some(Input(UInt(idxWidth.W))) else None
    val read_tag = if (hasTag && hasWr2sram) Some(Input(UInt(tagWidth.W))) else None
    val read_hit = Output(Bool())
    val read_data = Output(Vec(numWays, Valid(gen)))

    // wrbypass write sram
    val by2sram = Output(Bool())
    val pending_sram_idx_tag = Output(new Idx_Tag)
    val pending_sram_data = Output(Vec(numWays, gen))
    val pending_false = if (hasWr2sram) Some(Input(Bool())) else None
  })

  class WrBypassPtr extends CircularQueuePtr[WrBypassPtr](numEntries){
  }

  class Idx_Tag extends Bundle {
    val idx = UInt(idxWidth.W)
    val tag = if (hasTag) Some(UInt(tagWidth.W)) else None
    def apply(idx: UInt, tag: UInt) = {
      this.idx := idx
      this.tag.map(_ := tag)
    }
  }
  val idx_tag_cam = Module(new CAMTemplate(new Idx_Tag, numEntries, 2))
  val data_mem = Mem(numEntries, Vec(numWays, gen))
  val idx_tag_mem = Mem(numEntries, new Idx_Tag)
  val pending_write_to_sram = RegInit(VecInit(Seq.fill(numEntries)(0.B)))

  val valids = RegInit(0.U.asTypeOf(Vec(numEntries, Vec(numWays, Bool()))))

  val enq_ptr = RegInit(0.U.asTypeOf(new WrBypassPtr))
  val enq_idx = enq_ptr.value


  //write hit
  idx_tag_cam.io.r.req(0)(io.write_idx, io.write_tag.getOrElse(0.U))
  val hits_oh = idx_tag_cam.io.r.resp(0)
  val hit_idx = OHToUInt(hits_oh)
  val hit = hits_oh.reduce(_||_)

  io.hit := hit
  for (i <- 0 until numWays) {
    io.hit_data(i).valid := Mux1H(hits_oh, valids)(i)
    io.hit_data(i).bits  := data_mem.read(hit_idx)(i)
  }

  // read hit
  idx_tag_cam.io.r.req(1)(io.read_idx.getOrElse(0.U), io.read_tag.getOrElse(0.U))
  val hits_oh_r = idx_tag_cam.io.r.resp(1)
  val hit_idx_r = OHToUInt(hits_oh_r)
  val hit_r = hits_oh_r.reduce(_||_)

  io.read_hit := hit_r
  for (i <- 0 until numWays) {
    io.read_data(i).valid := Mux1H(hits_oh_r, valids)(i)
    io.read_data(i).bits  := data_mem.read(hit_idx_r)(i)
  }

  

  io.by2sram := pending_write_to_sram.reduce(_||_)
  val pending_bypass_idx = WireDefault(0.U(log2Up(numEntries).W))
  for (i <- 0 until numEntries) {
    when(pending_write_to_sram(i)) { pending_bypass_idx := i.U }
  }
  io.pending_sram_idx_tag := idx_tag_mem.read(pending_bypass_idx)
  io.pending_sram_data := data_mem.read(pending_bypass_idx)
  when(io.pending_false.getOrElse(false.B)) { pending_write_to_sram(pending_bypass_idx) := false.B }
  assert(!(io.pending_false.getOrElse(false.B) && io.ren.getOrElse(false.B)))

  val full_mask = Fill(numWays, 1.U(1.W)).asTypeOf(Vec(numWays, Bool()))
  val update_way_mask = io.write_way_mask.getOrElse(full_mask)

  // write data on every request
  val idx_tag_wdata = Wire(new Idx_Tag)
  idx_tag_wdata.idx := io.write_idx
  if (hasTag) { idx_tag_wdata.tag.get := io.write_tag.get }

  when (io.wen) {
    val data_write_idx = Mux(hit, hit_idx, enq_idx)
    data_mem.write(data_write_idx, io.write_data, update_way_mask)
    idx_tag_mem.write(data_write_idx, idx_tag_wdata)
    when(!io.ren.getOrElse(false.B)) {
      when(!hit) { pending_write_to_sram(data_write_idx) := false.B }
    }
    .otherwise { pending_write_to_sram(data_write_idx) := true.B }
  }


  // update valids
  for (i <- 0 until numWays) {
    when (io.wen) {
      when (hit) {
        when (update_way_mask(i)) {
          valids(hit_idx)(i) := true.B
        }
      }.otherwise {
        valids(enq_idx)(i) := false.B
        when (update_way_mask(i)) {
          valids(enq_idx)(i) := true.B
        }
      }
    }
  }

  val enq_en = io.wen && !hit
  idx_tag_cam.io.w.valid := enq_en
  idx_tag_cam.io.w.bits.index := enq_idx
  idx_tag_cam.io.w.bits.data(io.write_idx, io.write_tag.getOrElse(0.U))
  if (hasWr2sram) {
    enq_ptr := Mux(enq_en, MuxCase(enq_ptr + 1.U, (1 until numEntries).map(i => (!pending_write_to_sram((enq_ptr + i.U).value), enq_ptr + i.U))), enq_ptr)
  } else { enq_ptr := enq_ptr + enq_en }

  XSPerfAccumulate("wrbypass_wen",  io.wen)
  XSPerfAccumulate("wrbypass_hit",  io.wen &&  hit)
  XSPerfAccumulate("wrbypass_miss", io.wen && !hit)

  XSPerfAccumulate("hit_over_pending", io.wen && hit && pending_write_to_sram(hit_idx))
  XSPerfAccumulate("hit_over_pending", io.wen && hit && pending_write_to_sram(hit_idx))
  XSPerfAccumulate("enq_over_pending", io.wen && !hit && pending_write_to_sram(enq_idx))
  XSPerfAccumulate("read_write_sameTime", io.wen && io.ren.getOrElse(0.B))

  XSDebug(io.wen && hit,  p"wrbypass hit entry #${hit_idx}, idx ${io.write_idx}" +
    p"tag ${io.write_tag.getOrElse(0.U)}data ${io.write_data}\n")
  XSDebug(io.wen && !hit, p"wrbypass enq entry #${enq_idx}, idx ${io.write_idx}" +
    p"tag ${io.write_tag.getOrElse(0.U)}data ${io.write_data}\n")
}
