package com.non.chain.knowledge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class MetadataFilterTest {

    // --- condition factory ---

    @Test
    public void condition_eq() {
        MetadataFilter filter = MetadataFilter.condition("tag", MetadataFilter.Operator.EQ, "java");

        assertEquals(MetadataFilter.Type.CONDITION, filter.type());
        assertEquals("tag", filter.key());
        assertEquals(MetadataFilter.Operator.EQ, filter.operator());
        assertEquals("java", filter.value());
        assertTrue(filter.children().isEmpty());
    }

    @Test
    public void condition_ne() {
        MetadataFilter filter = MetadataFilter.condition("status", MetadataFilter.Operator.NE, "draft");
        assertEquals(MetadataFilter.Operator.NE, filter.operator());
    }

    @Test
    public void condition_gt() {
        MetadataFilter filter = MetadataFilter.condition("year", MetadataFilter.Operator.GT, 2020);
        assertEquals(MetadataFilter.Operator.GT, filter.operator());
        assertEquals(2020, filter.value());
    }

    @Test
    public void condition_gte() {
        MetadataFilter filter = MetadataFilter.condition("year", MetadataFilter.Operator.GTE, 2020);
        assertEquals(MetadataFilter.Operator.GTE, filter.operator());
    }

    @Test
    public void condition_lt() {
        MetadataFilter filter = MetadataFilter.condition("price", MetadataFilter.Operator.LT, 100.0);
        assertEquals(MetadataFilter.Operator.LT, filter.operator());
    }

    @Test
    public void condition_lte() {
        MetadataFilter filter = MetadataFilter.condition("price", MetadataFilter.Operator.LTE, 100.0);
        assertEquals(MetadataFilter.Operator.LTE, filter.operator());
    }

    @Test
    public void condition_in() {
        MetadataFilter filter = MetadataFilter.condition("tag", MetadataFilter.Operator.IN,
                Arrays.asList("java", "python"));
        assertEquals(MetadataFilter.Operator.IN, filter.operator());
    }

    @Test
    public void condition_exists() {
        MetadataFilter filter = MetadataFilter.condition("field", MetadataFilter.Operator.EXISTS, null);
        assertEquals(MetadataFilter.Operator.EXISTS, filter.operator());
        assertNull(filter.value());
    }

    @Test(expected = IllegalArgumentException.class)
    public void condition_nullKey_throwsException() {
        MetadataFilter.condition(null, MetadataFilter.Operator.EQ, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void condition_blankKey_throwsException() {
        MetadataFilter.condition("  ", MetadataFilter.Operator.EQ, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void condition_nullOperator_throwsException() {
        MetadataFilter.condition("key", null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void condition_nullValueForNonExists_throwsException() {
        MetadataFilter.condition("key", MetadataFilter.Operator.EQ, null);
    }

    // --- and factory ---

    @Test
    public void and_combinesFilters() {
        MetadataFilter f1 = MetadataFilter.condition("a", MetadataFilter.Operator.EQ, "1");
        MetadataFilter f2 = MetadataFilter.condition("b", MetadataFilter.Operator.EQ, "2");
        MetadataFilter and = MetadataFilter.and(Arrays.asList(f1, f2));

        assertEquals(MetadataFilter.Type.AND, and.type());
        assertNull(and.key());
        assertNull(and.operator());
        assertNull(and.value());
        assertEquals(2, and.children().size());
        assertSame(f1, and.children().get(0));
        assertSame(f2, and.children().get(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void and_nullList_throwsException() {
        MetadataFilter.and(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void and_emptyList_throwsException() {
        MetadataFilter.and(Collections.emptyList());
    }

    @Test(expected = NullPointerException.class)
    public void and_listWithNull_throwsException() {
        MetadataFilter.and(Arrays.asList(
                MetadataFilter.condition("a", MetadataFilter.Operator.EQ, "1"),
                null
        ));
    }

    // --- or factory ---

    @Test
    public void or_combinesFilters() {
        MetadataFilter f1 = MetadataFilter.condition("a", MetadataFilter.Operator.EQ, "1");
        MetadataFilter f2 = MetadataFilter.condition("b", MetadataFilter.Operator.EQ, "2");
        MetadataFilter or = MetadataFilter.or(Arrays.asList(f1, f2));

        assertEquals(MetadataFilter.Type.OR, or.type());
        assertEquals(2, or.children().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void or_nullList_throwsException() {
        MetadataFilter.or(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void or_emptyList_throwsException() {
        MetadataFilter.or(Collections.emptyList());
    }

    // --- not factory ---

    @Test
    public void not_wrapsFilter() {
        MetadataFilter inner = MetadataFilter.condition("a", MetadataFilter.Operator.EQ, "1");
        MetadataFilter not = MetadataFilter.not(inner);

        assertEquals(MetadataFilter.Type.NOT, not.type());
        assertNull(not.key());
        assertNull(not.operator());
        assertNull(not.value());
        assertEquals(1, not.children().size());
        assertSame(inner, not.children().get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void not_nullFilter_throwsException() {
        MetadataFilter.not(null);
    }

    // --- Children list immutability ---

    @Test
    public void condition_childrenIsUnmodifiable() {
        MetadataFilter filter = MetadataFilter.condition("key", MetadataFilter.Operator.EQ, "value");

        try {
            filter.children().add(MetadataFilter.condition("other", MetadataFilter.Operator.EQ, "x"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void and_childrenIsUnmodifiable() {
        MetadataFilter and = MetadataFilter.and(Arrays.asList(
                MetadataFilter.condition("a", MetadataFilter.Operator.EQ, "1")
        ));

        try {
            and.children().add(MetadataFilter.condition("b", MetadataFilter.Operator.EQ, "2"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void not_childrenIsUnmodifiable() {
        MetadataFilter not = MetadataFilter.not(
                MetadataFilter.condition("a", MetadataFilter.Operator.EQ, "1")
        );

        try {
            not.children().add(MetadataFilter.condition("b", MetadataFilter.Operator.EQ, "2"));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    // --- Nested structures ---

    @Test
    public void nested_andOrNot() {
        MetadataFilter c1 = MetadataFilter.condition("tag", MetadataFilter.Operator.EQ, "java");
        MetadataFilter c2 = MetadataFilter.condition("year", MetadataFilter.Operator.GT, 2020);
        MetadataFilter c3 = MetadataFilter.condition("status", MetadataFilter.Operator.NE, "draft");

        MetadataFilter and = MetadataFilter.and(Arrays.asList(c1, c2));
        MetadataFilter not = MetadataFilter.not(c3);
        MetadataFilter or = MetadataFilter.or(Arrays.asList(and, not));

        assertEquals(MetadataFilter.Type.OR, or.type());
        assertEquals(2, or.children().size());
        assertEquals(MetadataFilter.Type.AND, or.children().get(0).type());
        assertEquals(MetadataFilter.Type.NOT, or.children().get(1).type());
    }

    // --- Operator enum values ---

    @Test
    public void operator_allValues() {
        MetadataFilter.Operator[] operators = MetadataFilter.Operator.values();
        assertEquals(8, operators.length);

        assertEquals(MetadataFilter.Operator.EQ, MetadataFilter.Operator.valueOf("EQ"));
        assertEquals(MetadataFilter.Operator.NE, MetadataFilter.Operator.valueOf("NE"));
        assertEquals(MetadataFilter.Operator.GT, MetadataFilter.Operator.valueOf("GT"));
        assertEquals(MetadataFilter.Operator.GTE, MetadataFilter.Operator.valueOf("GTE"));
        assertEquals(MetadataFilter.Operator.LT, MetadataFilter.Operator.valueOf("LT"));
        assertEquals(MetadataFilter.Operator.LTE, MetadataFilter.Operator.valueOf("LTE"));
        assertEquals(MetadataFilter.Operator.IN, MetadataFilter.Operator.valueOf("IN"));
        assertEquals(MetadataFilter.Operator.EXISTS, MetadataFilter.Operator.valueOf("EXISTS"));
    }

    // --- Type enum values ---

    @Test
    public void type_allValues() {
        MetadataFilter.Type[] types = MetadataFilter.Type.values();
        assertEquals(4, types.length);

        assertEquals(MetadataFilter.Type.CONDITION, MetadataFilter.Type.valueOf("CONDITION"));
        assertEquals(MetadataFilter.Type.AND, MetadataFilter.Type.valueOf("AND"));
        assertEquals(MetadataFilter.Type.OR, MetadataFilter.Type.valueOf("OR"));
        assertEquals(MetadataFilter.Type.NOT, MetadataFilter.Type.valueOf("NOT"));
    }
}
