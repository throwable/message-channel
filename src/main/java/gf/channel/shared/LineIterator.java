package gf.channel.shared;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Read string line by line
 * Created by akuranov on 30/09/2015.
 */
public class LineIterator implements Iterator<String> {
    private String text;
    private int index = 0;
    private String line = null;

    public LineIterator(@Nullable String text) {
        this.text = text;
        if (text != null && !text.isEmpty())
            nextLine();
    }

    protected void nextLine()
    {
        if (index >= text.length()) {
            if (line != null) {
                line = null;
                return;
            } else
                throw new NoSuchElementException();
        }
        int n = text.indexOf('\n', index);
        int newIdx;

        if (n >= 0) {
            // windows CR check
            if (n > index) {
                if (text.charAt(n-1) == '\r') {
                    newIdx = n+1;
                    n--;
                } else
                    newIdx = n+1;
            } else
                newIdx = n+1;

            line = text.substring(index, n);
            index = newIdx;
        } else {
            line = text.substring(index);
            index = text.length();
        }
    }

    @Override
    public boolean hasNext() {
        return line != null;
    }

    @Override
    public String next() {
        String s = line;
        nextLine();
        return s;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
