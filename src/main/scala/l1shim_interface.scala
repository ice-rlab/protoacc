package protoacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, TLBPTWIO, TLB, MStatus, PRV}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{RAS}
import freechips.rocketchip.tilelink._


class L1ReqInternal extends Bundle {
  val addr = UInt()
  val size = UInt()
  val data = UInt(128.W)
  val cmd = UInt()
}

class L1RespInternal extends Bundle {
  val data = UInt(128.W)
}

class L1InternalTracking extends Bundle {
  val addrindex = UInt(4.W)
  val tag = UInt()
}

class L1MemHelperBundle extends Bundle {
  val req = Decoupled(new L1ReqInternal)
  val resp = Flipped(Decoupled(new L1RespInternal))
  val no_memops_inflight = Input(Bool())
}


class L1MemHelper(printInfo: String = "", numOutstandingReqs: Int = 32, queueRequests: Boolean = true, queueResponses: Boolean = true)(implicit p: Parameters) extends LazyModule {
  val numOutstandingRequestsAllowed = numOutstandingReqs
  val tlTagBits = log2Ceil(numOutstandingRequestsAllowed)


  lazy val module = new L1MemHelperModule(this, printInfo, queueRequests, queueResponses)
  val masterNode = TLClientNode(Seq(TLClientPortParameters(
    Seq(TLClientParameters(name = printInfo, sourceId = IdRange(0,
      numOutstandingRequestsAllowed)))
  )))
}

