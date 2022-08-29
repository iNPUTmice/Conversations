package eu.siacs.conversations.crypto.sasl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A tokenizer for GS2 header strings
 */
public final class Tokenizer implements Iterator<String>, Iterable<String> {
    private final List<String> parts;
    private int index;

    public Tokenizer(final byte[] challenge) {
        final String challengeString = new String(challenge);
        parts = new ArrayList<>(Arrays.asList(challengeString.split(",")));
        // Trim parts.
        for (int i = 0; i < parts.size(); i++) {
            parts.set(i, parts.get(i).trim());
        }
        index = 0;
    }

    /**
     * Returns true if there is at least one more element, false otherwise.
     *
     * @see #next
     */
    @Override
    public boolean hasNext() {
        return parts.size() != index + 1;
    }

    /**
     * Returns the next object and advances the iterator.
     *
     * @return the next object.
     * @throws java.util.NoSuchElementException if there are no more elements.
     * @see #hasNext
     */
    @Override
    public String next() {
        if (hasNext()) {
            return parts.get(index++);
        } else {
            throw new NoSuchElementException("No such element. Size is: " + parts.size());
        }
    }

    /**
     * Removes the last object returned by {@code next} from the collection.
     * This method can only be called once between each call to {@code next}.
     *
     * @throws UnsupportedOperationException if removing is not supported by the collection being
     *                                       iterated.
     * @throws IllegalStateException         if {@code next} has not been called, or {@code remove} has
     *                                       already been called after the last call to {@code next}.
     */
    @Override
    public void remove() {
        if (index <= 0) {
            throw new IllegalStateException("You can't delete an element before first next() method call");
        }
        parts.remove(--index);
    }

    /**
     * Returns an {@link java.util.Iterator} for the elements in this object.
     *
     * @return An {@code Iterator} instance.
     */
    @Override
    public Iterator<String> iterator() {
        return parts.iterator();
    }
}
