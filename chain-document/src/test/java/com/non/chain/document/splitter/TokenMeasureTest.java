package com.non.chain.document.splitter;

import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TokenMeasureTest {

    @Test
    public void measure_nullText_returnsZero() {
        TokenMeasure measure = new TokenMeasure(EncodingType.CL100K_BASE);

        assertEquals(0, measure.measure(null));
    }

    @Test
    public void measure_supportsEncodingTypeAndModelTypeConstructors() {
        TokenMeasure byEncoding = new TokenMeasure(EncodingType.CL100K_BASE);
        TokenMeasure byModel = new TokenMeasure(ModelType.GPT_4);

        assertTrue(byEncoding.measure("hello world") > 0);
        assertTrue(byModel.measure("hello world") > 0);
    }
}
