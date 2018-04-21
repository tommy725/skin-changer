package com.github.games647.changeskin.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;

public class RateLimiter  {

    private final Duration expireDuration;
    private final int maximumRequests;

    private final Deque<Instant> requests = new LinkedList<>();

    public RateLimiter(Duration expireDuration, int maximumRequests) {
        this.expireDuration = expireDuration;
        this.maximumRequests = maximumRequests;
    }

    public boolean tryAcquire() {
        if (maximumRequests <= 0) {
            return false;
        }

        Instant now = Instant.now();
        synchronized (requests) {
            if (requests.isEmpty()) {
                requests.push(now);
                return true;
            }

            //check oldest entry if it's expired
            Instant oldest = requests.getLast();
            if (Duration.between(oldest, now).compareTo(expireDuration) >= 0) {
                requests.removeLast();
                requests.push(now);
                return true;
            }

            //oldest entry didn't expired try to insert a new one
            if (requests.size() < maximumRequests) {
                requests.push(now);
                return true;
            }
        }

        return false;
    }

    public Duration getExpireDuration() {
        return expireDuration;
    }

    public int getMaximumRequests() {
        return maximumRequests;
    }
}
