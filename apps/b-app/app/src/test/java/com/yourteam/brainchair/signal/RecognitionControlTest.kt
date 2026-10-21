package com.yourteam.brainchair.signal

import org.junit.Test
import org.junit.Assert.*

class RecognitionControlTest {
    @Test fun noSupportStopsImmediatelyAndNoOldDirectionDuringSwitch() {
        val gate=DecisionGate(RecognitionConfig());val control=RecognitionControl()
        fun step(scores:DoubleArray,ms:Long){val d=gate.evaluate(scores,ms);control.accept(d.raw,d.stable,ms,ms)}
        val f=doubleArrayOf(.9,.1,.1,.1);val l=doubleArrayOf(.1,.9,.1,.1)
        step(f,0);step(f,200);step(f,400);assertEquals(Direction.FORWARD,control.state.output)
        step(l,600);assertEquals(Direction.STOP,control.state.output)
        step(l,800);step(l,1000);assertEquals(Direction.LEFT,control.state.output)
        step(doubleArrayOf(.1,.1,.1,.1),1200);assertEquals(Direction.STOP,control.state.output)
        step(l,1400);step(l,1600);assertEquals(Direction.STOP,control.state.output)
        step(l,1800);assertEquals(Direction.LEFT,control.state.output)
        step(doubleArrayOf(Double.NaN,.2,.2,.2),2000);assertEquals(Direction.STOP,control.state.output)
    }
    @Test fun inputLeaseCannotBeRenewedByLateComputationAndExpiryIsInclusive() {
        val c=RecognitionControl()
        c.accept(Direction.FORWARD,Direction.FORWARD,100,100);assertEquals(Direction.FORWARD,c.state.output)
        c.expire(1099);assertEquals(Direction.FORWARD,c.state.output)
        c.expire(1100);assertEquals(Direction.STOP,c.state.output)
        c.accept(Direction.FORWARD,Direction.FORWARD,100,1300);assertEquals(Direction.STOP,c.state.output)
        c.accept(Direction.LEFT,Direction.FORWARD,1400,1400);assertEquals(Direction.STOP,c.state.output)
        c.accept(Direction.FORWARD,Direction.FORWARD,1500,1500);c.stop("STREAM_STOP",1501)
        assertEquals(Direction.STOP,c.state.output);assertNull(c.state.expiresMs)
    }
}
