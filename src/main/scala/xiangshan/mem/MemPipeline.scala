package xiangshan.mem

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import chisel3.util.experimental.BoringUtils
import xiangshan.backend.decode.XSTrap
import xiangshan.mem.cache._
import xiangshan.mem.pipeline._
import bus.simplebus._

trait HasMEMConst{
  val LoadPipelineWidth = 2
  val StorePipelineWidth = 2
  val LSRoqSize = 64
  val StoreBufferSize = 16
  val RefillSize = 512
  val DcacheUserBundleWidth = (new DcacheUserBundle).getWidth
}

class MemToBackendIO extends XSBundle {
  val ldin = Vec(2, Flipped(Decoupled(new LduReq)))
  val stin = Vec(2, Flipped(Decoupled(new StuReq)))
  val out = Vec(2, Decoupled(new ExuOutput))
  val redirect = Flipped(ValidIO(new Redirect))
  val rollback = ValidIO(new Redirect)
}

class Memend(implicit val p: XSConfig) extends XSModule with HasMEMConst with NeedImpl{
  val io = IO(new Bundle{
    val backend = new MemToBackendIO
    val dmem = new SimpleBusUC(userBits = DcacheUserBundleWidth)
  })

  val lsu = Module(new Lsu)
  val dcache = Module(new Dcache)
  // val mshq = Module(new MSHQ)
  val dtlb = Module(new Dtlb)
  
  dcache.io := DontCare
  dtlb.io := DontCare
  // mshq.io := DontCare

  lsu.io.ldin <> io.backend.ldin
  lsu.io.stin <> io.backend.stin
  lsu.io.out <> io.backend.out
  lsu.io.redirect <> io.backend.redirect
  lsu.io.rollback <> io.backend.rollback
  lsu.io.dcache <> dcache.io.lsu
  lsu.io.dtlb <> dtlb.io.lsu

  // for ls pipeline test
  dcache.io.dmem <> io.dmem

}