class L1MemHelperModule(outer: L1MemHelper, printInfo: String = "", queueRequests: Boolean = true, queueResponses: Boolean = true)(implicit p: Parameters) extends LazyModuleImp(outer)
  with HasCoreParameters
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val userif = Flipped(new L1MemHelperBundle)

    val sfence = Input(Bool())
    val ptw = new TLBPTWIO
    val status = Flipped(Valid(new MStatus))
  })

  val (dmem, edge) = outer.masterNode.out.head

  val request_input = Wire(Decoupled(new L1ReqInternal))
  if (!queueRequests) {
    request_input <> io.userif.req
  } else {
    val requestQueue = Module(new Queue(new L1ReqInternal, 4))
    request_input <> requestQueue.io.deq
    requestQueue.io.enq <> io.userif.req
  }

  val response_output = Wire(Decoupled(new L1RespInternal))
  if (!queueResponses) {
    io.userif.resp <> response_output
  } else {
    val responseQueue = Module(new Queue(new L1RespInternal, 4))
    responseQueue.io.enq <> response_output
    io.userif.resp <> responseQueue.io.deq
  }

  val status = Reg(new MStatus)
  when (io.status.valid) {
    ProtoaccLogger.logInfo(printInfo + " setting status.dprv to: %x compare %x\n", io.status.bits.dprv, (PRV.M).U)
    status := io.status.bits
  }

  val tlb = Module(new TLB(false, log2Ceil(coreDataBytes), p(ProtoTLB).get)(edge, p))
  tlb.io.req.bits.prv := 0.U
  tlb.io.req.bits.v := false.B
  tlb.io.req.valid := request_input.valid
  tlb.io.req.bits.vaddr := request_input.bits.addr
  tlb.io.req.bits.size := request_input.bits.size
  tlb.io.req.bits.cmd := request_input.bits.cmd
  tlb.io.req.bits.passthrough := false.B
  val tlb_ready = tlb.io.req.ready && !tlb.io.resp.miss

  io.ptw <> tlb.io.ptw
  tlb.io.ptw.status := status
  tlb.io.sfence.valid := io.sfence
  tlb.io.sfence.bits.hv := false.B
  tlb.io.sfence.bits.hg := false.B
  tlb.io.sfence.bits.rs1 := false.B
  tlb.io.sfence.bits.rs2 := false.B
  tlb.io.sfence.bits.addr := 0.U
  tlb.io.sfence.bits.asid := 0.U
  tlb.io.kill := false.B

  val outstanding_req_addr = Module(new Queue(new L1InternalTracking, outer.numOutstandingRequestsAllowed * 4))


  val tags_for_issue_Q = Module(new Queue(UInt(outer.tlTagBits.W), outer.numOutstandingRequestsAllowed * 2))
  tags_for_issue_Q.io.enq.valid := false.B

  val tags_init_reg = RegInit(0.U((outer.tlTagBits+1).W))
  tags_for_issue_Q.io.enq.bits := 0.U
  when (tags_init_reg =/= (outer.numOutstandingRequestsAllowed).U) {
    tags_for_issue_Q.io.enq.bits := tags_init_reg
    tags_for_issue_Q.io.enq.valid := true.B
    when (tags_for_issue_Q.io.enq.ready) {
      ProtoaccLogger.logInfo(printInfo + " tags_for_issue_Q init with value %d\n", tags_for_issue_Q.io.enq.bits)
      tags_init_reg := tags_init_reg + 1.U
    }
  }

  val addr_mask_check = (0x1.U(64.W) << request_input.bits.size) - 1.U
  val assertcheck = RegNext((!request_input.valid) || ((request_input.bits.addr & addr_mask_check) === 0.U))
  assert(assertcheck,
    printInfo + " L2IF: access addr must be aligned to write width\n")

  val global_memop_accepted = RegInit(0.U(64.W))
  when (io.userif.req.fire) {
    global_memop_accepted := global_memop_accepted + 1.U
  }

  val global_memop_sent = RegInit(0.U(64.W))

  val global_memop_ackd = RegInit(0.U(64.W))

  val global_memop_resp_to_user = RegInit(0.U(64.W))

  io.userif.no_memops_inflight := global_memop_accepted === global_memop_ackd

  val free_outstanding_op_slots = (global_memop_sent - global_memop_ackd) < (1 << outer.tlTagBits).U
  val assert_free_outstanding_op_slots = (global_memop_sent - global_memop_ackd) <= (1 << outer.tlTagBits).U

  assert(assert_free_outstanding_op_slots,
    printInfo + " L2IF: Too many outstanding requests for tag count.\n")

  when (request_input.fire) {
    global_memop_sent := global_memop_sent + 1.U
  }

  val sendtag = tags_for_issue_Q.io.deq.bits

  when (request_input.bits.cmd === M_XRD) {
    val (legal, bundle) = edge.Get(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size)
    dmem.a.bits := bundle
  } .elsewhen (request_input.bits.cmd === M_XWR) {
    val (legal, bundle) = edge.Put(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size,
                            data=request_input.bits.data << ((request_input.bits.addr(3, 0) << 3)))
    dmem.a.bits := bundle
  } .elsewhen (request_input.valid) {

    assert(false.B, "ERR")
  }

  val tl_resp_queues = VecInit(Seq.fill(outer.numOutstandingRequestsAllowed) {
    val queue = Module(new Queue(new L1RespInternal, 4, flow=true))
    queue.io
  })

  val current_request_tag_has_response_space = tl_resp_queues(tags_for_issue_Q.io.deq.bits).enq.ready

  val fire_req = DecoupledHelper(
    request_input.valid,
    dmem.a.ready,
    tlb_ready,
    outstanding_req_addr.io.enq.ready,
    free_outstanding_op_slots,
    tags_for_issue_Q.io.deq.valid,
    current_request_tag_has_response_space
  )

  outstanding_req_addr.io.enq.bits.addrindex := request_input.bits.addr & 0xF.U
  outstanding_req_addr.io.enq.bits.tag := sendtag

  dmem.a.valid := fire_req.fire(dmem.a.ready)
  request_input.ready := fire_req.fire(request_input.valid)
  outstanding_req_addr.io.enq.valid := fire_req.fire(outstanding_req_addr.io.enq.ready)
  tags_for_issue_Q.io.deq.ready := fire_req.fire(tags_for_issue_Q.io.deq.valid)


  when (dmem.a.fire) {
    when (request_input.bits.cmd === M_XRD) {
      ProtoaccLogger.logInfo(printInfo + " L2IF: req(read) vaddr: 0x%x, paddr: 0x%x, wid: 0x%x, opnum: %d, sendtag: %d\n",
        request_input.bits.addr,
        tlb.io.resp.paddr,
        request_input.bits.size,
        global_memop_sent,
        sendtag)
    }
    when (request_input.bits.cmd === M_XWR) {
      ProtoaccLogger.logInfo(printInfo + " L2IF: req(write) vaddr: 0x%x, paddr: 0x%x, wid: 0x%x, data: 0x%x, opnum: %d, sendtag: %d\n",
        request_input.bits.addr,
        tlb.io.resp.paddr,
        request_input.bits.size,
        request_input.bits.data,
        global_memop_sent,
        sendtag)
    }
  }





  val selectQready = tl_resp_queues(dmem.d.bits.source).enq.ready

  val fire_actual_mem_resp = DecoupledHelper(
    selectQready,
    dmem.d.valid,
    tags_for_issue_Q.io.enq.ready
  )

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready)) {
    tags_for_issue_Q.io.enq.valid := true.B
    tags_for_issue_Q.io.enq.bits := dmem.d.bits.source
  }

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready) &&
    tags_for_issue_Q.io.enq.valid) {
      ProtoaccLogger.logInfo(printInfo + " tags_for_issue_Q add back tag %d\n", tags_for_issue_Q.io.enq.bits)
  }

  dmem.d.ready := fire_actual_mem_resp.fire(dmem.d.valid)

  for (i <- 0 until outer.numOutstandingRequestsAllowed) {
    tl_resp_queues(i).enq.valid := fire_actual_mem_resp.fire(selectQready) && (dmem.d.bits.source === i.U)
    tl_resp_queues(i).enq.bits.data := dmem.d.bits.data
  }




  val currentQueue = tl_resp_queues(outstanding_req_addr.io.deq.bits.tag)
  val queueValid = currentQueue.deq.valid

  val fire_user_resp = DecoupledHelper(
    queueValid,
    response_output.ready,
    outstanding_req_addr.io.deq.valid
  )

  val resultdata = currentQueue.deq.bits.data >> (outstanding_req_addr.io.deq.bits.addrindex << 3)

  response_output.bits.data := resultdata

  response_output.valid := fire_user_resp.fire(response_output.ready)
  outstanding_req_addr.io.deq.ready := fire_user_resp.fire(outstanding_req_addr.io.deq.valid)

  for (i <- 0 until outer.numOutstandingRequestsAllowed) {
    tl_resp_queues(i).deq.ready := fire_user_resp.fire(queueValid) && (outstanding_req_addr.io.deq.bits.tag === i.U)
  }


  when (dmem.d.fire) {
    when (edge.hasData(dmem.d.bits)) {
      ProtoaccLogger.logInfo(printInfo + " L2IF: resp(read) data: 0x%x, opnum: %d, gettag: %d\n",
        dmem.d.bits.data,
        global_memop_ackd,
        dmem.d.bits.source)
    } .otherwise {
      ProtoaccLogger.logInfo(printInfo + " L2IF: resp(write) opnum: %d, gettag: %d\n",
        global_memop_ackd,
        dmem.d.bits.source)
    }
  }

  when (response_output.fire) {
    ProtoaccLogger.logInfo(printInfo + " L2IF: realresp() data: 0x%x, opnum: %d, gettag: %d\n",
      resultdata,
      global_memop_resp_to_user,
      outstanding_req_addr.io.deq.bits.tag)
  }

  when (dmem.d.fire) {
    global_memop_ackd := global_memop_ackd + 1.U
  }

  when (response_output.fire) {
    global_memop_resp_to_user := global_memop_resp_to_user + 1.U
  }

}


