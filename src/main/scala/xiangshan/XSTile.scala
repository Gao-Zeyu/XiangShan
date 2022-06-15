package xiangshan

import chipsalliance.rocketchip.config.{Config, Parameters}
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{BundleBridgeSink, LazyModule, LazyModuleImp, LazyRawModuleImp}
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.GenericLogicalTreeNode
import freechips.rocketchip.tile.{BusErrorUnit, BusErrorUnitParams, BusErrors}
import freechips.rocketchip.tilelink.{BankBinder, TLBuffer, TLIdentityNode, TLTempNode, TLXbar}
import huancun.debug.TLLogger
import huancun.mbist._
import huancun.utils.ResetGen
import huancun.{HCCacheParamsKey, HuanCun}
import system.HasSoCParameter
import top.BusPerfMonitor
import utils._

class L1BusErrorUnitInfo(implicit val p: Parameters) extends Bundle with HasSoCParameter {
  val ecc_error = Valid(UInt(soc.PAddrBits.W))
}

class XSL1BusErrors()(implicit val p: Parameters) extends BusErrors {
  val icache = new L1BusErrorUnitInfo
  val dcache = new L1BusErrorUnitInfo
  val l2 = new L1BusErrorUnitInfo

  override def toErrorList: List[Option[(ValidIO[UInt], String, String)]] =
    List(
      Some(icache.ecc_error, "I_ECC", "Icache ecc error"),
      Some(dcache.ecc_error, "D_ECC", "Dcache ecc error"),
      Some(l2.ecc_error, "L2_ECC", "L2Cache ecc error")
    )
}

/**
  *   XSTileMisc contains every module except Core and L2 Cache
  */
class XSTileMisc()(implicit p: Parameters) extends LazyModule
  with HasXSParameter
  with HasSoCParameter
{
  val l1_xbar = TLXbar()
  val mmio_xbar = TLXbar()
  val mmio_port = TLIdentityNode() // to L3
  val memory_port = TLIdentityNode()
  val beu = LazyModule(new BusErrorUnit(
    new XSL1BusErrors(), BusErrorUnitParams(0x1f10010000L), new GenericLogicalTreeNode
  ))
  val busPMU = BusPerfMonitor(enable = !debugOpts.FPGAPlatform)
  val l1d_logger = TLLogger(s"L2_L1D_${coreParams.HartId}", !debugOpts.FPGAPlatform)
  val l2_binder = coreParams.L2CacheParamsOpt.map(_ => BankBinder(coreParams.L2NBanks, 64))

  val i_mmio_port = TLTempNode()
  val d_mmio_port = TLTempNode()

  busPMU := l1d_logger
  l1_xbar :=* busPMU

  l2_binder match {
    case Some(binder) =>
      memory_port := TLBuffer.chainNode(2) := TLClientsMerger() := TLXbar() :=* binder
    case None =>
      memory_port := l1_xbar
  }

  mmio_xbar := TLBuffer.chainNode(2) := i_mmio_port
  mmio_xbar := TLBuffer.chainNode(2) := d_mmio_port
  beu.node := TLBuffer.chainNode(1) := mmio_xbar
  mmio_port := TLBuffer() := mmio_xbar

  lazy val module = new LazyModuleImp(this){
    val beu_errors = IO(Input(chiselTypeOf(beu.module.io.errors)))
    beu.module.io.errors <> beu_errors
  }
}

