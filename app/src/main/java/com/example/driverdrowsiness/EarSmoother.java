
package com.example.driverdrowsiness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class EarSmoother {

    private static final int WINDOW_SIZE = 5;

    private final Deque<Double> samples = new ArrayDeque<>();

    public synchronized double add(double value) {
        samples.addLast(value);

        while (samples.size() > WINDOW_SIZE) {
            samples.removeFirst();
        }

        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);

        int middle = sorted.size() / 2;

        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }

        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    public synchronized void reset() {
        samples.clear();
    }
}
