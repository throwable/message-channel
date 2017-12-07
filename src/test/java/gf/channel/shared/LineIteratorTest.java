package gf.channel.shared;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by akuranov on 30/09/2015.
 */
public class LineIteratorTest {
    @Test
    public void testSomeText() {
        LineIterator li = new LineIterator("write\nsome\ntext");
        assertTrue(li.hasNext());
        assertEquals("write", li.next());
        assertEquals("some", li.next());
        assertEquals("text", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testEmptyLines() {
        LineIterator li = new LineIterator("\nwrite\nsome\n\ntext\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertEquals("write", li.next());
        assertEquals("some", li.next());
        assertEquals("", li.next());
        assertEquals("text", li.next());
        // tailing \n not counted
        assertFalse(li.hasNext());
    }

    @Test
    public void testEmptyTailingLines() {
        LineIterator li = new LineIterator("\nwrite\nsome\n\ntext\n\n\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertEquals("write", li.next());
        assertEquals("some", li.next());
        assertEquals("", li.next());
        assertEquals("text", li.next());
        // tailing \n not counted
        assertEquals("", li.next());
        assertEquals("", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testOneLine() {
        LineIterator li = new LineIterator("one");
        assertTrue(li.hasNext());
        assertEquals("one", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testEmptyLine() {
        LineIterator li = new LineIterator("");
        assertFalse(li.hasNext());
    }

    @Test
    public void testEmptyTailingLine() {
        LineIterator li = new LineIterator("\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testTwoTailingEmptyLines() {
        LineIterator li = new LineIterator("\n\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertEquals("", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testNullString() {
        LineIterator li = new LineIterator(null);
        assertFalse(li.hasNext());
    }

    @Test
    public void testCRString() {
        LineIterator li = new LineIterator("write\r\nsome\r\ntext");
        assertTrue(li.hasNext());
        assertEquals("write", li.next());
        assertEquals("some", li.next());
        assertEquals("text", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testCREmptyLines() {
        LineIterator li = new LineIterator("\r\nwrite\r\nsome\r\n\r\ntext\r\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertEquals("write", li.next());
        assertEquals("some", li.next());
        assertEquals("", li.next());
        assertEquals("text", li.next());
        // tailing \n not counted
        assertFalse(li.hasNext());
    }

    @Test
    public void testCREmptyTailingLines() {
        LineIterator li = new LineIterator("\r\nwrite\r\nsome\r\n\r\ntext\r\n\r\n\r\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertEquals("write", li.next());
        assertEquals("some", li.next());
        assertEquals("", li.next());
        assertEquals("text", li.next());
        // tailing \n not counted
        assertEquals("", li.next());
        assertEquals("", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testCREmptyTailingLine() {
        LineIterator li = new LineIterator("\r\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertFalse(li.hasNext());
    }

    @Test
    public void testCRTwoTailingEmptyLines() {
        LineIterator li = new LineIterator("\r\n\r\n");
        assertTrue(li.hasNext());
        assertEquals("", li.next());
        assertEquals("", li.next());
        assertFalse(li.hasNext());
    }
}