class XSTile(parenName:String = "Unknown")(implicit p: Parameters) extends LazyModule
  with HasXSParameter
  with HasSoCParameter
{
  private val core = LazyModule(new XSCore(parenName + "XSCore_")(p))
  private val misc = LazyModule(new XSTileMisc())
  private val l2cache = coreParams.L2CacheParamsOpt.map(l2param =>
    LazyModule(new HuanCun(parenName + "L2_")(new Config((_, _, _) => {
      case HCCacheParamsKey => l2param
    })))
  )

  // public ports
  val memory_port = misc.memory_port
  val uncache = misc.mmio_port
  val clint_int_sink = core.clint_int_sink
  val plic_int_sink = core.plic_int_sink
  val debug_int_sink = core.debug_int_sink
  val beu_int_source = misc.beu.intNode
  val core_reset_sink = BundleBridgeSink(Some(() => Reset()))

  val l1d_to_l2_bufferOpt = coreParams.dcacheParametersOpt.map { _ =>
    val buffer = LazyModule(new TLBuffer)
    misc.l1d_logger := buffer.node := core.memBlock.dcache.clientNode
    buffer
  }

  val l1i_to_l2_buffer = LazyModule(new TLBuffer)
  misc.busPMU :=
    TLLogger(s"L2_L1I_${coreParams.HartId}", !debugOpts.FPGAPlatform) :=
    l1i_to_l2_buffer.node :=
    core.frontend.icache.clientNode

  val ptw_to_l2_bufferOpt = if (!coreParams.softPTW) {
    val buffer = LazyModule(new TLBuffer)
    misc.busPMU :=
      TLLogger(s"L2_PTW_${coreParams.HartId}", !debugOpts.FPGAPlatform) :=
      buffer.node :=
      core.ptw_to_l2_buffer.node
    Some(buffer)
  } else None

  l2cache match {
    case Some(l2) =>
      misc.l2_binder.get :*= l2.node :*= TLBuffer() :*= misc.l1_xbar
    case None =>
  }

  misc.i_mmio_port := core.frontend.instrUncache.clientNode
  misc.d_mmio_port := core.memBlock.uncache.clientNode

  lazy val module = new LazyRawModuleImp(this) {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(AsyncReset())
      val hartId = Input(UInt(64.W))
      val reset_vector = Input(UInt(PAddrBits.W))
      val cpu_halt = Output(Bool())
    })
    val xsx_ultiscan_in = IO(new FSCANInputInterface)
    val xsl2_ultiscan_out = IO(Flipped(new FSCANInputInterface))
    val hd2prf_in = IO(new MbitsFuseInterface(isSRAM = false))
    val hsuspsr_in = IO(new MbitsFuseInterface(isSRAM = true))
    val uhdusplr_in = IO(new MbitsFuseInterface(isSRAM = true))
    val hduspsr_in = IO(new MbitsFuseInterface(isSRAM = true))
    val mbist_ijtag = IO(new JTAGInterface)
    val mbist_extra_core_sram = IO(new MbitsExtraFullInterface)
    val mbist_extra_core_rf = IO(new MbitsExtraFullInterface)
    val mbist_extra_l2_sram = IO(new MbitsExtraFullInterface)
    val mbist_extra_l2_rf = IO(new MbitsExtraFullInterface)

    dontTouch(io)
    dontTouch(xsx_ultiscan_in)
    dontTouch(xsl2_ultiscan_out)
    dontTouch(hsuspsr_in)
    dontTouch(hd2prf_in)
    dontTouch(uhdusplr_in)
    dontTouch(hduspsr_in)
    dontTouch(mbist_ijtag)
    dontTouch(mbist_extra_core_sram)
    dontTouch(mbist_extra_core_rf)
    dontTouch(mbist_extra_l2_sram)
    dontTouch(mbist_extra_l2_rf)

    val xsl2_ultiscan = Module(new Ultiscan(3400, 20, 20, 1, 1, 0, 0, "xsl2", !debugOpts.FPGAPlatform))
    dontTouch(xsl2_ultiscan.io)
    xsl2_ultiscan.io := DontCare
    xsl2_ultiscan.io.core_clock_preclk := io.clock

    val dfx_reset = Some(xsl2_ultiscan.toResetGen)
    val reset_sync = withClockAndReset(xsl2_ultiscan.io.core_clock_postclk, io.reset) {
      ResetGen(2, dfx_reset)
    }

    childClock := xsl2_ultiscan.io.core_clock_postclk
    childReset := reset_sync

    val ultiscanIO = IO(new Bundle{
      val ijtag = xsl2_ultiscan.io.ijtag.cloneType
      val uscan = xsl2_ultiscan.io.uscan.cloneType
      val fdfx_powergood = Input(Bool())
    })
    dontTouch(ultiscanIO)

    withClockAndReset(childClock, childReset) {
      val core_soft_rst = core_reset_sink.in.head._1

      core.module.io.hartId := io.hartId
      core.module.io.reset_vector := DelayN(io.reset_vector, 5)
      io.cpu_halt := core.module.io.cpu_halt
      if (l2cache.isDefined) {
        core.module.io.perfEvents.zip(l2cache.get.module.io.perfEvents.flatten).foreach(x => x._1.value := x._2)
      }
      else {
        core.module.io.perfEvents <> DontCare
      }
      core.module.io.dfx_reset := dfx_reset.get

      ultiscanIO.ijtag <> xsl2_ultiscan.io.ijtag
      ultiscanIO.uscan <> xsl2_ultiscan.io.uscan
      xsl2_ultiscan.io.fdfx_powergood := ultiscanIO.fdfx_powergood

      val mbistInterfaceL2SRAM = if (l2cache.isDefined) {
        require(l2cache.get.module.mbist_sram.isDefined,"There is No L2 Mbist SRAM port!")
        val responseMbistPipeline = l2cache.get.module.sliceMbistPipelines.head._1.get
        val intf = Some(Module(new MBISTInterface(
          Seq(l2cache.get.module.mbist_sram.get.params),
          Seq(responseMbistPipeline.node.array_id),
          s"mbist_core${coreParams.HartId}_l2_sram_intf",
          isSRAM = true,
          1
        )))
        intf.get.extra := DontCare
        intf.get.clock := childClock
        intf.get.toPipeline.head <> l2cache.get.module.mbist_sram.get
        mbist_extra_l2_sram.connectExtra(intf.get.extra.head)
        mbist_extra_l2_sram.connectPWR_MGNT(
          responseMbistPipeline.PWR_MGNT.get._1,
          responseMbistPipeline.PWR_MGNT.get._2
        )
        intf
      }
      else {
        None
      }
      val mbistInterfaceL2RF = if (l2cache.isDefined) {
        require(l2cache.get.module.mbist_rf.isDefined,"There is No L2 Mbist RF port!")
        val responseMbistPipeline = l2cache.get.module.sliceMbistPipelines.head._2.get
        val intf = Some(Module(new MBISTInterface(
          Seq(l2cache.get.module.mbist_rf.get.params),
          Seq(responseMbistPipeline.node.array_id),
          s"mbist_core${coreParams.HartId}_l2_rf_intf",
          isSRAM = false,
          1
        )))
        intf.get.extra := DontCare
        intf.get.clock := childClock
        intf.get.toPipeline.head <> l2cache.get.module.mbist_rf.get
        mbist_extra_l2_rf.connectExtra(intf.get.extra.head)
        mbist_extra_l2_rf.connectPWR_MGNT(
          responseMbistPipeline.PWR_MGNT.get._1,
          responseMbistPipeline.PWR_MGNT.get._2
        )
        intf
      }
      else {
        None
      }

      val mbistInterfaceCoreSRAM = Module(new MBISTInterface(
        Seq(core.module.mbist_sram.params),
        Seq(core.module.coreMbistPipelineSram.get.node.array_id),
        s"mbist_core${coreParams.HartId}_core_sram_intf",
        isSRAM = true,
        1
      ))
      mbistInterfaceCoreSRAM.extra := DontCare
      mbistInterfaceCoreSRAM.clock := childClock
      mbistInterfaceCoreSRAM.toPipeline.head <> core.module.mbist_sram
      mbist_extra_core_sram.connectExtra(mbistInterfaceCoreSRAM.extra.head)
      mbist_extra_core_sram.connectPWR_MGNT(
        core.module.coreMbistPipelineSram.get.PWR_MGNT.get._1,
        core.module.coreMbistPipelineSram.get.PWR_MGNT.get._2
      )

      val mbistInterfaceCoreRF = Module(new MBISTInterface(
        Seq(core.module.mbist_rf.params),
        Seq(core.module.coreMbistPipelineRf.get.node.array_id),
        s"mbist_core${coreParams.HartId}_core_rf_intf",
        isSRAM = false,
        1
      ))
      mbistInterfaceCoreRF.extra := DontCare
      mbistInterfaceCoreRF.clock := childClock
      mbistInterfaceCoreRF.toPipeline.head <> core.module.mbist_rf
      mbist_extra_core_rf.connectExtra(mbistInterfaceCoreRF.extra.head)
      mbist_extra_core_rf.connectPWR_MGNT(
        core.module.coreMbistPipelineRf.get.PWR_MGNT.get._1,
        core.module.coreMbistPipelineRf.get.PWR_MGNT.get._2
      )

      val (intfParams,mbistNames,intfPrefixes) = if(l2cache.isDefined) {
        (
          Seq(
            mbistInterfaceCoreSRAM.mbist.params,
            mbistInterfaceCoreRF.mbist.params,
            mbistInterfaceL2SRAM.get.mbist.params,
            mbistInterfaceL2RF.get.mbist.params
        ),
          Seq("L1_SRAM","L1_RF","L2_SRAM","L2_RF"),
          Seq("L1","L2")
          )
      } else {
        (
          Seq(
            mbistInterfaceCoreSRAM.mbist.params,
            mbistInterfaceCoreRF.mbist.params
          ),
          Seq("L1_SRAM","L1_RF"),
          Seq("L1")
          )
      }


      val mbistControllerCoreWithL2 = Module(new MBISTController(
        intfParams,
        mbistNames,
        intfPrefixes,
        None
      ))
      dontTouch(mbistControllerCoreWithL2.io)

      if(l2cache.isDefined){
        //Core
        mbistControllerCoreWithL2.mbist(0) <> mbistInterfaceCoreSRAM.mbist
        mbistControllerCoreWithL2.fscan_ram(0) <> mbistInterfaceCoreSRAM.fscan_ram
        mbistControllerCoreWithL2.io.hd2prf_out <> mbistInterfaceCoreSRAM.hd2prf_fuse
        mbistControllerCoreWithL2.io.hsuspsr_out <> mbistInterfaceCoreSRAM.hsuspsr_fuse
        mbistControllerCoreWithL2.io.uhdusplr_out <> mbistInterfaceCoreSRAM.uhdusplr_fuse
        mbistControllerCoreWithL2.io.hduspsr_out <> mbistInterfaceCoreSRAM.hduspsr_fuse

        mbistControllerCoreWithL2.mbist(1) <> mbistInterfaceCoreRF.mbist
        mbistControllerCoreWithL2.fscan_ram(0) <> mbistInterfaceCoreRF.fscan_ram
        mbistControllerCoreWithL2.io.hd2prf_out <> mbistInterfaceCoreRF.hd2prf_fuse
        mbistControllerCoreWithL2.io.hsuspsr_out <> mbistInterfaceCoreRF.hsuspsr_fuse
        mbistControllerCoreWithL2.io.uhdusplr_out <> mbistInterfaceCoreRF.uhdusplr_fuse
        mbistControllerCoreWithL2.io.hduspsr_out <> mbistInterfaceCoreRF.hduspsr_fuse
        //L2
        mbistControllerCoreWithL2.mbist(2) <> mbistInterfaceL2SRAM.get.mbist
        mbistControllerCoreWithL2.fscan_ram(1) <> mbistInterfaceL2SRAM.get.fscan_ram
        mbistControllerCoreWithL2.io.hd2prf_out <> mbistInterfaceL2SRAM.get.hd2prf_fuse
        mbistControllerCoreWithL2.io.hsuspsr_out <> mbistInterfaceL2SRAM.get.hsuspsr_fuse
        mbistControllerCoreWithL2.io.uhdusplr_out <> mbistInterfaceL2SRAM.get.uhdusplr_fuse
        mbistControllerCoreWithL2.io.hduspsr_out <> mbistInterfaceL2SRAM.get.hduspsr_fuse

        mbistControllerCoreWithL2.mbist(3) <> mbistInterfaceL2RF.get.mbist
        mbistControllerCoreWithL2.fscan_ram(1) <> mbistInterfaceL2RF.get.fscan_ram
        mbistControllerCoreWithL2.io.hd2prf_out <> mbistInterfaceL2RF.get.hd2prf_fuse
        mbistControllerCoreWithL2.io.hsuspsr_out <> mbistInterfaceL2RF.get.hsuspsr_fuse
        mbistControllerCoreWithL2.io.uhdusplr_out <> mbistInterfaceL2RF.get.uhdusplr_fuse
        mbistControllerCoreWithL2.io.hduspsr_out <> mbistInterfaceL2RF.get.hduspsr_fuse
      }
      else{
        mbistControllerCoreWithL2.mbist(0) <> mbistInterfaceCoreSRAM.mbist
        mbistControllerCoreWithL2.fscan_ram(0) <> mbistInterfaceCoreSRAM.fscan_ram
        mbistControllerCoreWithL2.io.hd2prf_out <> mbistInterfaceCoreSRAM.hd2prf_fuse
        mbistControllerCoreWithL2.io.hsuspsr_out <> mbistInterfaceCoreSRAM.hsuspsr_fuse
        mbistControllerCoreWithL2.io.uhdusplr_out <> mbistInterfaceCoreSRAM.uhdusplr_fuse
        mbistControllerCoreWithL2.io.hduspsr_out <> mbistInterfaceCoreSRAM.hduspsr_fuse

        mbistControllerCoreWithL2.mbist(1) <> mbistInterfaceCoreRF.mbist
        mbistControllerCoreWithL2.fscan_ram(0) <> mbistInterfaceCoreRF.fscan_ram
        mbistControllerCoreWithL2.io.hd2prf_out <> mbistInterfaceCoreRF.hd2prf_fuse
        mbistControllerCoreWithL2.io.hsuspsr_out <> mbistInterfaceCoreRF.hsuspsr_fuse
        mbistControllerCoreWithL2.io.uhdusplr_out <> mbistInterfaceCoreRF.uhdusplr_fuse
        mbistControllerCoreWithL2.io.hduspsr_out <> mbistInterfaceCoreRF.hduspsr_fuse
      }
      mbistControllerCoreWithL2.io.fscan_clkungate := xsl2_ultiscan.io.fscan.clkungate
      mbistControllerCoreWithL2.io.clock := childClock

      mbistControllerCoreWithL2.io.xsx_fscan_in <> xsx_ultiscan_in

      mbistControllerCoreWithL2.io.xsl2_fscan_in.bypsel := xsl2_ultiscan.io.fscan.ram.bypsel
      mbistControllerCoreWithL2.io.xsl2_fscan_in.wdis_b := xsl2_ultiscan.io.fscan.ram.wrdis_b
      mbistControllerCoreWithL2.io.xsl2_fscan_in.rdis_b := xsl2_ultiscan.io.fscan.ram.rddis_b
      mbistControllerCoreWithL2.io.xsl2_fscan_in.init_en := xsl2_ultiscan.io.fscan.ram.init_en
      mbistControllerCoreWithL2.io.xsl2_fscan_in.init_val := xsl2_ultiscan.io.fscan.ram.init_val

      xsl2_ultiscan_out.bypsel := xsl2_ultiscan.io.fscan.ram.bypsel
      xsl2_ultiscan_out.wdis_b := xsl2_ultiscan.io.fscan.ram.wrdis_b
      xsl2_ultiscan_out.rdis_b := xsl2_ultiscan.io.fscan.ram.rddis_b
      xsl2_ultiscan_out.init_en := xsl2_ultiscan.io.fscan.ram.init_en
      xsl2_ultiscan_out.init_val := xsl2_ultiscan.io.fscan.ram.init_val

      mbist_ijtag <> mbistControllerCoreWithL2.io.mbist_ijtag

      mbistControllerCoreWithL2.io.hd2prf_in <> hd2prf_in
      mbistControllerCoreWithL2.io.hsuspsr_in <> hsuspsr_in
      mbistControllerCoreWithL2.io.uhdusplr_in <> uhdusplr_in
      mbistControllerCoreWithL2.io.hduspsr_in <> hduspsr_in

      misc.module.beu_errors.icache <> core.module.io.beu_errors.icache
      misc.module.beu_errors.dcache <> core.module.io.beu_errors.dcache
      if (l2cache.isDefined) {
        misc.module.beu_errors.l2.ecc_error.valid := l2cache.get.module.io.ecc_error.valid
        misc.module.beu_errors.l2.ecc_error.bits := l2cache.get.module.io.ecc_error.bits
      } else {
        misc.module.beu_errors.l2 <> 0.U.asTypeOf(misc.module.beu_errors.l2)
      }

      // Modules are reset one by one
      // io_reset ----
      //             |
      //             v
      // reset ----> OR_SYNC --> {Misc, L2 Cache, Cores}
      val resetChain = Seq(
        Seq(misc.module, core.module, l1i_to_l2_buffer.module) ++
          l2cache.map(_.module) ++
          l1d_to_l2_bufferOpt.map(_.module) ++ ptw_to_l2_bufferOpt.map(_.module)
      )
      ResetGen(resetChain, (childReset.asBool || core_soft_rst.asBool).asAsyncReset, !debugOpts.FPGAPlatform, dfx_reset)
    }
  }
}
