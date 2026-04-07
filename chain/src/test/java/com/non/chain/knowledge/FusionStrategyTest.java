package com.non.chain.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public class FusionStrategyTest {

    @Test
    public void values_containsRrfAndLinear() {
        FusionStrategy[] values = FusionStrategy.values();
        assertEquals(2, values.length);
        assertArrayEquals(new FusionStrategy[]{FusionStrategy.RRF, FusionStrategy.LINEAR}, values);
    }

    @Test
    public void valueOf_rrf() {
        assertEquals(FusionStrategy.RRF, FusionStrategy.valueOf("RRF"));
    }

    @Test
    public void valueOf_linear() {
        assertEquals(FusionStrategy.LINEAR, FusionStrategy.valueOf("LINEAR"));
    }
}
