//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Tile
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Feb 5
//
// Describes a RISC-V Out-of-Order processor tile

package boom

import Chisel._
import cde.{Parameters, Field}
import bist._

class BOOMTile(clockSignal: Clock = null, resetSignal: Bool = null)
   (implicit p: Parameters) extends rocket.Tile(clockSignal, resetSignal)(p)
{
   println("Building BOOMTile")
   val core = Module(new BOOMCore())
   println("\tBuilding I$")
   val icache = Module(new rocket.Frontend()(p.alterPartial({
      case uncore.agents.CacheName => "L1I"
      })))
   println("\tBuilding D$")
   val dcache = rocket.HellaCache(p(rocket.DCacheKey))(dcacheParams)

//   val dc_shim = Module(new DCacheShim()(dcacheParams))

   val ptwPorts = collection.mutable.ArrayBuffer(icache.io.ptw, core.io.ptw_tlb)
   val dcPorts = collection.mutable.ArrayBuffer(core.io.dmem)
   val uncachedArbPorts = collection.mutable.ArrayBuffer(icache.io.mem)
   val uncachedPorts = collection.mutable.ArrayBuffer[uncore.tilelink.ClientUncachedTileLinkIO]()
   val cachedPorts = collection.mutable.ArrayBuffer(dcache.mem)
   core.io.interrupts := io.interrupts
   core.io.hartid := io.hartid
//   dc_shim.io.core <> core.io.dmem
   icache.io.cpu <> core.io.imem
   icache.io.resetVector := io.resetVector


   val uncachedArb = Module(new uncore.tilelink.ClientUncachedTileLinkIOArbiter(uncachedArbPorts.size))
   uncachedArb.io.in <> uncachedArbPorts
   uncachedArb.io.out +=: uncachedPorts

   // Connect the caches and RoCC to the outer memory system
   io.uncached <> uncachedPorts
   io.cached <> cachedPorts
   // TODO remove nCached/nUncachedTileLinkPorts parameters and these assertions
   require(uncachedPorts.size == nUncachedTileLinkPorts)
   require(cachedPorts.size == nCachedTileLinkPorts)

   if (p(rocket.UseVM))
   {
      val ptw = Module(new rocket.PTW(ptwPorts.size)(dcacheParams))
      ptw.io.requestor <> ptwPorts
      ptw.io.mem +=: dcPorts
      core.io.ptw <> ptw.io.dpath

      // the dcache's built-in TLB will be unused, but it still needs some of the
      // status/sret signals for things such as lr/sc
      dcache.ptw.status <> ptw.io.requestor(1).status
      dcache.ptw.invalidate := ptw.io.requestor(1).invalidate
      dcache.ptw.req.ready := Bool(false)
      dcache.ptw.resp.valid := Bool(false)
   }

   val dcArb = Module(new rocket.HellaCacheArbiter(dcPorts.size)(dcacheParams))
   dcArb.io.requestor <> dcPorts
   dcache.cpu <> dcArb.io.mem
   dcache.cpu.invalidate_lr := core.io.dmem.invalidate_lr


   // Cache Counters
   core.io.counters.dc_miss := dcache.mem.acquire.fire()
   core.io.counters.ic_miss := icache.io.mem.acquire.fire()

   // [pfchiu] reset redundancy
   icache.io.reset_redundancy <> io.reset_redundancy 
   dcache.resiliency.reset_redundancy <> io.reset_redundancy
   //[pfchiu] core bist
   val core_bist = Module(new bist.BistTop(core_sram_num))
   io.bist <> core_bist.io.bist	
   icache.io.bist_dut <> core_bist.io.sram_ut
   dcache.bist_dut <> core_bist.io.sram_ut
   for (i <- 0 until core_sram_num/2) {
     core_bist.io.sram_ut.dout(i) <> icache.io.bist_dut.dout(i) 
     icache.io.bist_dut.en(i) <> core_bist.io.sram_ut.en(i) 
   }
   for (i <- 0 until core_sram_num/2) {
     core_bist.io.sram_ut.dout(core_sram_num/2+i) <> dcache.bist_dut.dout(i)
     dcache.bist_dut.en(i) <> core_bist.io.sram_ut.en(core_sram_num/2+i) 
   }
  //[pfchiu] program disable
  io.program_disable <> icache.io.program_disable 
  io.program_disable <> dcache.resiliency.program_disable
  icache.io.program_disable.valid := io.program_disable.valid && io.program_disable.dest === UInt(0)
  dcache.resiliency.program_disable.valid := io.program_disable.valid && io.program_disable.dest === UInt(1)

  //[pfchiu] program bbypass
 io.program_bbypass <> icache.io.program_bbypass
  icache.io.program_bbypass.valid := io.program_bbypass.valid && io.program_bbypass.dest === UInt(0)
  dcache.resiliency.program_bbypass.valid := io.program_bbypass.valid && io.program_bbypass.dest === UInt(1)

  //[pfchiu] program dcr
  io.program_dcr <> icache.io.program_dcr
  icache.io.program_dcr.valid := io.program_dcr.valid && io.program_dcr.dest === UInt(0)
  io.program_dcr <> dcache.resiliency.program_dcr
  dcache.resiliency.program_dcr.valid := io.program_dcr.valid && io.program_dcr.dest === UInt(1)

  //[pfchiu] ecc_log
  // icache tag has 4 ways, 
  val numErrorLogSources = 10
  val error_log_arb = Module(new Arbiter(UInt(width = 32), numErrorLogSources))
  //feed the vector of error_log to the arbiter
  val error_log_queue =  (0 until numErrorLogSources).map(x => Module(new Queue(UInt(width=32), 2))).toList
  //for icache tag
  for (i <- 0 until 4) {
    error_log_queue(i).io.enq.valid := icache.io.error_log.tag(i).log_we
    error_log_queue(i).io.enq.bits := icache.io.error_log.tag(i).log_entry
    error_log_arb.io.in(i) <> error_log_queue(i).io.deq
  }
  //for icache data
  error_log_queue(4).io.enq.valid := icache.io.error_log.data.log_we
  error_log_queue(4).io.enq.bits := icache.io.error_log.data.log_entry
  error_log_arb.io.in(4) <> error_log_queue(4).io.deq

  //for dcache tag
  for (i <- 5 until 9) {
    error_log_queue(i).io.enq.valid := dcache.error_log.tag(i-5).log_we
    error_log_queue(i).io.enq.bits := dcache.error_log.tag(i-5).log_entry
    error_log_arb.io.in(i) <> error_log_queue(i).io.deq
  }
    
  //for icache data
  error_log_queue(9).io.enq.valid := dcache.error_log.data.log_we
  error_log_queue(9).io.enq.bits := dcache.error_log.data.log_entry
  error_log_arb.io.in(9) <> error_log_queue(9).io.deq

  error_log_arb.io.out.ready := io.error_log.log_entry(31)
  when (error_log_arb.io.out.valid && error_log_arb.io.out.ready) {
    io.error_log.log_entry := error_log_arb.io.out.bits
  }

}

